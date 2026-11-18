package ru.industryreborn.building;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Чтение чужих схематик: Sponge .schem (версии 1, 2 и 3 - WorldEdit, FAWE) и структур Minecraft .nbt (команда /structure, блок структуры).
 * Результат - сетка blocks[y][z][x] с данными блоков (null - воздух). Содержимое сундуков, таблички и сущности не переносятся.
 */
public final class SchematicImporter {

    /** Размеры и блоки прочитанной схематики. */
    public record Grid(int sizeX, int sizeY, int sizeZ, String[][][] blocks) { }

    private SchematicImporter() {
    }

    private static boolean air(String data) {
        String n = data.split("\\[")[0].toLowerCase(Locale.ROOT);
        return n.equals("minecraft:air") || n.equals("minecraft:cave_air") || n.equals("minecraft:void_air")
                || n.equals("minecraft:structure_void") || n.equals("minecraft:jigsaw") || n.equals("minecraft:structure_block");
    }

    private static String normalize(String data) {
        return data.contains(":") ? data : "minecraft:" + data;
    }

    /** Читает файл по расширению (.schem или .nbt). */
    public static Grid read(String fileName, InputStream in) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        Map<String, Object> root = Nbt.read(in);
        if (lower.endsWith(".schem")) {
            return sponge(root);
        }
        if (lower.endsWith(".nbt")) {
            return structure(root);
        }
        if (lower.endsWith(".schematic")) {
            throw new IOException("Старый формат .schematic (MCEdit) не поддерживается: сохраните схематику в WorldEdit как .schem");
        }
        throw new IOException("Поддерживаются файлы .schem и .nbt");
    }

    private static String[][][] empty(int x, int y, int z) throws IOException {
        if (x < 1 || y < 1 || z < 1 || x > 64 || y > 64 || z > 64) {
            throw new IOException("Размер схематики " + x + "×" + y + "×" + z + ": допускается до 64 по каждой оси");
        }
        return new String[y][z][x];
    }

    // ------------------------------------------------------------ Sponge .schem

    private static Grid sponge(Map<String, Object> top) throws IOException {
        Map<String, Object> root = top.get("Schematic") instanceof Map<?, ?> ? Nbt.map(top.get("Schematic")) : top;
        int version = Nbt.intOf(root.get("Version"), 1);
        int w = Nbt.intOf(root.get("Width"), 0);
        int h = Nbt.intOf(root.get("Height"), 0);
        int l = Nbt.intOf(root.get("Length"), 0);
        Map<String, Object> paletteTag;
        Object dataTag;
        if (version >= 3) {
            Map<String, Object> blocks = Nbt.map(root.get("Blocks"));
            if (blocks == null) {
                throw new IOException("В схематике нет раздела Blocks");
            }
            paletteTag = Nbt.map(blocks.get("Palette"));
            dataTag = blocks.get("Data");
        } else {
            paletteTag = Nbt.map(root.get("Palette"));
            dataTag = root.get("BlockData");
        }
        if (paletteTag == null || !(dataTag instanceof byte[] data)) {
            throw new IOException("Неверная схематика: нет палитры или данных блоков");
        }
        String[][][] grid = empty(w, h, l);
        Map<Integer, String> palette = new TreeMap<>();
        for (var e : paletteTag.entrySet()) {
            palette.put(Nbt.intOf(e.getValue(), -1), normalize(e.getKey()));
        }
        int index = 0;
        int pos = 0;
        long total = (long) w * h * l;
        while (pos < data.length && index < total) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new IOException("Данные блоков оборваны");
                }
                int b = data[pos++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 35) {
                    throw new IOException("Неверные данные блоков");
                }
            }
            String state = palette.get(value);
            if (state != null && !air(state)) {
                int x = index % w;
                int z = (index / w) % l;
                int y = index / (w * l);
                grid[y][z][x] = state;
            }
            index++;
        }
        if (index < total) {
            throw new IOException("Данных блоков меньше, чем размер схематики");
        }
        return new Grid(w, h, l, grid);
    }

    // ------------------------------------------------------------ структура .nbt

    private static Grid structure(Map<String, Object> root) throws IOException {
        if (!(root.get("size") instanceof List<?> size) || size.size() != 3 || !(root.get("blocks") instanceof List<?> blocks)) {
            throw new IOException("Это не структура Minecraft (нет size или blocks)");
        }
        List<?> paletteList = root.get("palette") instanceof List<?> p ? p
                : root.get("palettes") instanceof List<?> ps && !ps.isEmpty() && ps.get(0) instanceof List<?> first ? first : null;
        if (paletteList == null) {
            throw new IOException("В структуре нет палитры");
        }
        String[] palette = new String[paletteList.size()];
        for (int i = 0; i < palette.length; i++) {
            Map<String, Object> entry = Nbt.map(paletteList.get(i));
            if (entry == null || !(entry.get("Name") instanceof String name)) {
                throw new IOException("Неверная запись палитры");
            }
            StringBuilder sb = new StringBuilder(normalize(name));
            Map<String, Object> props = Nbt.map(entry.get("Properties"));
            if (props != null && !props.isEmpty()) {
                sb.append('[');
                boolean first = true;
                for (var e : new TreeMap<>(props).entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(e.getKey()).append('=').append(e.getValue());
                    first = false;
                }
                sb.append(']');
            }
            palette[i] = sb.toString();
        }
        int w = Nbt.intOf(size.get(0), 0);
        int h = Nbt.intOf(size.get(1), 0);
        int l = Nbt.intOf(size.get(2), 0);
        String[][][] grid = empty(w, h, l);
        for (Object o : blocks) {
            Map<String, Object> b = Nbt.map(o);
            if (b == null || !(b.get("pos") instanceof List<?> pos) || pos.size() != 3) {
                throw new IOException("Неверная запись блока");
            }
            int x = Nbt.intOf(pos.get(0), -1);
            int y = Nbt.intOf(pos.get(1), -1);
            int z = Nbt.intOf(pos.get(2), -1);
            int state = Nbt.intOf(b.get("state"), -1);
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l || state < 0 || state >= palette.length) {
                throw new IOException("Блок вне размера структуры");
            }
            if (!air(palette[state])) {
                grid[y][z][x] = palette[state];
            }
        }
        return new Grid(w, h, l, grid);
    }
}
