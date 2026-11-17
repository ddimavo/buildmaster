package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.listener.SelectionListener;
import ru.industryreborn.model.Box;
import ru.industryreborn.util.BoxOutline;

/**
 * Делает выделение видимым: контуром из частиц показываются выделенная топором область, обе точки выделения и предложенный
 * номер отеля с его дверью. Частицы видит только сам игрок.
 */
public final class SelectionVisualizer {

    /** Куда выводятся частицы (в тестах подменяется). */
    @FunctionalInterface
    public interface Sink {
        void spawn(Player player, double x, double y, double z, Color color, float size);
    }

    public static final Color GOLD = Color.fromRGB(255, 200, 40);
    public static final Color GREEN = Color.fromRGB(60, 235, 90);
    public static final Color RED = Color.fromRGB(255, 70, 70);
    public static final Color CYAN = Color.fromRGB(60, 220, 255);
    public static final Color WHITE = Color.fromRGB(255, 255, 255);

    /** Временная подсветка области (команда show). */
    private record Highlight(String world, Box box, Color color, long until) { }

    private final IndustryRebornPlugin plugin;
    private final java.util.Map<java.util.UUID, List<Highlight>> highlights = new java.util.HashMap<>();
    private Sink sink = (p, x, y, z, color, size) ->
            p.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, new Particle.DustOptions(color, size));

    public SelectionVisualizer(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    /** Только для тестов. */
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    /** Подсвечивает область на несколько секунд (например, существующий участок или номер). */
    public void highlight(Player player, String world, Box box, int seconds) {
        highlights.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>())
                .add(new Highlight(world, box, CYAN, System.currentTimeMillis() + seconds * 1000L));
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("visual.selection-particles", true);
    }

    private int maxPoints() {
        return Math.max(60, plugin.getConfig().getInt("visual.max-points", 400));
    }

    /** Раз в полсекунды: рисует выделения и предложения для каждого игрока. */
    public void tick() {
        if (!enabled()) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission("industryreborn.admin")) {
                render(player);
            }
        }
    }

    /** Рисует всё, что нужно показать этому игроку. Возвращает число выведенных частиц. */
    public int render(Player player) {
        int count = 0;
        SelectionListener.Selection sel = plugin.selection().get(player);
        if (sel != null && sel.world != null && sel.world.equals(player.getWorld().getName())) {
            if (sel.p1 != null) {
                count += outline(player, Box.of(sel.p1[0], sel.p1[1], sel.p1[2], sel.p1[0], sel.p1[1], sel.p1[2]), GREEN, 40, false);
            }
            if (sel.p2 != null) {
                count += outline(player, Box.of(sel.p2[0], sel.p2[1], sel.p2[2], sel.p2[0], sel.p2[1], sel.p2[2]), RED, 40, false);
            }
            Box box = sel.box();
            if (box != null) {
                count += outline(player, box, GOLD, maxPoints(), true);
            }
        }
        RoomAutoService.Pending pending = plugin.roomAuto().pending(player);
        if (pending != null) {
            var hotel = plugin.plots().get(pending.hotelId());
            if (hotel != null && hotel.world.equals(player.getWorld().getName())) {
                count += outline(player, pending.box(), GREEN, maxPoints(), true);
                int[] d = pending.door();
                count += outline(player, new Box(d[0], d[1], d[2], d[0], d[1] + 1, d[2]), CYAN, 60, false);
            }
        }
        var list = highlights.get(player.getUniqueId());
        if (list != null) {
            long now = System.currentTimeMillis();
            list.removeIf(h -> h.until() < now);
            for (Highlight h : list) {
                if (h.world().equals(player.getWorld().getName())) {
                    count += outline(player, h.box(), h.color(), maxPoints(), true);
                }
            }
            if (list.isEmpty()) {
                highlights.remove(player.getUniqueId());
            }
        }
        return count;
    }

    private int outline(Player player, Box box, Color color, int maxPoints, boolean corners) {
        var loc = player.getLocation();
        double range = Math.max(32, plugin.getConfig().getDouble("visual.range", 64));
        double r2 = range * range;
        List<double[]> points = new ArrayList<>(BoxOutline.edgePoints(box, maxPoints));
        int count = 0;
        for (double[] p : points) {
            if (dist2(loc, p) <= r2) {
                sink.spawn(player, p[0], p[1], p[2], color, 1.0f);
                count++;
            }
        }
        if (corners) {
            for (double[] p : BoxOutline.corners(box)) {
                if (dist2(loc, p) <= r2) {
                    sink.spawn(player, p[0], p[1], p[2], WHITE, 2.2f);
                    count++;
                }
            }
        }
        return count;
    }

    private static double dist2(org.bukkit.Location loc, double[] p) {
        double dx = loc.getX() - p[0];
        double dy = loc.getY() - p[1];
        double dz = loc.getZ() - p[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
