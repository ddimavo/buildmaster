package ru.industryreborn.service;

/** Проверка текстов, которые вводят игроки (название и описание участка). */
public final class TextRules {

    private TextRules() { }

    /**
     * Нормализует текст: схлопывает пробелы. Бросает IllegalArgumentException с сообщением для игрока,
     * если текст пустой, слишком длинный или содержит служебные символы.
     */
    public static String clean(String raw, int maxLength, String what) {
        if (raw == null) {
            throw new IllegalArgumentException(what + " не может быть пустым.");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) && !Character.isWhitespace(c)) {
                throw new IllegalArgumentException(what + " содержит недопустимые символы.");
            }
        }
        String text = raw.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(what + " не может быть пустым.");
        }
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(what + " слишком длинное: максимум " + maxLength + " символов.");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '>' || c == '§' || c == '&' || Character.isISOControl(c)) {
                throw new IllegalArgumentException(what + " содержит недопустимые символы (< > & §).");
            }
        }
        return text;
    }

    /** Разбивает текст на строки не длиннее width, не разрывая слова (слишком длинные слова режутся). */
    public static java.util.List<String> wrap(String text, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            while (word.length() > width) {
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
