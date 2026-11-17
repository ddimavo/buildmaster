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

/**
 * Голограммы банка: большая доска (налоги, комиссии, казна) и отдельная доска последних операций казны.
 * Положение каждой двигается отдельно: «services» - доска налогов, «log» - доска операций.
 */
public final class BankHolograms {

    private static final double VIEW_DISTANCE = 20;

    private final BuildMasterPlugin plugin;
    private final Map<String, ru.buildmaster.holo.Holo> spawned = new HashMap<>();
    private final Map<String, String> lastText = new HashMap<>();

    public BankHolograms(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    public void setSpawner(BiFunction<Location, Consumer<TextDisplay>, TextDisplay> spawner) {
        plugin.holoFactory().setSpawner(spawner);
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("holograms.bank", true);
    }

    public int spawnedCount() {
        return spawned.size();
    }

    /** Положение доски налогов: заданное или под общей голограммой банка. null - положения нет. */
    public double[] boardAnchor(Plot bank, int lineCount) {
        if (bank.servicesPos != null) {
            return bank.servicesPos;
        }
        if (!bank.hasHolo) {
            return null;
        }
        int mainLines = HologramTextLines.count(plugin, bank);
        return new double[] {bank.holoX, bank.holoY - (mainLines + lineCount) * 0.125 - 0.25, bank.holoZ};
    }

    /** Положение доски операций: заданное или под доской налогов. */
    public double[] logAnchor(Plot bank, int boardLines, int logLines) {
        if (bank.logPos != null) {
            return bank.logPos;
        }
        double[] b = boardAnchor(bank, boardLines);
        return b == null ? null : new double[] {b[0], b[1] - (boardLines + logLines) * 0.125 - 0.3, b[2]};
    }

    public void tick() {
        if (!enabled()) {
            removeAll();
            return;
        }
        for (Plot plot : plugin.plots().all()) {
            String boardKey = plot.id.toLowerCase() + "#board";
            String logKey = plot.id.toLowerCase() + "#log";
            if (plot.category != Category.BANK || plot.busy || plot.restorePending) {
                remove(boardKey);
                remove(logKey);
                continue;
            }
            List<String> board = BankBoardText.board(plugin, plot);
            List<String> log = BankBoardText.log(plugin, plot);
            display(boardKey, plot, boardAnchor(plot, board.size()), board);
            display(logKey, plot, logAnchor(plot, board.size(), log.size()), log);
        }
    }

    private void display(String key, Plot plot, double[] a, List<String> lines) {
        World world = Bukkit.getWorld(plot.world);
        if (a == null || world == null || !world.isChunkLoaded((int) Math.floor(a[0]) >> 4, (int) Math.floor(a[2]) >> 4)) {
            remove(key);
            return;
        }
        Location at = new Location(world, a[0], a[1], a[2]);
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= VIEW_DISTANCE * VIEW_DISTANCE) {
                show(key, at, String.join("\n", lines));
                return;
            }
        }
        remove(key);
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
        var spec = new ru.buildmaster.holo.HoloSpec(at, text).style(260, 0.5f, 0x8C141400);
        spawned.put(key, plugin.holoFactory().spawn("bank_" + key, spec));
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
