package ru.buildmaster.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

/** Голограммы участков на встроенных сущностях TextDisplay (внешние плагины не нужны). */
public final class HologramService {

    private final BuildMasterPlugin plugin;
    private final NamespacedKey key;
    private final Map<String, java.util.UUID> spawned = new HashMap<>();

    public HologramService(BuildMasterPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "hologram");
    }

    private Component render(Plot plot) {
        List<String> lines = HologramText.lines(plot, plugin.economy()::format);
        return Text.mm(String.join("\n", lines));
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
        Entity existing = spawned.containsKey(plot.id) ? Bukkit.getEntity(spawned.get(plot.id)) : null;
        if (existing instanceof TextDisplay display && display.isValid()) {
            display.text(render(plot));
            display.teleport(new Location(world, plot.holoX, plot.holoY, plot.holoZ));
            return;
        }
        Location loc = new Location(world, plot.holoX, plot.holoY, plot.holoZ);
        TextDisplay display = world.spawn(loc, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setLineWidth(220);
            d.setViewRange(0.6f);
            d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            d.setDefaultBackground(false);
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, plot.id);
            d.text(render(plot));
        });
        spawned.put(plot.id, display.getUniqueId());
    }

    public void remove(Plot plot) {
        java.util.UUID id = spawned.remove(plot.id);
        if (id != null) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
    }

    public void updateAll() {
        for (Plot plot : plugin.plots().all()) {
            update(plot);
        }
    }

    /** Убирает все голограммы (при выключении плагина): они не сохраняются в мире. */
    public void removeAll() {
        for (java.util.UUID id : spawned.values()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        spawned.clear();
    }
}
