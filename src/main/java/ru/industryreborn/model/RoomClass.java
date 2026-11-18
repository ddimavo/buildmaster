package ru.industryreborn.model;

import java.util.Locale;
import org.bukkit.Material;

/** Класс номера отеля. Новый номер по умолчанию - эконом; смену класса управляющий запрашивает, администратор одобряет. */
public enum RoomClass {

    ECONOMY("Эконом", "gray", "economy", 1, Material.LIGHT_GRAY_BED),
    STANDARD("Стандарт", "green", "standard", 2, Material.LIME_BED),
    COMFORT("Комфорт", "aqua", "comfort", 3, Material.CYAN_BED),
    LUXURY("Люкс", "gold", "luxury", 4, Material.YELLOW_BED);

    private final String title;
    private final String color;
    private final String key;
    private final int stars;
    private final Material icon;

    RoomClass(String title, String color, String key, int stars, Material icon) {
        this.title = title;
        this.color = color;
        this.key = key;
        this.stars = stars;
        this.icon = icon;
    }

    public String title() {
        return title;
    }

    /** Ключ для команд и конфигурации: economy, standard, comfort, luxury. */
    public String key() {
        return key;
    }

    public int stars() {
        return stars;
    }

    public Material icon() {
        return icon;
    }

    /** Значок в MiniMessage: «★★☆☆ Комфорт» цветом класса. */
    public String badge() {
        return "<" + color + ">" + "★".repeat(stars) + "<dark_gray>" + "★".repeat(4 - stars) + " <" + color + ">" + title;
    }

    /** Название и звёзды без цвета (для заявок и журнала). */
    public String plain() {
        return title + " (" + "★".repeat(stars) + ")";
    }

    /** Класс по ключу или русскому названию (без учёта регистра); null - не найден. */
    public static RoomClass parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        for (RoomClass c : values()) {
            if (c.key.equals(v) || c.title.toLowerCase(Locale.ROOT).equals(v) || c.name().toLowerCase(Locale.ROOT).equals(v)) {
                return c;
            }
        }
        return switch (v) {
            case "эконом-класс", "econom", "1" -> ECONOMY;
            case "std", "2" -> STANDARD;
            case "3" -> COMFORT;
            case "lux", "4" -> LUXURY;
            default -> null;
        };
    }
}
