package ru.industryreborn.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.holo.Holo;
import ru.industryreborn.holo.HoloSpec;
import ru.industryreborn.model.Plot;

/**
 * Общие голограммы участков. Рисуются встроенными сущностями TextDisplay либо плагином FancyHolograms, если он установлен
 * (см. holograms.backend в config.yml).
 */
public final class HologramService {

    private final IndustryRebornPlugin plugin;
    private final Map<String, Holo> spawned = new HashMap<>();
    private final Map<String, String> lastText = new HashMap<>();

    public HologramService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    private String render(Plot plot) {
        List<String> lines = new java.util.ArrayList<>(HologramText.lines(plot, plugin.economy()::format, plugin.power().nowSec(),
                plugin.power().mode() == PowerService.Mode.USAGE, plugin.grid().offReason(plot)));
        if (plot.category == ru.industryreborn.model.Category.HOTEL) {
            int total = plugin.plots().childrenOf(plot.id).size();
            lines.add("<aqua>Свободных номеров: <white>" + plugin.hotels().freeRooms(plot) + "/" + total);
        }
        return String.join("\n", lines);
    }

    /** Создаёт или обновляет голограмму участка, если чанк загружен. */
    public void update(Plot plot) {
        if (!plot.hasHolo) {
            remove(plot);
            return;
        }
        World world = Bukkit.getWorld(plot.world);
        if (world == null || !world.isChunkLoaded((int) Math.floor(plot.holoX) >> 4, (int) Math.floor(plot.holoZ) >> 4)) {
            return;
        }
        Location loc = new Location(world, plot.holoX, plot.holoY, plot.holoZ);
        String text = render(plot);
        Holo existing = spawned.get(plot.id);
        if (existing != null && existing.valid()) {
            if (!text.equals(lastText.get(plot.id))) {
                existing.text(text);
                lastText.put(plot.id, text);
            }
            existing.teleport(loc);
            return;
        }
        HoloSpec spec = new HoloSpec(loc, text).style(220, 0.6f, 0x6E000000).marker(plot.id);
        spawned.put(plot.id, plugin.holoFactory().spawn("plot_" + plot.id, spec));
        lastText.put(plot.id, text);
    }

    public void remove(Plot plot) {
        Holo h = spawned.remove(plot.id);
        lastText.remove(plot.id);
        if (h != null) {
            h.remove();
        }
    }

    public void updateAll() {
        for (Plot plot : plugin.plots().all()) {
            update(plot);
        }
    }

    /** Убирает все голограммы (при выключении плагина): они не сохраняются в мире. */
    public void removeAll() {
        for (Holo h : spawned.values()) {
            h.remove();
        }
        spawned.clear();
        lastText.clear();
    }
}
