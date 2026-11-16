package ru.buildmaster.model;

import java.util.EnumSet;
import java.util.Locale;
import org.bukkit.Material;

/** Должность в вакансии. Названия фиксированные: свободный текст вакансий не нужен, поэтому модерации он не требует. */
public enum JobRole {
    SELLER("seller", "Продавец", Material.EMERALD, Plot.Perm.CONTAINERS),
    MANAGER("manager", "Администратор", Material.WRITABLE_BOOK, Plot.Perm.CONTAINERS),
    COOK("cook", "Повар", Material.COOKED_BEEF, Plot.Perm.CONTAINERS, Plot.Perm.STATIONS),
    CLEANER("cleaner", "Горничная", Material.BUCKET),
    GUARD("guard", "Охранник", Material.IRON_SWORD),
    KEEPER("keeper", "Кладовщик", Material.BARREL, Plot.Perm.CONTAINERS),
    MASTER("master", "Мастер", Material.ANVIL, Plot.Perm.CONTAINERS, Plot.Perm.STATIONS);

    private final String key;
    private final String title;
    private final Material icon;
    private final EnumSet<Plot.Perm> defaultPerms = EnumSet.noneOf(Plot.Perm.class);

    JobRole(String key, String title, Material icon, Plot.Perm... perms) {
        this.key = key;
        this.title = title;
        this.icon = icon;
        defaultPerms.addAll(java.util.Arrays.asList(perms));
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    /** Права, которые получает работник по умолчанию (строить работники не могут никогда). */
    public EnumSet<Plot.Perm> defaultPerms() {
        return EnumSet.copyOf(defaultPerms);
    }

    public static JobRole parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.toLowerCase(Locale.ROOT);
        for (JobRole r : values()) {
            if (r.key.equals(v) || r.title.toLowerCase(Locale.ROOT).equals(v) || r.name().equalsIgnoreCase(v)) {
                return r;
            }
        }
        return null;
    }
}
