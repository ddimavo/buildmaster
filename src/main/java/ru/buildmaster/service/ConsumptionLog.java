package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Потребление электроэнергии предприятиями по суткам (в единицах V и в деньгах). Хранит последние 30 суток. */
public final class ConsumptionLog {

    public record Sum(double units, double money) {
        public static final Sum ZERO = new Sum(0, 0);
    }

    private static final long DAY = 86_400;
    private static final int KEEP_DAYS = 30;

    private final Map<String, TreeMap<Long, double[]>> days = new HashMap<>();
    private boolean dirty;

    private static String key(String plotId) {
        return plotId.toLowerCase(Locale.ROOT);
    }

    /** Записывает потребление в момент nowSec (биллинговое время). */
    public void record(String plotId, long nowSec, double units, double money) {
        if (units <= 0 && money <= 0) {
            return;
        }
        TreeMap<Long, double[]> map = days.computeIfAbsent(key(plotId), k -> new TreeMap<>());
        double[] bucket = map.computeIfAbsent(nowSec / DAY, k -> new double[2]);
        bucket[0] += units;
        bucket[1] += money;
        while (map.size() > KEEP_DAYS) {
            map.pollFirstEntry();
        }
        dirty = true;
    }

    /** Сумма за последние daysBack суток, включая текущие (1 - только сегодняшние биллинговые сутки). */
    public Sum last(String plotId, long nowSec, int daysBack) {
        TreeMap<Long, double[]> map = days.get(key(plotId));
        if (map == null) {
            return Sum.ZERO;
        }
        long today = nowSec / DAY;
        double units = 0;
        double money = 0;
        for (Map.Entry<Long, double[]> e : map.entrySet()) {
            if (e.getKey() > today - daysBack && e.getKey() <= today) {
                units += e.getValue()[0];
                money += e.getValue()[1];
            }
        }
        return new Sum(units, money);
    }

    public Sum total(String plotId) {
        TreeMap<Long, double[]> map = days.get(key(plotId));
        if (map == null) {
            return Sum.ZERO;
        }
        double units = 0;
        double money = 0;
        for (double[] b : map.values()) {
            units += b[0];
            money += b[1];
        }
        return new Sum(units, money);
    }

    public void clear(String plotId) {
        days.remove(key(plotId));
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void save(File file) throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, TreeMap<Long, double[]>> p : days.entrySet()) {
            for (Map.Entry<Long, double[]> d : p.getValue().entrySet()) {
                yml.set("plots." + p.getKey() + "." + d.getKey(), d.getValue()[0] + "|" + d.getValue()[1]);
            }
        }
        file.getParentFile().mkdirs();
        yml.save(file);
        dirty = false;
    }

    public static ConsumptionLog load(File file) {
        ConsumptionLog log = new ConsumptionLog();
        if (!file.exists()) {
            return log;
        }
        ConfigurationSection plots = YamlConfiguration.loadConfiguration(file).getConfigurationSection("plots");
        if (plots == null) {
            return log;
        }
        for (String plot : plots.getKeys(false)) {
            ConfigurationSection section = plots.getConfigurationSection(plot);
            for (String day : section.getKeys(false)) {
                String[] p = section.getString(day, "0|0").split("\\|");
                log.days.computeIfAbsent(plot, k -> new TreeMap<>())
                        .put(Long.parseLong(day), new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1])});
            }
        }
        return log;
    }
}
