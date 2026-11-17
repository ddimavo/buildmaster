package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

/** Публичная голограмма услуг и товаров мастерской или магазина. Положение задаётся отдельно от общей голограммы. */
public final class ServicesHolograms {

    private static final double VIEW_DISTANCE = 16;

    private final BuildMasterPlugin plugin;
    private final Map<String, ru.buildmaster.holo.Holo> spawned = new HashMap<>();
    private final Map<String, String> lastText = new HashMap<>();

    public ServicesHolograms(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    public void setSpawner(BiFunction<Location, Consumer<TextDisplay>, TextDisplay> spawner) {
        plugin.holoFactory().setSpawner(spawner);
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("holograms.services", true);
    }

    public int spawnedCount() {
        return spawned.size();
    }

    /**
     * Положение голограммы: заданное управляющим либо ниже общей голограммы (общая занимает свои строки, эта ставится под ней).
     * null - положения нет (ни своего, ни общей голограммы).
     */
    public double[] anchor(Plot plot, int lineCount) {
        if (plot.servicesPos != null) {
            return plot.servicesPos;
        }
        if (!plot.hasHolo) {
            return null;
        }
        int mainLines = HologramTextLines.count(plugin, plot);
        return new double[] {plot.holoX, plot.holoY - (mainLines + lineCount) * 0.125 - 0.25, plot.holoZ};
    }

    public void tick() {
        if (!enabled()) {
            removeAll();
            return;
        }
        for (Plot plot : plugin.plots().all()) {
            String key = plot.id.toLowerCase();
            if ((plot.category != Category.WORKSHOP && plot.category != Category.SHOP) || plot.isFree() || plot.busy
                    || plot.restorePending || plot.approval != Plot.Approval.APPROVED) {
                remove(key);
                continue;
            }
            List<String> lines = ServiceBoardText.lines(plugin, plot);
            double[] a = lines.isEmpty() ? null : anchor(plot, lines.size());
            World world = Bukkit.getWorld(plot.world);
            if (a == null || world == null || !world.isChunkLoaded((int) Math.floor(a[0]) >> 4, (int) Math.floor(a[2]) >> 4)) {
                remove(key);
                continue;
            }
            Location at = new Location(world, a[0], a[1], a[2]);
            boolean viewer = false;
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(at) <= VIEW_DISTANCE * VIEW_DISTANCE) {
                    viewer = true;
                    break;
                }
            }
            if (!viewer) {
                remove(key);
                continue;
            }
            show(key, at, String.join("\n", lines));
        }
    }

    private void show(String key, Location at, String text) {
        ru.buildmaster.holo.Holo existing = spawned.get(key);
        if (existing != null && existing.valid()) {
            if (!text.equals(lastText.get(key))) {
                existing.text(text);
                lastText.put(key, text);
            }
            existing.teleport(at);
            return;
        }
        var spec = new ru.buildmaster.holo.HoloSpec(at, text).style(240, 0.4f, 0x82001E14);
        spawned.put(key, plugin.holoFactory().spawn("svc_" + key, spec));
        lastText.put(key, text);
    }

    private void remove(String key) {
        ru.buildmaster.holo.Holo d = spawned.remove(key);
        lastText.remove(key);
        if (d != null) {
            d.remove();
        }
    }

    public void removeAll() {
        for (String key : new ArrayList<>(spawned.keySet())) {
            remove(key);
        }
    }
}
