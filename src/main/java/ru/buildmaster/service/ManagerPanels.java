package ru.buildmaster.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

/**
 * Панель характеристик предприятия над общей голограммой участка. Видна только управляющему, арендодателю и
 * администраторам, и только пока они рядом.
 */
public final class ManagerPanels {

    private static final double VIEW_DISTANCE = 24;

    private final BuildMasterPlugin plugin;
    private final Map<String, ru.buildmaster.holo.Holo> spawned = new HashMap<>();
    private final Map<String, Set<UUID>> viewers = new HashMap<>();
    private final Map<String, String> lastText = new HashMap<>();

    public ManagerPanels(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** Только для тестов: подмена создания голограмм (MockBukkit не умеет TextDisplay). */
    public void setSpawner(java.util.function.BiFunction<Location, java.util.function.Consumer<TextDisplay>, TextDisplay> spawner) {
        plugin.holoFactory().setSpawner(spawner);
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("holograms.manager-panels", true);
    }

    public int spawnedCount() {
        return spawned.size();
    }

    /** Кто видит панель: управляющий, арендодатель участка игрока, администраторы. */
    public static boolean canSee(Plot plot, Player player) {
        return plot.isOwner(player.getUniqueId()) || plot.isLandlord(player.getUniqueId())
                || player.hasPermission("buildmaster.admin");
    }

    public List<String> lines(Plot plot) {
        return StructureText.lines(plugin, plot);
    }

    public void tick() {
        if (!enabled()) {
            removeAll();
            return;
        }
        for (Plot plot : plugin.plots().all()) {
            if ((!plot.hasHolo && plot.managerPos == null) || plot.busy || plot.restorePending
                    || plot.approval != Plot.Approval.APPROVED) {
                remove(plot.id.toLowerCase());
                continue;
            }
            World world = Bukkit.getWorld(plot.world);
            double bx = plot.managerPos != null ? plot.managerPos[0] : plot.holoX;
            double by = plot.managerPos != null ? plot.managerPos[1] : plot.holoY;
            double bz = plot.managerPos != null ? plot.managerPos[2] : plot.holoZ;
            if (world == null || !world.isChunkLoaded((int) Math.floor(bx) >> 4, (int) Math.floor(bz) >> 4)) {
                continue;
            }
            Location base = new Location(world, bx, by, bz);
            Set<Player> nearby = new HashSet<>();
            for (Player p : world.getPlayers()) {
                if (canSee(plot, p) && p.getLocation().distanceSquared(base) <= VIEW_DISTANCE * VIEW_DISTANCE) {
                    nearby.add(p);
                }
            }
            String key = plot.id.toLowerCase();
            if (nearby.isEmpty()) {
                remove(key);
                continue;
            }
            update(plot, base, nearby, key, world);
        }
    }

    private void update(Plot plot, Location base, Set<Player> nearby, String key, World world) {
        List<String> lines = lines(plot);
        // над общей голограммой: её высота растёт с числом строк, панель ставится выше; своя позиция ставится как есть
        int publicLines = plot.managerPos != null ? 0 : HologramTextLines.count(plugin, plot);
        double y = plot.managerPos != null ? base.getY() : base.getY() + (publicLines + lines.size()) * 0.125 + 0.2;
        Location at = new Location(world, base.getX(), y, base.getZ());
        String text = String.join("\n", lines);
        ru.buildmaster.holo.Holo existing = spawned.get(key);
        ru.buildmaster.holo.Holo display;
        if (existing != null && existing.valid()) {
            display = existing;
            if (!text.equals(lastText.get(key))) {
                display.text(text);
                lastText.put(key, text);
            }
            display.teleport(at);
        } else {
            var spec = new ru.buildmaster.holo.HoloSpec(at, text).left(true).style(260, 0.5f, 0x960A1428).perViewer(true);
            display = plugin.holoFactory().spawn("panel_" + key, spec);
            spawned.put(key, display);
            lastText.put(key, text);
            viewers.remove(key);
        }
        Set<UUID> shown = viewers.computeIfAbsent(key, k -> new HashSet<>());
        for (Player p : nearby) {
            if (shown.add(p.getUniqueId())) {
                try {
                    display.show(p);
                } catch (RuntimeException ignored) {
                    // см. выше
                }
            }
        }
        for (UUID id : new HashSet<>(shown)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !nearby.contains(p)) {
                shown.remove(id);
                if (p != null) {
                    try {
                        display.hide(p);
                    } catch (RuntimeException ignored) {
                        // см. выше
                    }
                }
            }
        }
    }

    private void remove(String key) {
        ru.buildmaster.holo.Holo display = spawned.remove(key);
        viewers.remove(key);
        lastText.remove(key);
        if (display != null) {
            display.remove();
        }
    }

    public void removeAll() {
        for (String key : new java.util.ArrayList<>(spawned.keySet())) {
            remove(key);
        }
    }
}
