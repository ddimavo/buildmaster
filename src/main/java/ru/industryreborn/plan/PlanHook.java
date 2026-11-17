package ru.industryreborn.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.ServiceOrder;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.Module;
import ru.industryreborn.service.Treasury;
import ru.industryreborn.util.Text;

/**
 * Связь с плагином Plan (https://github.com/plan-player-analytics/Plan). Если Plan установлен и функция plan включена, регистрируется
 * расширение с показателями плагина. Данные собираются на основном потоке раз в plan.refresh-seconds и отдаются Plan снимком.
 * Классы Plan загружаются только внутри {@link PlanRegistration}.
 */
public final class PlanHook {

    private final IndustryRebornPlugin plugin;
    private volatile PlanSnapshot snapshot;
    private boolean registered;
    private Object extension;
    private long lastRefresh;

    public PlanHook(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean planInstalled() {
        Plugin p = Bukkit.getPluginManager().getPlugin("Plan");
        return p != null && p.isEnabled();
    }

    public boolean registered() {
        return registered;
    }

    public PlanSnapshot snapshot() {
        return snapshot;
    }

    /** Приводит регистрацию в соответствие с настройками: включена ли функция и есть ли Plan. */
    public void sync() {
        boolean want = plugin.modules().enabled(Module.PLAN) && plugin.getConfig().getBoolean("plan.enabled", true) && planInstalled();
        if (want && !registered) {
            register();
        } else if (!want && registered) {
            unregister();
        }
    }

    private void register() {
        try {
            refresh();
            extension = PlanRegistration.register(this);
            registered = extension != null;
            if (registered) {
                plugin.getLogger().info("Расширение Plan зарегистрировано.");
            }
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось зарегистрировать расширение Plan: " + e, e);
            registered = false;
        }
    }

    private void unregister() {
        try {
            PlanRegistration.unregister(extension);
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось снять расширение Plan: " + e);
        }
        registered = false;
        extension = null;
    }

    /** Раз в несколько секунд: пересобирает снимок (основной поток). */
    public void tick() {
        sync();
        long now = System.currentTimeMillis();
        long every = Math.max(5, plugin.getConfig().getInt("plan.refresh-seconds", 60)) * 1000L;
        if ((registered || snapshot == null) && now - lastRefresh >= every) {
            refresh();
        }
    }

    /** Собирает снимок данных. Вызывать на основном потоке. */
    public void refresh() {
        var stats = plugin.stats().collect();
        Map<UUID, PlanSnapshot.PlayerView> players = new HashMap<>();
        Map<UUID, List<Plot>> owned = new HashMap<>();
        for (Plot p : plugin.plots().all()) {
            if (p.owner != null && !p.isFree() && p.parentId == null || p.owner != null && !p.isFree() && p.category == ru.industryreborn.model.Category.ROOM) {
                owned.computeIfAbsent(p.owner, k -> new ArrayList<>()).add(p);
            }
        }
        java.util.Set<UUID> ids = new java.util.HashSet<>(owned.keySet());
        plugin.orders().all().forEach(o -> ids.add(o.client));
        for (UUID id : ids) {
            List<Plot> plots = owned.getOrDefault(id, List.of());
            double revenue = 0;
            double accounts = 0;
            long nearest = Long.MAX_VALUE;
            int businesses = 0;
            List<PlanSnapshot.PlotRow> rows = new ArrayList<>();
            for (Plot p : plots) {
                boolean business = p.category == ru.industryreborn.model.Category.WORKSHOP || p.category == ru.industryreborn.model.Category.SHOP
                        || p.category == ru.industryreborn.model.Category.RESTAURANT || p.category == ru.industryreborn.model.Category.WAREHOUSE
                        || p.category == ru.industryreborn.model.Category.HOTEL;
                if (business) {
                    businesses++;
                    revenue += p.revenue;
                    accounts += plugin.power().balance(p);
                }
                if (p.status == Plot.Status.RENTED) {
                    nearest = Math.min(nearest, p.rentedUntil);
                }
                rows.add(new PlanSnapshot.PlotRow(p.displayName(), p.category.title(),
                        p.status == Plot.Status.RENTED ? Text.date(p.rentedUntil) : "куплен", business ? plugin.economy().format(plugin.power().balance(p)) : "-"));
            }
            int orderActive = 0;
            int orderReady = 0;
            for (ServiceOrder o : plugin.orders().ofClient(id)) {
                if (o.state == ServiceOrder.State.READY) {
                    orderReady++;
                } else {
                    orderActive++;
                }
            }
            players.put(id, new PlanSnapshot.PlayerView(plots.size(), businesses, revenue, nearest == Long.MAX_VALUE ? "-" : Text.date(nearest),
                    plugin.jobs().jobsOf(id).size(), orderActive, orderReady, plugin.storage().countItems(id, StorageEntry.State.AVAILABLE), accounts, rows));
        }
        List<PlanSnapshot.TreasuryRow> treasury = new ArrayList<>();
        List<Treasury.Tx> log = plugin.treasury().log();
        for (int i = log.size() - 1; i >= 0 && treasury.size() < 15; i--) {
            Treasury.Tx tx = log.get(i);
            treasury.add(new PlanSnapshot.TreasuryRow(Text.date(tx.time()), (tx.amount() >= 0 ? "+" : "-") + plugin.economy().format(Math.abs(tx.amount())), tx.reason()));
        }
        snapshot = new PlanSnapshot(stats, players, treasury);
        lastRefresh = System.currentTimeMillis();
    }

    /** Для тестов и расширения: показатели игрока либо null. */
    public PlanSnapshot.PlayerView player(UUID id) {
        PlanSnapshot s = snapshot;
        return s == null ? null : s.players().get(id);
    }
}
