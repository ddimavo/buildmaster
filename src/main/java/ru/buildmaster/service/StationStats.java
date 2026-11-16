package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Статистика использования станков мастерских: операции и израсходованные деньги по участкам и типам. */
public final class StationStats {

    public record Entry(long operations, double spent) { }

    private final Map<String, Map<String, long[]>> ops = new HashMap<>();
    private final Map<String, Map<String, double[]>> spent = new HashMap<>();
    private boolean dirty;

    private static String key(String plotId) {
        return plotId.toLowerCase(Locale.ROOT);
    }

    /** Фиксирует одну операцию на станке типа type (smelter, anvil...) и потраченную на неё сумму. */
    public void record(String plotId, String type, double amount) {
        ops.computeIfAbsent(key(plotId), k -> new TreeMap<>()).computeIfAbsent(type, k -> new long[1])[0]++;
        if (amount > 0) {
            spent.computeIfAbsent(key(plotId), k -> new TreeMap<>()).computeIfAbsent(type, k -> new double[1])[0] += amount;
        }
        dirty = true;
    }

    public Map<String, Entry> byType(String plotId) {
        Map<String, Entry> result = new TreeMap<>();
        Map<String, long[]> o = ops.get(key(plotId));
        if (o == null) {
            return result;
        }
        Map<String, double[]> s = spent.getOrDefault(key(plotId), Map.of());
        for (Map.Entry<String, long[]> e : o.entrySet()) {
            result.put(e.getKey(), new Entry(e.getValue()[0], s.containsKey(e.getKey()) ? s.get(e.getKey())[0] : 0));
        }
        return result;
    }

    public Entry total(String plotId) {
        long n = 0;
        double money = 0;
        for (Entry e : byType(plotId).values()) {
            n += e.operations();
            money += e.spent();
        }
        return new Entry(n, money);
    }

    /** Итоги по всем участкам по типам станков. */
    public Map<String, Entry> totalsByType() {
        Map<String, long[]> n = new TreeMap<>();
        Map<String, double[]> m = new TreeMap<>();
        for (String plot : ops.keySet()) {
            for (Map.Entry<String, Entry> e : byType(plot).entrySet()) {
                n.computeIfAbsent(e.getKey(), k -> new long[1])[0] += e.getValue().operations();
                m.computeIfAbsent(e.getKey(), k -> new double[1])[0] += e.getValue().spent();
            }
        }
        Map<String, Entry> result = new TreeMap<>();
        for (String type : n.keySet()) {
            result.put(type, new Entry(n.get(type)[0], m.get(type)[0]));
        }
        return result;
    }

    /** Участки с наибольшим числом операций (id в нижнем регистре). */
    public List<Map.Entry<String, Entry>> top(int limit) {
        List<Map.Entry<String, Entry>> list = new ArrayList<>();
        for (String plot : ops.keySet()) {
            list.add(Map.entry(plot, total(plot)));
        }
        list.sort(Comparator.comparingLong((Map.Entry<String, Entry> e) -> e.getValue().operations()).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public void clear(String plotId) {
        ops.remove(key(plotId));
        spent.remove(key(plotId));
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void save(File file) throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        for (String plot : ops.keySet()) {
            for (Map.Entry<String, Entry> e : byType(plot).entrySet()) {
                yml.set("plots." + plot + "." + e.getKey() + ".ops", e.getValue().operations());
                yml.set("plots." + plot + "." + e.getKey() + ".spent", e.getValue().spent());
            }
        }
        file.getParentFile().mkdirs();
        yml.save(file);
        dirty = false;
    }

    public static StationStats load(File file) {
        StationStats stats = new StationStats();
        if (!file.exists()) {
            return stats;
        }
        ConfigurationSection plots = YamlConfiguration.loadConfiguration(file).getConfigurationSection("plots");
        if (plots == null) {
            return stats;
        }
        for (String plot : plots.getKeys(false)) {
            ConfigurationSection types = plots.getConfigurationSection(plot);
            for (String type : types.getKeys(false)) {
                stats.ops.computeIfAbsent(plot, k -> new TreeMap<>()).put(type, new long[] {types.getLong(type + ".ops")});
                stats.spent.computeIfAbsent(plot, k -> new TreeMap<>()).put(type, new double[] {types.getDouble(type + ".spent")});
            }
        }
        return stats;
    }
}
