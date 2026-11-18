package ru.industryreborn.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Единый стиль меню: оформление названий, описаний и заголовков. Чистые строковые преобразования MiniMessage,
 * поэтому все меню выглядят одинаково, а текст остаётся прежним.
 */
public final class Style {

    private static final Pattern ACTION = Pattern.compile("^<(yellow|green|red|aqua|gold|light_purple)>((?:ЛКМ|ПКМ|Shift|Нажмите|Клик).*)$");
    private static final Pattern NAME = Pattern.compile("^(<[a-z_#0-9]+>)([^<]+)(.*)$");
    private static final String SEPARATOR = "<dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬▬▬";

    private Style() { }

    /** Название предмета: жирный первый фрагмент (остальное - уточнения в других цветах). */
    public static String name(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("<bold>") || raw.startsWith("<gray>") || raw.startsWith("<dark_gray>")) {
            return raw;
        }
        Matcher m = NAME.matcher(raw);
        if (!m.matches()) {
            return raw;
        }
        return m.group(1) + "<bold>" + m.group(2).stripTrailing() + "</bold>"
                + (m.group(2).endsWith(" ") ? " " : "") + m.group(3);
    }

    /** Строка-действие (ЛКМ, ПКМ, «Нажмите...») в едином виде: цветная стрелка, клавиша белым, пояснение серым. */
    private static String action(String color, String body) {
        int dash = body.indexOf(" - ");
        if (dash > 0 && dash <= 18) {
            return "<" + color + ">▶ <white>" + body.substring(0, dash) + " <gray>— " + body.substring(dash + 3);
        }
        return "<" + color + ">▶ <white>" + body;
    }

    private static String row(String raw) {
        if (raw.startsWith("<gray>") && raw.contains(": ") && !raw.contains("<italic>")) {
            return "<dark_gray>▪ " + raw;
        }
        return raw;
    }

    /** Описание предмета: строки-параметры с маркерами, действия внизу под разделителем. */
    public static List<String> lore(String... lines) {
        List<String> out = new ArrayList<>();
        boolean separated = false;
        boolean content = false;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            if (raw.isEmpty()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) {
                    out.add("");
                }
                continue;
            }
            Matcher a = ACTION.matcher(raw);
            if (a.matches()) {
                if (!separated) {
                    while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
                        out.remove(out.size() - 1);
                    }
                    if (content) {
                        out.add(SEPARATOR);
                    }
                    separated = true;
                }
                out.add(action(a.group(1), a.group(2)));
            } else {
                out.add(row(raw));
                content = true;
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** Заголовок окна: акцентный ромб, раздел жирным тёмным, уточнение (после « · ») серым. */
    public static String title(String raw) {
        String text = raw.replaceAll("</?(dark_gray|dark_red|gray|black|white)>", "").trim();
        if (text.contains(" › ")) {
            // путь: «Район › Мои участки › Булочная» - предки серым, последняя часть жирным
            String[] parts = text.split(" › ");
            StringBuilder sb = new StringBuilder("<#c9811a>◆ ");
            for (int i = 0; i < parts.length - 1; i++) {
                sb.append("<#6b7280>").append(parts[i]).append(" › ");
            }
            return sb.append("<#1f2937><bold>").append(parts[parts.length - 1]).append("</bold>").toString();
        }
        String head = text;
        String tail = "";
        int dot = text.indexOf(" · ");
        if (dot > 0) {
            head = text.substring(0, dot);
            tail = text.substring(dot + 3);
        }
        return "<#c9811a>◆ <#1f2937><bold>" + head + "</bold>" + (tail.isEmpty() ? "" : " <#6b7280>· " + tail);
    }

    /** Полоска из делений: заполненная часть цветом, остальное тёмным. */
    public static String bar(double ratio, int length, String color) {
        double r = Math.max(0, Math.min(1, Double.isNaN(ratio) ? 0 : ratio));
        int filled = (int) Math.round(r * length);
        return "<" + color + ">" + "█".repeat(filled) + "<dark_gray>" + "█".repeat(length - filled);
    }
}
