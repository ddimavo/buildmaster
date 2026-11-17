package ru.industryreborn.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
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
}
