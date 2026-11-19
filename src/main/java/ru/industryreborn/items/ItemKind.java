package ru.industryreborn.items;

import java.util.Locale;

/** Тип предмета плагина: обычный предмет, функциональный инструмент или скин (одноразовый, меняет внешний вид одного инструмента). */
public enum ItemKind {

    ITEM("Предмет"), TOOL("Инструмент"), SKIN("Скин");

    private final String title;

    ItemKind(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    /** Тип по названию (item/tool/skin, русские: предмет, инструмент, скин); null - не распознан. */
    public static ItemKind parse(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "item", "предмет" -> ITEM;
            case "tool", "инструмент" -> TOOL;
            case "skin", "скин" -> SKIN;
            default -> null;
        };
    }
}
