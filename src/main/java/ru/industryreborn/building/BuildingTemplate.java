package ru.industryreborn.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Шаблон здания (.ir.json): размер, палитра «символ - данные блока» и слои сверху вниз по Y; каждый слой - строки по Z, в строке символы по X.
 * Точка «.» - воздух. Файлы читаются и пишутся руками, формат описан в docs/MANUAL.md.
 */
public final class BuildingTemplate {

    private final String name;
    private final String title;
    private final String category;
    private final String description;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final String foundation;
    private final Map<Character, String> palette;
    private final List<List<String>> layers;

    private BuildingTemplate(String name, String title, String category, String description, int x, int y, int z, String foundation,
                             Map<Character, String> palette, List<List<String>> layers) {
        this.name = name;
        this.title = title;
        this.category = category;
        this.description = description;
        this.sizeX = x;
        this.sizeY = y;
        this.sizeZ = z;
        this.foundation = foundation;
        this.palette = palette;
        this.layers = layers;
    }

    public String name() {
        return name;
    }

    public String title() {
        return title;
    }

    public String category() {
        return category;
    }

    public String description() {
        return description;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public String foundation() {
        return foundation;
    }

    /** Данные блока в клетке либо null (воздух). */
    public String blockAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || y >= layers.size()) {
            return null;
        }
        List<String> rows = layers.get(y);
        if (z >= rows.size() || x >= rows.get(z).length()) {
            return null;
        }
        char c = rows.get(z).charAt(x);
        return c == '.' || c == ' ' ? null : palette.get(c);
    }

    /** Сколько блоков в здании (без воздуха). */
    public int blockCount() {
        int n = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (blockAt(x, y, z) != null) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Ширина и глубина после поворота на quarters четвертей (по часовой стрелке). */
    public int rotatedX(int quarters) {
        return Math.floorMod(quarters, 2) == 0 ? sizeX : sizeZ;
    }

    public int rotatedZ(int quarters) {
        return Math.floorMod(quarters, 2) == 0 ? sizeZ : sizeX;
    }

    /** Клетка шаблона для клетки повёрнутой постройки (rx, rz): координаты в исходном шаблоне. */
    public int[] source(int rx, int rz, int quarters) {
        return switch (Math.floorMod(quarters, 4)) {
            case 1 -> new int[] {rz, sizeZ - 1 - rx};
            case 2 -> new int[] {sizeX - 1 - rx, sizeZ - 1 - rz};
            case 3 -> new int[] {sizeX - 1 - rz, rx};
            default -> new int[] {rx, rz};
        };
    }

    // ------------------------------------------------------------ чтение

    public static BuildingTemplate parse(Reader reader) throws IOException {
        try {
            JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
            String name = o.get("name").getAsString();
            JsonArray size = o.getAsJsonArray("size");
            Map<Character, String> palette = new HashMap<>();
            for (var e : o.getAsJsonObject("palette").entrySet()) {
                if (e.getKey().length() != 1) {
                    throw new IOException("Ключ палитры должен быть одним символом: «" + e.getKey() + "»");
                }
                palette.put(e.getKey().charAt(0), e.getValue().getAsString());
            }
            List<List<String>> layers = new ArrayList<>();
            for (JsonElement layer : o.getAsJsonArray("layers")) {
                List<String> rows = new ArrayList<>();
                for (JsonElement row : layer.getAsJsonArray()) {
                    rows.add(row.getAsString());
                }
                layers.add(rows);
            }
            int x = size.get(0).getAsInt();
            int y = Math.max(size.get(1).getAsInt(), 0);
            int z = size.get(2).getAsInt();
            if (x < 1 || z < 1 || y < 1 || x > 64 || z > 64 || y > 64) {
                throw new IOException("Размер здания от 1 до 64 по каждой оси");
            }
            if (layers.size() > y) {
                throw new IOException("Слоёв больше, чем высота");
            }
            for (List<String> rows : layers) {
                if (rows.size() > z) {
                    throw new IOException("Строк в слое больше, чем глубина");
                }
                for (String row : rows) {
                    if (row.length() > x) {
                        throw new IOException("Строка длиннее ширины здания");
                    }
                    for (char c : row.toCharArray()) {
                        if (c != '.' && c != ' ' && !palette.containsKey(c)) {
                            throw new IOException("Символа «" + c + "» нет в палитре");
                        }
                    }
                }
            }
            return new BuildingTemplate(name, o.has("title") ? o.get("title").getAsString() : name,
                    o.has("category") ? o.get("category").getAsString() : "", o.has("description") ? o.get("description").getAsString() : "",
                    x, y, z, o.has("foundation") ? o.get("foundation").getAsString() : "minecraft:stone_bricks", palette, layers);
        } catch (RuntimeException e) {
            throw new IOException("Неверный формат шаблона: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ запись

    /** Символы палитры: печатные ASCII без «.», пробела, кавычки и обратной косой черты, затем латиница-расширенная и кириллица. */
    private static List<Character> symbolPool() {
        List<Character> pool = new ArrayList<>();
        for (char c = '!'; c <= '~'; c++) {
            if (c != '.' && c != '"' && c != '\\') {
                pool.add(c);
            }
        }
        for (char c = 0xC0; c <= 0x24F; c++) {
            pool.add(c);
        }
        for (char c = 0x410; c <= 0x44F; c++) {
            pool.add(c);
        }
        return pool;
    }

    /**
     * Собирает шаблон из сетки blocks[y][z][x] (null - воздух). Палитра строится автоматически; если разных блоков больше,
     * чем доступных символов, выбрасывает IOException.
     */
    public static BuildingTemplate fromGrid(String name, String title, String category, String description, String foundation,
                                            String[][][] blocks) throws IOException {
        int y = blocks.length;
        int z = y == 0 ? 0 : blocks[0].length;
        int x = z == 0 ? 0 : blocks[0][0].length;
        // верхние пустые слои отбрасываем
        int top = y;
        while (top > 0 && layerEmpty(blocks[top - 1])) {
            top--;
        }
        if (top == 0) {
            throw new IOException("В здании нет ни одного блока");
        }
        if (x < 1 || z < 1 || x > 64 || z > 64 || top > 64) {
            throw new IOException("Размер здания от 1 до 64 по каждой оси, а здесь " + x + "×" + top + "×" + z);
        }
        List<Character> pool = symbolPool();
        Map<String, Character> bySymbol = new LinkedHashMap<>();
        Map<Character, String> palette = new LinkedHashMap<>();
        List<List<String>> layers = new ArrayList<>();
        for (int yy = 0; yy < top; yy++) {
            List<String> rows = new ArrayList<>();
            for (int zz = 0; zz < z; zz++) {
                StringBuilder row = new StringBuilder();
                for (int xx = 0; xx < x; xx++) {
                    String data = blocks[yy][zz][xx];
                    if (data == null) {
                        row.append('.');
                        continue;
                    }
                    Character c = bySymbol.get(data);
                    if (c == null) {
                        if (bySymbol.size() >= pool.size()) {
                            throw new IOException("Слишком много разных блоков (больше " + pool.size() + ")");
                        }
                        c = pool.get(bySymbol.size());
                        bySymbol.put(data, c);
                        palette.put(c, data);
                    }
                    row.append(c.charValue());
                }
                rows.add(row.toString());
            }
            layers.add(rows);
        }
        return new BuildingTemplate(name, title == null || title.isBlank() ? name : title, category == null ? "" : category,
                description == null ? "" : description, x, top, z, foundation == null ? "minecraft:stone_bricks" : foundation, palette, layers);
    }

    private static boolean layerEmpty(String[][] layer) {
        for (String[] row : layer) {
            for (String s : row) {
                if (s != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Текст файла .ir.json. */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("format", 1);
        o.addProperty("name", name);
        o.addProperty("title", title);
        o.addProperty("category", category);
        o.addProperty("description", description);
        JsonArray size = new JsonArray();
        size.add(sizeX);
        size.add(sizeY);
        size.add(sizeZ);
        o.add("size", size);
        o.addProperty("foundation", foundation);
        JsonObject pal = new JsonObject();
        for (var e : palette.entrySet()) {
            pal.addProperty(String.valueOf(e.getKey()), e.getValue());
        }
        o.add("palette", pal);
        JsonArray ls = new JsonArray();
        for (List<String> rows : layers) {
            JsonArray a = new JsonArray();
            rows.forEach(a::add);
            ls.add(a);
        }
        o.add("layers", ls);
        return new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(o);
    }
}
