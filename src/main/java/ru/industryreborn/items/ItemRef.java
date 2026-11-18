package ru.industryreborn.items;

import java.util.Locale;
import org.bukkit.Material;

/**
 * Ссылка на предмет в рецептах и файлах: ванильный материал, предмет плагина или предмет Nexo.
 * Записывается как в рецептах Nexo: «IRON_INGOT» (или «minecraft:iron_ingot»), «ir:steel», «nexo:steel».
 * Каноничный ключ ({@link #key()}) одинаков для склада и сравнений: «IRON_INGOT», «ir:steel», «nexo:steel».
 */
public record ItemRef(Kind kind, String id) {

    public enum Kind { VANILLA, PLUGIN, NEXO }

    public static ItemRef vanilla(Material material) {
        return new ItemRef(Kind.VANILLA, material.name());
    }

    /** Разбирает ссылку; null - если запись пустая или материал неизвестен. */
    public static ItemRef parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ir:")) {
            String id = lower.substring(3);
            return id.isEmpty() ? null : new ItemRef(Kind.PLUGIN, id);
        }
        if (lower.startsWith("nexo:")) {
            String id = s.substring(5);
            return id.isEmpty() ? null : new ItemRef(Kind.NEXO, id);
        }
        Material m = Material.matchMaterial(s);
        return m == null || m.isAir() || !m.isItem() ? null : vanilla(m);
    }

    public boolean custom() {
        return kind != Kind.VANILLA;
    }

    /** Ключ для склада и карт: «IRON_INGOT», «ir:steel», «nexo:steel». */
    public String key() {
        return switch (kind) {
            case VANILLA -> id;
            case PLUGIN -> "ir:" + id;
            case NEXO -> "nexo:" + id;
        };
    }

    public Material material() {
        return kind == Kind.VANILLA ? Material.matchMaterial(id) : null;
    }

    /** Ссылка по ключу склада (то, что вернул {@link #key()}). */
    public static ItemRef ofKey(String key) {
        return parse(key);
    }

    /** Ключ склада - предмет плагина или Nexo, а не материал. */
    public static boolean isCustomKey(String key) {
        return key != null && key.indexOf(':') > 0;
    }
}
