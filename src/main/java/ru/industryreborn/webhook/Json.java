package ru.industryreborn.webhook;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Минимальный сборщик JSON (внешние библиотеки не нужны). */
final class Json {

    private Json() { }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Значение: строка, число, boolean, Map, List или null. */
    static String write(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return quote(s);
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                sb.append(quote(String.valueOf(e.getKey()))).append(':').append(write(e.getValue()));
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            return sb.append('}').toString();
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                sb.append(write(l.get(i)));
                if (i < l.size() - 1) {
                    sb.append(',');
                }
            }
            return sb.append(']').toString();
        }
        return quote(String.valueOf(v));
    }
}
