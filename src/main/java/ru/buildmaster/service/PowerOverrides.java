package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Переопределения тарифов, заданные администратором командами. Хранятся отдельно от config.yml
 * (Bukkit стирает комментарии конфига при сохранении). null - значение берётся из конфига.
 * Приоритет: скидка участка, затем переопределение, затем config.yml.
 */
public final class PowerOverrides {

    private Double base;
    private Double progression;
    private Double usageBase;
    private String mode;
    private final Map<String, Double> coefficients = new LinkedHashMap<>();

    public Double base() {
        return base;
    }

    public void setBase(Double value) {
        this.base = value;
    }

    public Double progression() {
        return progression;
    }

    public void setProgression(Double value) {
        this.progression = value;
    }

    public Double usageBase() {
        return usageBase;
    }

    public void setUsageBase(Double value) {
        this.usageBase = value;
    }

    public String mode() {
        return mode;
    }

    public void setMode(String value) {
        this.mode = value;
    }

    public Double coefficient(String type) {
        return coefficients.get(type);
    }

    public void setCoefficient(String type, double value) {
        coefficients.put(type, value);
    }

    /** true - переопределение было и убрано. */
    public boolean resetCoefficient(String type) {
        return coefficients.remove(type) != null;
    }

    public Map<String, Double> coefficients() {
        return Map.copyOf(coefficients);
    }

    public void save(File file) throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("base", base);
        yml.set("progression", progression);
        yml.set("usage-base", usageBase);
        yml.set("mode", mode);
        for (Map.Entry<String, Double> e : coefficients.entrySet()) {
            yml.set("stations." + e.getKey(), e.getValue());
        }
        file.getParentFile().mkdirs();
        yml.save(file);
    }

    public static PowerOverrides load(File file) {
        PowerOverrides o = new PowerOverrides();
        if (!file.exists()) {
            return o;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        o.base = yml.contains("base") ? yml.getDouble("base") : null;
        o.progression = yml.contains("progression") ? yml.getDouble("progression") : null;
        o.usageBase = yml.contains("usage-base") ? yml.getDouble("usage-base") : null;
        o.mode = yml.getString("mode");
        ConfigurationSection stations = yml.getConfigurationSection("stations");
        if (stations != null) {
            for (String key : stations.getKeys(false)) {
                o.coefficients.put(key, stations.getDouble(key));
            }
        }
        return o;
    }
}
