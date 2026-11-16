package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Производственные линии из config.yml (production.lines): переработка сырья, а не создание из ничего. */
public final class ProductionCatalog {

    /**
     * Линия: из inputAmount предметов input получается outputAmount предметов output за seconds секунд на одном станке.
     */
    public record Line(String id, String title, String station, Material input, int inputAmount, Material output,
                       int outputAmount, double seconds, double installPrice) { }

    private final java.util.function.Supplier<FileConfiguration> config;

    public ProductionCatalog(java.util.function.Supplier<FileConfiguration> config) {
        this.config = config;
    }

    public List<Line> lines() {
        List<Line> list = new ArrayList<>();
        ConfigurationSection root = config.get().getConfigurationSection("production.lines");
        if (root == null) {
            return list;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            Material in = Material.matchMaterial(s.getString("input", ""));
            Material out = Material.matchMaterial(s.getString("output", ""));
            if (in == null || out == null || in.isAir() || out.isAir()) {
                continue;
            }
            list.add(new Line(id, s.getString("title", id), s.getString("station", "smelter"), in,
                    Math.max(1, s.getInt("input-amount", 1)), out, Math.max(1, s.getInt("output-amount", 1)),
                    s.getDouble("seconds", 10), s.getDouble("install-price", 0)));
        }
        return list;
    }

    public Line get(String id) {
        for (Line l : lines()) {
            if (l.id().equalsIgnoreCase(id)) {
                return l;
            }
        }
        return null;
    }
}
