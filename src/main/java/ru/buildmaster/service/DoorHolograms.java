package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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

/** Голограммы над дверями номеров отелей: номер, статус, гость, срок. Видны, пока рядом есть игроки. */
public final class DoorHolograms {

    private final BuildMasterPlugin plugin;
    private final Map<String, TextDisplay> spawned = new HashMap<>();
    private final Map<String, String> lastText = new HashMap<>();
    private BiFunction<Location, Consumer<TextDisplay>, TextDisplay> spawner;

    public DoorHolograms(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** Только для тестов: MockBukkit не умеет TextDisplay. */
    public void setSpawner(BiFunction<Location, Consumer<TextDisplay>, TextDisplay> spawner) {
        this.spawner = spawner;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("holograms.doors", true);
    }

    public int spawnedCount() {
        return spawned.size();
    }

    private double viewDistance() {
        return Math.max(4, plugin.getConfig().getDouble("holograms.door-view-distance", 14));
    }

    public void tick() {
        if (!enabled()) {
            removeAll();
            return;
        }
        double r2 = viewDistance() * viewDistance();
        java.util.Set<String> alive = new java.util.HashSet<>();
        for (Plot room : plugin.plots().all()) {
            if (room.category != Category.ROOM || room.doors.isEmpty()) {
                continue;
            }
            World world = Bukkit.getWorld(room.world);
            Plot hotel = plugin.hotels().hotelOf(room);
            if (world == null) {
                continue;
            }
            for (int i = 0; i < room.doors.size(); i++) {
                int[] d = room.doors.get(i);
                String key = room.id.toLowerCase() + "#" + i;
                double[] a = DoorText.anchor(room.box, d[0], d[1], d[2]);
                if (a == null || !world.isChunkLoaded(d[0] >> 4, d[2] >> 4)) {
                    continue;
                }
                alive.add(key);
                Location at = new Location(world, a[0], a[1], a[2]);
                boolean viewer = false;
                for (Player p : world.getPlayers()) {
                    if (p.getLocation().distanceSquared(at) <= r2) {
                        viewer = true;
                        break;
                    }
                }
                if (!viewer) {
                    removeOne(key);
                    continue;
                }
                List<String> lines = new java.util.ArrayList<>(DoorText.lines(room, hotel, plugin.economy()::format, System.currentTimeMillis()));
                if (!room.isFree() && plugin.power().isBilled(hotel) && !plugin.power().isPowered(hotel)) {
                    lines.add("<red>⚡ Нет электричества");
                }
                show(key, at, String.join("\n", lines));
            }
        }
        Iterator<Map.Entry<String, TextDisplay>> it = spawned.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!alive.contains(e.getKey())) {
                e.getValue().remove();
                lastText.remove(e.getKey());
                it.remove();
            }
        }
    }

    private void show(String key, Location at, String text) {
        TextDisplay existing = spawned.get(key);
        if (existing != null && existing.isValid()) {
            if (!text.equals(lastText.get(key))) {
                existing.text(Text.mm(text));
                lastText.put(key, text);
            }
            return;
        }
        Consumer<TextDisplay> init = d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setLineWidth(180);
            d.setViewRange(0.25f);
            d.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            d.setDefaultBackground(false);
            d.text(Text.mm(text));
        };
        TextDisplay display = spawner != null ? spawner.apply(at, init) : at.getWorld().spawn(at, TextDisplay.class, init);
        spawned.put(key, display);
        lastText.put(key, text);
    }

    private void removeOne(String key) {
        TextDisplay d = spawned.remove(key);
        lastText.remove(key);
        if (d != null) {
            d.remove();
        }
    }

    public void removeAll() {
        for (String key : new ArrayList<>(spawned.keySet())) {
            removeOne(key);
        }
    }
}
