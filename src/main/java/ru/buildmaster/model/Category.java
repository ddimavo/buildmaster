package ru.buildmaster.model;

import java.util.Locale;

/** Тип участка. От него зависят лимиты владения. */
public enum Category {
    HOUSE("Дом"),
    SHOP("Магазин"),
    WORKSHOP("Мастерская"),
    RESTAURANT("Ресторан"),
    WAREHOUSE("Склад");

    private final String title;

    Category(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    /** Ключ в config.yml (house, shop...). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Category parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        for (Category c : values()) {
            if (c.key().equals(v) || c.title.toLowerCase(Locale.ROOT).equals(v)) {
                return c;
            }
        }
        return null;
    }
}
