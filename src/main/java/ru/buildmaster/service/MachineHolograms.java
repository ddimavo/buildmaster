package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Station;
import ru.buildmaster.util.Text;

/**
 * Маленькие голограммы над станками мастерской: статус, прогресс плавки или варки, сырьё, топливо и продукция.
 * Создаются только пока рядом есть игроки и чанк загружен, исчезают, когда станок убран.
 */
public final class MachineHolograms {

    private static final double VIEW_DISTANCE = 12;

    private final BuildMasterPlugin plugin;
    private final Map<String, TextDisplay> spawned = new HashMap<>();
    private java.util.function.BiFunction<Location, java.util.function.Consumer<TextDisplay>, TextDisplay> spawner;
    private final Map<String, String> lastText = new HashMap<>();

    public MachineHolograms(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** Только для тестов: подмена создания голограмм (MockBukkit не умеет TextDisplay). */
    public void setSpawner(java.util.function.BiFunction<Location, java.util.function.Consumer<TextDisplay>, TextDisplay> spawner) {
        this.spawner = spawner;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("holograms.machines", true);
    }

    private static String id(Plot plot, String pos) {
        return plot.id.toLowerCase() + "@" + pos;
    }

    /** Число созданных голограмм (для проверок). */
    public int spawnedCount() {
        return spawned.size();
    }

    public void tick() {
        if (!enabled()) {
            removeAll();
            return;
        }
        for (Plot plot : plugin.plots().all()) {
            if (plot.category != Category.WORKSHOP || plot.isFree() || plot.busy || plot.restorePending) {
                continue;
            }
            World world = Bukkit.getWorld(plot.world);
            if (world == null) {
                continue;
            }
            for (var e : new ArrayList<>(plugin.stations().machines(plot).entrySet())) {
                handle(plot, world, e.getKey(), e.getValue());
            }
        }
        // голограммы станков, которых больше нет в реестре (участок освобождён и т.п.)
        Iterator<Map.Entry<String, TextDisplay>> it = spawned.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            String key = entry.getKey();
            int at = key.indexOf('@');
            Plot plot = plugin.plots().get(key.substring(0, at));
            if (plot == null || plot.isFree() || !plugin.stations().machines(plot).containsKey(key.substring(at + 1))) {
                entry.getValue().remove();
                lastText.remove(key);
                it.remove();
            }
        }
    }

    private void handle(Plot plot, World world, String pos, Station expected) {
        String[] c = pos.split(",");
        int x = Integer.parseInt(c[0]);
        int y = Integer.parseInt(c[1]);
        int z = Integer.parseInt(c[2]);
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        if (Station.fromName(block.getType().name()) != expected) {
            // станок разрушен без события (взрыв, поршень): убираем запись
            plugin.stations().removeMachine(plot, x, y, z);
            return;
        }
        String key = id(plot, pos);
        Location at = new Location(world, x + 0.5, y + 1.35, z + 0.5);
        boolean viewer = false;
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= VIEW_DISTANCE * VIEW_DISTANCE) {
                viewer = true;
                break;
            }
        }
        if (!viewer) {
            removeOne(key);
            return;
        }
        String text = String.join("\n", lines(plot, block, expected));
        TextDisplay existing = spawned.get(key);
        if (existing != null && existing.isValid()) {
            TextDisplay display = existing;
            if (!text.equals(lastText.get(key))) {
                display.text(Text.mm(text));
                lastText.put(key, text);
            }
            return;
        }
        java.util.function.Consumer<TextDisplay> init = d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setLineWidth(200);
            d.setViewRange(0.3f);
            d.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            d.setDefaultBackground(false);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.5f, 0.5f, 0.5f),
                    new AxisAngle4f()));
            d.text(Text.mm(text));
        };
        TextDisplay display = spawner != null ? spawner.apply(at, init) : world.spawn(at, TextDisplay.class, init);
        spawned.put(key, display);
        lastText.put(key, text);
    }

    private void removeOne(String key) {
        TextDisplay display = spawned.remove(key);
        lastText.remove(key);
        if (display != null) {
            display.remove();
        }
    }

    public void removeAll() {
        for (String key : new ArrayList<>(spawned.keySet())) {
            removeOne(key);
        }
    }

    // ------------------------------------------------------------ содержимое

    /** Название предмета для клиента на его языке. */
    static String itemName(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        try {
            return "<lang:" + item.translationKey() + ">";
        } catch (RuntimeException | LinkageError e) {
            return item.getType().name().toLowerCase().replace('_', ' ');
        }
    }

    private static String blockTitle(Material type) {
        return switch (type) {
            case FURNACE -> "Печь";
            case BLAST_FURNACE -> "Плавильная печь";
            case SMOKER -> "Коптильня";
            case BREWING_STAND -> "Варочная стойка";
            case ENCHANTING_TABLE -> "Стол зачарований";
            default -> type.name().startsWith("ANVIL") || type.name().endsWith("ANVIL") ? "Наковальня" : type.name();
        };
    }

    /** Строки голограммы станка (MiniMessage). */
    public List<String> lines(Plot plot, Block block, Station station) {
        var power = plugin.power();
        boolean powered = power.isPowered(plot);
        String off = plugin.grid().offReason(plot);
        String key = station.tariffKey();
        String cost = power.mode() == PowerService.Mode.USAGE
                ? "Операция: " + plugin.economy().format(power.operationPrice(plot, key))
                : "Мощность: " + String.format(java.util.Locale.ROOT, "%.1f", power.coefficient(key)) + " V/сут";
        String title = blockTitle(block.getType());
        ProductionService.View view = plugin.factory().view(plot, StationService.posKey(block.getX(), block.getY(), block.getZ()));
        if (view.recipe() == null) {
            return MachineText.unassigned(title, powered, off, cost);
        }
        RecipeBook.Recipe recipe = view.recipe();
        List<String> inputs = new ArrayList<>();
        for (RecipeBook.Ingredient ing : recipe.inputs()) {
            Material shown = ing.options().get(0);
            int have = 0;
            for (Material m : ing.options()) {
                int n = plugin.factory().stock(plot, m);
                have += n;
                if (n > 0 && plugin.factory().stock(plot, shown) == 0) {
                    shown = m;
                }
            }
            inputs.add(itemName(new ItemStack(shown)) + " × " + have + " <dark_gray>(по " + ing.count() + ")");
        }
        return MachineText.production(title, itemName(new ItemStack(recipe.output())), view.status().title(),
                view.status() == ProductionService.Status.WORKING, view.ratio(), inputs, itemName(new ItemStack(recipe.output())),
                view.output(), view.bufferCapacity(), cost);
    }
}
