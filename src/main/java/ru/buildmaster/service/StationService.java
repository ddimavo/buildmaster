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
    /** Рабочие станки мастерских: участок -> позиция "x,y,z" -> станок. Для голограмм и платной установки. */
    private final Map<String, Map<String, Station>> machines = new HashMap<>();

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
        machines.remove(key(plot));
    }

    // ------------------------------------------------------------ рабочие станки мастерской

    public static String posKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** Платные станки мастерской (позиция -> станок). */
    public Map<String, Station> machines(Plot plot) {
        return machines.getOrDefault(key(plot), Map.of());
    }

    public void addMachine(Plot plot, int x, int y, int z, Station station) {
        machines.computeIfAbsent(key(plot), k -> new HashMap<>()).put(posKey(x, y, z), station);
    }

    public void removeMachine(Plot plot, int x, int y, int z) {
        Map<String, Station> m = machines.get(key(plot));
        if (m != null) {
            m.remove(posKey(x, y, z));
        }
    }

    /** Цена установки станка с данным ключом тарифа (0 - бесплатно). */
    public double installPrice(String tariffKey) {
        return Math.max(0, plugin.getConfig().getDouble("station-install." + tariffKey, 0));
    }

    /**
     * Установка платного станка управляющим: деньги списываются со счёта предприятия, при нехватке - с кошелька
     * управляющего, и уходят в казну. Станок регистрируется как рабочий. Возвращает причину отказа или null.
     */
    public String install(Plot plot, org.bukkit.entity.Player player, Station station, int x, int y, int z) {
        if (!plot.isOwner(player.getUniqueId())) {
            return "Станки в мастерской устанавливает только управляющий: установка платная.";
        }
        double price = installPrice(station.tariffKey());
        double fromAccount = 0;
        double fromWallet = 0;
        if (price > 0) {
            plugin.power().touch(plot);
            double account = plot.account.rawBalance();
            fromAccount = Math.min(account, price);
            fromWallet = price - fromAccount;
            if (fromWallet > 1e-9 && (!plugin.economy().available() || !plugin.economy().has(player, fromWallet))) {
                return "Не хватает денег на установку: " + station.title() + " стоит " + plugin.economy().format(price)
                        + ". На счёте предприятия " + plugin.economy().format(account)
                        + ". Пополните счёт: /district account deposit <сумма>";
            }
            if (fromAccount > 1e-9) {
                plot.account.withdraw(fromAccount);
            }
            if (fromWallet > 1e-9 && !plugin.economy().withdraw(player, fromWallet)) {
                plot.account.deposit(fromAccount);
                return "Не удалось списать деньги за установку станка. Попробуйте ещё раз.";
            }
            plugin.treasury().deposit(price, "Установка станка " + station.title() + ", участок " + plot.id);
            plugin.saveTreasury();
            plugin.plots().save();
        }
        addMachine(plot, x, y, z, station);
        plugin.journal().add(plot.id, player.getUniqueId(), player.getName(), Journal.Action.STATION,
                station.title() + " установлен, списано " + plugin.economy().format(price));
        ru.buildmaster.util.Text.ok(player, "Станок «" + station.title() + "» установлен как рабочий"
                + (price > 0 ? ": списано " + plugin.economy().format(price)
                + (fromWallet > 1e-9 ? " (со счёта " + plugin.economy().format(fromAccount) + ", из кошелька "
                + plugin.economy().format(fromWallet) + ")" : " со счёта предприятия") : "") + ".");
        return null;
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
        Map<String, Station> foundMachines = new HashMap<>();
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
                            if (station.tariffKey() != null) {
                                foundMachines.put(posKey(x, y, z), station);
                            }
                        }
                    }
                }
            }
        }, () -> {
            counts.put(key(plot), fresh);
            machines.put(key(plot), foundMachines);
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
