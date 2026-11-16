package ru.buildmaster.service;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Station;
import ru.buildmaster.util.ChunkJob;

/** Реестр станков по участкам: ведётся по событиям, раз в сутки и после сброса сверяется сканированием. */
public final class StationService {

    private final BuildMasterPlugin plugin;
    private final Map<String, EnumMap<Station, Integer>> counts = new HashMap<>();

    public StationService(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    private static String key(Plot plot) {
        return plot.id.toLowerCase();
    }

    /** Участок известен реестру (подсчёт выполнен). */
    public boolean ready(Plot plot) {
        return counts.containsKey(key(plot));
    }

    /** Регистрирует участок с нулём станков (для вновь созданных и в тестах). */
    public void register(Plot plot) {
        counts.put(key(plot), new EnumMap<>(Station.class));
    }

    public void forget(Plot plot) {
        counts.remove(key(plot));
    }

    public int count(Plot plot, Station station) {
        EnumMap<Station, Integer> m = counts.get(key(plot));
        return m == null ? 0 : m.getOrDefault(station, 0);
    }

    /** Сколько станков делят с данным станком один лимит (все печи; все «прочие» станки). */
    public int groupCount(Plot plot, Station station) {
        int total = 0;
        for (Station s : Station.values()) {
            if (s.limitKey().equals(station.limitKey())) {
                total += count(plot, s);
            }
        }
        return total;
    }

    /** Сколько станков с данным ключом тарифа (smelter, anvil...) стоит в здании. */
    public int countByTariffKey(Plot plot, String tariffKey) {
        int total = 0;
        for (Station s : Station.values()) {
            if (tariffKey.equals(s.tariffKey())) {
                total += count(plot, s);
            }
        }
        return total;
    }

    /** Количество станков по ключам тарифа (smelter, anvil, alchemy, enchanting). */
    public Map<String, Integer> tariffCounts(Plot plot) {
        Map<String, Integer> result = new HashMap<>();
        for (Station s : Station.values()) {
            if (s.tariffKey() != null) {
                int n = count(plot, s);
                if (n > 0) {
                    result.merge(s.tariffKey(), n, Integer::sum);
                }
            }
        }
        return result;
    }

    public void inc(Plot plot, Station station) {
        counts.computeIfAbsent(key(plot), k -> new EnumMap<>(Station.class)).merge(station, 1, Integer::sum);
    }

    public void dec(Plot plot, Station station) {
        EnumMap<Station, Integer> m = counts.get(key(plot));
        if (m != null) {
            m.merge(station, -1, Integer::sum);
            if (m.getOrDefault(station, 0) <= 0) {
                m.remove(station);
            }
        }
    }

    /** Лимит станка для типа участка из конфига (-1 - без ограничений). */
    public int limit(Category category, Station station) {
        return plugin.getConfig().getInt("station-limits." + category.key() + "." + station.limitKey(), -1);
    }

    /** Заново считает станки участка по реальным блокам (по чанку за тик). */
    public void rescan(Plot plot) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            return;
        }
        EnumMap<Station, Integer> fresh = new EnumMap<>(Station.class);
        ChunkJob.run(plugin, world, plot.box, (chunk, clip) -> {
            for (int y = clip.minY(); y <= clip.maxY(); y++) {
                for (int z = clip.minZ(); z <= clip.maxZ(); z++) {
                    for (int x = clip.minX(); x <= clip.maxX(); x++) {
                        Material type = world.getType(x, y, z);
                        if (type.isAir()) {
                            continue;
                        }
                        Station station = Station.fromName(type.name());
                        if (station != null) {
                            fresh.merge(station, 1, Integer::sum);
                        }
                    }
                }
            }
        }, () -> {
            counts.put(key(plot), fresh);
            plugin.power().recalc(plot);
        }, error -> plugin.getLogger().log(Level.WARNING, "Не удалось пересчитать станки участка " + plot.id, error));
    }

    public void rescanAll() {
        for (Plot plot : plugin.plots().all()) {
            if (!plot.busy) {
                rescan(plot);
            }
        }
    }
}
