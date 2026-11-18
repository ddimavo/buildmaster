package ru.industryreborn.building;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Минимальное чтение NBT (для .schem и .nbt): compound -> Map, list -> List, byte/short/int/long/float/double -> числа,
 * byte[]/int[]/long[] -> массивы, string -> String. Файл может быть сжат gzip.
 */
public final class Nbt {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_ARRAY = 64 * 1024 * 1024;

    private Nbt() {
    }

    /** Читает корневой compound файла. */
    public static Map<String, Object> read(InputStream raw) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(raw);
        buffered.mark(2);
        int b1 = buffered.read();
        int b2 = buffered.read();
        buffered.reset();
        InputStream in = b1 == 0x1f && b2 == 0x8b ? new GZIPInputStream(buffered) : buffered;
        try (DataInputStream data = new DataInputStream(in)) {
            int type = data.readUnsignedByte();
            if (type != 10) {
                throw new IOException("Это не NBT-файл (корневой тег не compound)");
            }
            data.readUTF();             // имя корня
            return compound(data, 0);
        }
    }

    public static Map<String, Object> read(byte[] bytes) throws IOException {
        return read(new ByteArrayInputStream(bytes));
    }

    private static Map<String, Object> compound(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT слишком глубокий");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {
                return map;
            }
            String name = in.readUTF();
            map.put(name, value(in, type, depth + 1));
        }
    }

    private static Object value(DataInputStream in, int type, int depth) throws IOException {
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                int n = length(in);
                byte[] a = new byte[n];
                in.readFully(a);
                return a;
            }
            case 8:
                return in.readUTF();
            case 9: {
                int elem = in.readUnsignedByte();
                int n = length(in);
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    list.add(value(in, elem, depth + 1));
                }
                return list;
            }
            case 10:
                return compound(in, depth);
            case 11: {
                int n = length(in);
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case 12: {
                int n = length(in);
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default:
                throw new IOException("Неизвестный тип NBT-тега: " + type);
        }
    }

    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_ARRAY) {
            throw new IOException("Неверная длина массива в NBT: " + n);
        }
        return n;
    }

    // ---- удобные чтения

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    public static int intOf(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }
}
