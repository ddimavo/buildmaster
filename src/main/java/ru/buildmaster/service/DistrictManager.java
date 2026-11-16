package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.buildmaster.model.District;

/** Районы: участок может входить не более чем в один район. */
public final class DistrictManager {

    private final File file;
    private final Map<String, District> districts = new LinkedHashMap<>();

    public DistrictManager(File file) {
        this.file = file;
    }

    private static String key(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    public Collection<District> all() {
        return districts.values();
    }

    public District get(String name) {
        return name == null ? null : districts.get(key(name));
    }

    /** null - район с таким именем уже есть. */
    public District create(String name) {
        if (districts.containsKey(key(name))) {
            return null;
        }
        District d = new District(name);
        districts.put(key(name), d);
        return d;
    }

    public boolean delete(String name) {
        return districts.remove(key(name)) != null;
    }

    public District of(String plotId) {
        for (District d : districts.values()) {
            if (d.plotIds.contains(key(plotId))) {
                return d;
            }
        }
        return null;
    }

    /** Переносит участок в район (убирая из прежнего). */
    public void assign(String plotId, District district) {
        remove(plotId);
        district.plotIds.add(key(plotId));
    }

    public boolean remove(String plotId) {
        boolean removed = false;
        for (District d : districts.values()) {
            removed |= d.plotIds.remove(key(plotId));
        }
        return removed;
    }

    public List<String> plotIdsOf(District d) {
        return new ArrayList<>(d.plotIds);
    }

    public void save() throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        for (District d : districts.values()) {
            yml.set("districts." + d.name, new ArrayList<>(d.plotIds));
        }
        file.getParentFile().mkdirs();
        yml.save(file);
    }

    public void load() {
        districts.clear();
        if (!file.exists()) {
            return;
        }
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("districts");
        if (root == null) {
            return;
        }
        for (String name : root.getKeys(false)) {
            District d = new District(name);
            d.plotIds.addAll(root.getStringList(name));
            districts.put(key(name), d);
        }
    }
}
