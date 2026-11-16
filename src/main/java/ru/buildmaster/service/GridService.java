package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.District;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

/**
 * Электросеть: электростанции (участки типа POWER_PLANT) с мощностью в V в сутки.
 * Предприятие (мастерская) подключено ровно к одной станции. Пока станций нет, сеть не действует
 * и мастерские работают как раньше. Перегрузка станции плавно повышает тариф подключённых предприятий.
 */
public final class GridService {

    private final BuildMasterPlugin plugin;
    private long lastUpdateSec = Long.MIN_VALUE;

    public GridService(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ состояние

    public List<Plot> plants() {
        List<Plot> list = new ArrayList<>();
        for (Plot p : plugin.plots().all()) {
            if (p.category == Category.POWER_PLANT) {
                list.add(p);
            }
        }
        list.sort(Comparator.comparing(p -> p.id.toLowerCase()));
        return list;
    }

    /** Сеть действует, если есть хотя бы одна электростанция. */
    public boolean active() {
        for (Plot p : plugin.plots().all()) {
            if (p.category == Category.POWER_PLANT) {
                return true;
            }
        }
        return false;
    }

    /** Станция, к которой подключено предприятие, или null. */
    public Plot plantOf(Plot plot) {
        if (plot.plantId == null) {
            return null;
        }
        Plot plant = plugin.plots().get(plot.plantId);
        return plant != null && plant.category == Category.POWER_PLANT ? plant : null;
    }

    /** Потребители электросети: мастерские и отели (не номера). */
    public static boolean gridClient(Plot p) {
        return p.category == Category.WORKSHOP || PowerService.isHotel(p);
    }

    /** Подключено ли предприятие к сети (если сети нет - считается подключённым). */
    public boolean onGrid(Plot plot) {
        return !active() || plantOf(plot) != null;
    }

    /** Предприятия, подключённые к станции. */
    public List<Plot> connected(Plot plant) {
        List<Plot> list = new ArrayList<>();
        for (Plot p : plugin.plots().all()) {
            if (p.plantId != null && p.plantId.equalsIgnoreCase(plant.id) && gridClient(p)) {
                list.add(p);
            }
        }
        list.sort(Comparator.comparing(p -> p.id.toLowerCase()));
        return list;
    }

    /** Потребление станции: сумма потребления занятых подключённых мастерских, V в сутки. */
    public double demand(Plot plant) {
        double sum = 0;
        for (Plot p : connected(plant)) {
            if (plugin.power().isBilled(p)) {
                sum += p.account.unitsPerDay();
            }
        }
        return sum;
    }

    public double load(Plot plant) {
        return GridLoad.ratio(demand(plant), plant.plantCapacity);
    }

    /** Множитель тарифа станции, к которой подключено предприятие (1.0, если не подключено). */
    public double multiplier(Plot plot) {
        Plot plant = plantOf(plot);
        return plant == null ? 1.0 : Math.max(1.0, plant.plantMultiplier);
    }

    /** Почему предприятие обесточено сетью, либо null. */
    public String offReason(Plot plot) {
        if (!plugin.power().isBilled(plot) || onGrid(plot)) {
            return null;
        }
        return plot.gridReason != null && !plot.gridReason.isBlank() ? plot.gridReason : "не подключено к электростанции";
    }

    // ------------------------------------------------------------ подключения

    /** Подключает мастерскую к станции (прежнее подключение заменяется). Возвращает ошибку или null. */
    public String connect(Plot plot, Plot plant, String actor) {
        if (!gridClient(plot)) {
            return "К электросети подключаются только мастерские и отели.";
        }
        if (plant.category != Category.POWER_PLANT) {
            return "«" + plant.id + "» не электростанция.";
        }
        if (plant.id.equalsIgnoreCase(plot.plantId)) {
            return "Предприятие уже подключено к станции " + plant.id + ".";
        }
        plugin.power().touch(plot);
        plot.plantId = plant.id;
        plot.lastPlantId = plant.id;
        plot.gridReason = null;
        plugin.power().recalc(plot);
        plugin.journal().add(plot.id, plot.owner, actor, Journal.Action.GRID, "Подключено к станции " + plant.id);
        if (plot.owner != null) {
            plugin.service().warn(plot.owner, plot.id, "<green>Предприятие <white>" + Text.esc(plot.id)
                    + "</white> подключено к электростанции <white>" + Text.esc(plant.id) + "</white>.");
        }
        return null;
    }

    /** Отключает предприятие от станции с причиной; тариф не начисляется, станки не работают. */
    public String disconnect(Plot plot, String reason, String actor) {
        if (!gridClient(plot)) {
            return "К электросети подключаются только мастерские и отели.";
        }
        if (plot.plantId == null) {
            return "Предприятие уже отключено.";
        }
        if (reason == null || reason.isBlank()) {
            return "Укажите причину отключения.";
        }
        plugin.power().touch(plot);
        plot.lastPlantId = plot.plantId;
        plot.plantId = null;
        plot.gridReason = reason;
        plugin.power().recalc(plot);
        plugin.journal().add(plot.id, plot.owner, actor, Journal.Action.GRID, "Отключено от сети: " + reason);
        if (plot.owner != null) {
            plugin.service().warn(plot.owner, plot.id, "<red>Ваше предприятие <white>" + Text.esc(plot.id)
                    + "</white> отключено от электростанции. Причина: " + Text.esc(reason)
                    + ". Тариф не начисляется, станки не работают.");
        }
        return null;
    }

    /** Возвращает предприятие к станции, от которой оно было отключено. */
    public String restore(Plot plot, String actor) {
        if (plot.plantId != null) {
            return "Предприятие уже подключено.";
        }
        Plot plant = plot.lastPlantId == null ? null : plugin.plots().get(plot.lastPlantId);
        if (plant == null || plant.category != Category.POWER_PLANT) {
            plant = leastLoaded(plot);
        }
        if (plant == null) {
            return "Нет электростанции, к которой можно подключить.";
        }
        return connect(plot, plant, actor);
    }

    /** Станция, на которой после подключения нагрузка будет наименьшей. */
    public Plot leastLoaded(Plot plot) {
        Plot best = null;
        double bestRatio = Double.POSITIVE_INFINITY;
        for (Plot plant : plants()) {
            double ratio = GridLoad.ratio(demand(plant) + plot.account.unitsPerDay(), plant.plantCapacity);
            if (best == null || ratio < bestRatio) {
                best = plant;
                bestRatio = ratio;
            }
        }
        return best;
    }

    /** Подключает мастерскую к наименее загруженной станции, если она ещё не подключена. */
    public boolean autoConnect(Plot plot, String actor) {
        if (!gridClient(plot) || plot.plantId != null || !active()) {
            return false;
        }
        Plot plant = leastLoaded(plot);
        return plant != null && connect(plot, plant, actor) == null;
    }

    /** Подключает все неподключённые мастерские. Возвращает их число. */
    public int autoConnectAll(String actor) {
        int n = 0;
        for (Plot p : new ArrayList<>(plugin.plots().all())) {
            if (gridClient(p) && p.plantId == null && autoConnect(p, actor)) {
                n++;
            }
        }
        return n;
    }

    public List<Plot> plotsOf(District district) {
        List<Plot> list = new ArrayList<>();
        for (String id : district.plotIds) {
            Plot p = plugin.plots().get(id);
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }

    // ------------------------------------------------------------ отчёты

    /** Сводка потребления группы предприятий. */
    public record Report(int plots, double demand, ConsumptionLog.Sum day, ConsumptionLog.Sum week) { }

    /** Потребление группы предприятий: текущая нагрузка (V/сут), за сутки и за 7 суток. */
    public Report report(java.util.Collection<Plot> plots) {
        long now = plugin.power().nowSec();
        int n = 0;
        double demand = 0;
        double u1 = 0;
        double m1 = 0;
        double u7 = 0;
        double m7 = 0;
        for (Plot p : plots) {
            if (!gridClient(p)) {
                continue;
            }
            n++;
            if (plugin.power().isBilled(p)) {
                demand += p.account.unitsPerDay();
            }
            ConsumptionLog.Sum d = plugin.power().consumption().last(p.id, now, 1);
            ConsumptionLog.Sum w = plugin.power().consumption().last(p.id, now, 7);
            u1 += d.units();
            m1 += d.money();
            u7 += w.units();
            m7 += w.money();
        }
        return new Report(n, demand, new ConsumptionLog.Sum(u1, m1), new ConsumptionLog.Sum(u7, m7));
    }

    // ------------------------------------------------------------ мощность и перегрузка

    /** Задаёт мощность станции (V в сутки). Возвращает ошибку или null. */
    public String setCapacity(Plot plant, double capacity, String actor) {
        if (plant.category != Category.POWER_PLANT) {
            return "«" + plant.id + "» не электростанция.";
        }
        if (!(capacity >= 0) || Double.isInfinite(capacity)) {
            return "Мощность должна быть числом не меньше нуля.";
        }
        plant.plantCapacity = capacity;
        plugin.plots().save();
        plugin.holograms().update(plant);
        plugin.journal().add(plant.id, null, actor, Journal.Action.GRID, "Мощность станции: " + capacity + " V/сут");
        return null;
    }

    /** Один шаг регулировки тарифа станции: при перегрузке множитель растёт понемногу, при норме снижается. */
    public void updatePlant(Plot plant) {
        double demand = demand(plant);
        boolean over = GridLoad.overloaded(demand, plant.plantCapacity);
        double old = Math.max(1.0, plant.plantMultiplier);
        double next = GridLoad.nextMultiplier(old, over,
                plugin.getConfig().getDouble("grid.surcharge-step", 0.02),
                plugin.getConfig().getDouble("grid.max-multiplier", 3.0));
        if (next == old) {
            return;
        }
        List<Plot> plots = connected(plant);
        for (Plot p : plots) {
            plugin.power().touch(p);    // расход до изменения закрывается по старому множителю
        }
        plant.plantMultiplier = next;
        for (Plot p : plots) {
            plugin.power().recalc(p);
        }
        plugin.plots().save();
        plugin.holograms().update(plant);
        if (old == 1.0 && next > 1.0) {
            notifyConnected(plots, "<yellow>Нагрузка на электростанцию <white>" + Text.esc(plant.id)
                    + "</white> превышена: тариф повышается понемногу (сейчас ×" + next + ").");
            plugin.journal().add(plant.id, null, "система", Journal.Action.GRID, "Перегрузка: множитель тарифа ×" + next);
        } else if (next == 1.0 && old > 1.0) {
            notifyConnected(plots, "<green>Нагрузка на электростанцию <white>" + Text.esc(plant.id)
                    + "</white> в норме: надбавка к тарифу снята.");
            plugin.journal().add(plant.id, null, "система", Journal.Action.GRID, "Нагрузка в норме, множитель ×1.0");
        }
    }

    private void notifyConnected(List<Plot> plots, String message) {
        for (Plot p : plots) {
            if (p.owner != null && plugin.power().isBilled(p)) {
                plugin.service().warn(p.owner, p.id, message);
            }
        }
    }

    public void updateAll() {
        for (Plot plant : plants()) {
            updatePlant(plant);
        }
    }

    /** Вызывается из периодической задачи: шаг регулировки раз в grid.update-interval-seconds. */
    public void tick() {
        long now = plugin.power().nowSec();
        long interval = Math.max(1, plugin.getConfig().getLong("grid.update-interval-seconds", 600));
        if (lastUpdateSec == Long.MIN_VALUE) {
            lastUpdateSec = now;
            return;
        }
        if (now - lastUpdateSec >= interval) {
            lastUpdateSec = now;
            updateAll();
        }
    }
}
