package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.configuration.file.FileConfiguration;

/** Правила: какими блоками можно заменять материалы и что можно ставить при стройке. */
public final class BlockRules {

    private List<Pattern> replaceBanned = List.of();
    private List<Pattern> placeBanned = List.of();
    private List<Material> replaceTargets = List.of();

    public void reload(FileConfiguration cfg) {
        replaceBanned = compile(cfg.getStringList("replace-banned"));
        placeBanned = compile(cfg.getStringList("place-banned"));
        List<Material> list = new ArrayList<>();
        for (Material m : Material.values()) {
            if (canReplaceWith(m)) {
                list.add(m);
            }
        }
        list.sort(Comparator.comparing(m -> m.getKey().getKey()));
        replaceTargets = list;
    }

    private static List<Pattern> compile(List<String> globs) {
        List<Pattern> result = new ArrayList<>();
        for (String g : globs) {
            String regex = Pattern.quote(g.toUpperCase(Locale.ROOT)).replace("*", "\\E.*\\Q");
            result.add(Pattern.compile("^" + regex + "$"));
        }
        return result;
    }

    private static boolean matches(List<Pattern> patterns, Material m) {
        String name = m.name();
        for (Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Материал можно использовать как цель замены (в меню и командой). */
    public boolean canReplaceWith(Material m) {
        return !m.isLegacy() && m.isBlock() && m.isItem() && !m.isAir() && !matches(replaceBanned, m);
    }

    public boolean canPlace(Material m) {
        return !matches(placeBanned, m);
    }

    public List<Material> replaceTargets() {
        return replaceTargets;
    }

    /** Блоки с данными (сундуки, таблички, кровати...) нельзя заменять: данные пропадут. */
    public static boolean isTileEntity(Material m) {
        if (!m.isBlock() || m.isLegacy()) {
            return false;
        }
        try {
            BlockState state = m.createBlockData().createBlockState();
            return state instanceof TileState;
        } catch (Exception e) {
            return false;
        }
    }
}
