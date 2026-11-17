package ru.industryreborn.building;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.service.Module;

/**
 * Автостроительство: ставит готовое здание из шаблона на пустой участок при его создании (или по команде). Здание центрируется
 * по участку, основание подсыпается до земли, блоки ставятся пачками по несколько тысяч за тик.
 */
public final class BuildingService {

    /** Результат подбора места. */
    public record Placement(int originX, int originY, int originZ, int quarters, int width, int depth) { }

    private static final String[] BUNDLED = {"house-small", "house-large", "shop-small", "workshop-small", "workshop-forge", "warehouse-small",
        "warehouse-large", "restaurant-small", "hotel-small", "bank", "power-plant"};

    private final IndustryRebornPlugin plugin;
    private final Map<String, BuildingTemplate> templates = new LinkedHashMap<>();

    public BuildingService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    public File folder() {
        return new File(plugin.getDataFolder(), "buildings");
    }

    /** Копирует встроенные здания в папку (если их там ещё нет) и читает все шаблоны. */
    public void load() {
        templates.clear();
        File dir = folder();
        dir.mkdirs();
        for (String name : BUNDLED) {
            File f = new File(dir, name + ".ir.json");
            if (!f.exists()) {
                try (InputStream in = plugin.getResource("buildings/" + name + ".ir.json")) {
                    if (in != null) {
                        Files.copy(in, f.toPath());
                    }
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Не удалось скопировать здание " + name, e);
                }
            }
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".ir.json"));
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files);
        for (File f : files) {
            try (var reader = new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8)) {
                BuildingTemplate t = BuildingTemplate.parse(reader);
                templates.put(t.name().toLowerCase(Locale.ROOT), t);
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("Здание " + f.getName() + " пропущено: " + e.getMessage());
            }
        }
    }

    public List<BuildingTemplate> all() {
        return List.copyOf(templates.values());
    }

    public BuildingTemplate get(String name) {
        return name == null ? null : templates.get(name.toLowerCase(Locale.ROOT));
    }

    public List<BuildingTemplate> forCategory(Category c) {
        List<BuildingTemplate> list = new ArrayList<>();
        for (BuildingTemplate t : templates.values()) {
            if (t.category().equalsIgnoreCase(c.key())) {
                list.add(t);
            }
        }
        return list;
    }

    public boolean enabled() {
        return plugin.modules().enabled(Module.AUTO_BUILD) && plugin.getConfig().getBoolean("auto-build.enabled", true);
    }

    /** Шаблон по умолчанию для типа: заданный в config.yml (auto-build.defaults) либо первый подходящий по размеру. */
    public BuildingTemplate defaultFor(Category c, Box box) {
        String configured = plugin.getConfig().getString("auto-build.defaults." + c.key(), "auto");
        if (!configured.equalsIgnoreCase("auto") && !configured.equalsIgnoreCase("none")) {
            return get(configured);
        }
        if (configured.equalsIgnoreCase("none")) {
            return null;
        }
        BuildingTemplate best = null;
        for (BuildingTemplate t : forCategory(c)) {
            if (fitsAnyRotation(t, box) != -1 && (best == null || t.blockCount() > best.blockCount())) {
                best = t;       // самое большое здание из подходящих
            }
        }
        return best;
    }

    /** Подходит ли шаблон участку; возвращает число четвертей поворота (0 или 1) либо -1. */
    public int fitsAnyRotation(BuildingTemplate t, Box box) {
        int w = box.sizeX();
        int d = box.sizeZ();
        if (t.sizeX() <= w && t.sizeZ() <= d && t.sizeY() <= box.sizeY()) {
            return 0;
        }
        if (t.sizeZ() <= w && t.sizeX() <= d && t.sizeY() <= box.sizeY()) {
            return 1;
        }
        return -1;
    }

    private static boolean natural(Block b) {
        Material m = b.getType();
        return !b.isLiquid() && (m.isAir() || Tag.REPLACEABLE.isTagged(m) || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m) || Tag.FLOWERS.isTagged(m)
                || Tag.SAPLINGS.isTagged(m) || m == Material.SNOW || m == Material.VINE);
    }

    /**
     * Подбирает место: по центру участка, на уровне земли. Возвращает ошибку в виде строки в поле error либо место.
     * force - не проверять, что участок пустой.
     */
    public Object plan(World world, Box box, BuildingTemplate t, int quarters, boolean force) {
        int q = quarters < 0 ? fitsAnyRotation(t, box) : quarters;
        if (q < 0) {
            return "Здание «" + t.title() + "» (" + t.sizeX() + "×" + t.sizeZ() + ") не помещается на участок " + box.sizeX() + "×" + box.sizeZ() + ".";
        }
        int w = t.rotatedX(q);
        int d = t.rotatedZ(q);
        if (w > box.sizeX() || d > box.sizeZ()) {
            return "Здание «" + t.title() + "» после поворота не помещается на участок.";
        }
        int ox = box.minX() + (box.sizeX() - w) / 2;
        int oz = box.minZ() + (box.sizeZ() - d) / 2;
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int x = ox; x < ox + w; x++) {
            for (int z = oz; z < oz + d; z++) {
                int h = groundY(world, x, z, box);
                low = Math.min(low, h);
                high = Math.max(high, h);
            }
        }
        int slope = plugin.getConfig().getInt("auto-build.max-slope", 3);
        if (!force && high - low > slope) {
            return "Участок слишком неровный: перепад высоты " + (high - low) + " (допустимо " + slope + ").";
        }
        int oy = Math.max(box.minY(), high + 1);
        if (oy + t.sizeY() - 1 > box.maxY()) {
            return "Здание не помещается по высоте: участок заканчивается на уровне " + box.maxY() + ".";
        }
        if (!force) {
            for (int x = ox; x < ox + w; x++) {
                for (int z = oz; z < oz + d; z++) {
                    for (int y = oy; y < oy + t.sizeY(); y++) {
                        if (!natural(world.getBlockAt(x, y, z))) {
                            return "На участке уже что-то построено (" + world.getBlockAt(x, y, z).getType().name().toLowerCase(Locale.ROOT)
                                    + " в " + x + " " + y + " " + z + "). Автостроительство ставит здание только на пустой участок.";
                        }
                    }
                }
            }
        }
        return new Placement(ox, oy, oz, q, w, d);
    }

    /** Природный грунт: то, на чём стоит здание. Постройки (доски, кирпич, стекло и т.п.) грунтом не считаются - над ними участок «занят». */
    private static boolean terrain(Material m) {
        if (Tag.DIRT.isTagged(m) || Tag.SAND.isTagged(m) || Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.BASE_STONE_NETHER.isTagged(m)) {
            return true;
        }
        String n = m.name();
        return n.endsWith("_ORE") || n.endsWith("TERRACOTTA") && !n.contains("GLAZED") || n.endsWith("SANDSTONE") && !n.contains("_STAIRS")
                && !n.contains("_SLAB") && !n.contains("_WALL") || switch (m) {
                    case GRAVEL, CLAY, BEDROCK, SNOW_BLOCK, ICE, PACKED_ICE, BLUE_ICE, MYCELIUM, PODZOL, MUD, END_STONE, NETHERRACK,
                         SOUL_SAND, SOUL_SOIL, MAGMA_BLOCK, OBSIDIAN, DEEPSLATE, TUFF, CALCITE, DRIPSTONE_BLOCK, GRASS_BLOCK, DIRT_PATH,
                         FARMLAND, COBBLED_DEEPSLATE -> true;
                    default -> false;
                };
    }

    /** Высота земли: верхний блок грунта колонки в пределах участка (деревья, трава, воздух, вода и постройки не считаются). */
    private int groundY(World world, int x, int z, Box box) {
        for (int y = Math.min(box.maxY(), world.getMaxHeight() - 1); y >= Math.max(box.minY(), world.getMinHeight()); y--) {
            Block b = world.getBlockAt(x, y, z);
            if (terrain(b.getType())) {
                return y;
            }
        }
        return box.minY() - 1;
    }

    private BlockData data(String s) {
        try {
            return Bukkit.createBlockData(s);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Неизвестный блок в здании: " + s);
            return Bukkit.createBlockData(Material.STONE_BRICKS);
        }
    }

    private static StructureRotation rotation(int q) {
        return switch (Math.floorMod(q, 4)) {
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            case 3 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
    }

    /**
     * Ставит здание по плану, пачками по auto-build.blocks-per-tick блоков за тик; done вызывается после последнего блока.
     * Сначала очищается объём здания, затем подсыпается основание до земли.
     */
    public void build(World world, BuildingTemplate t, Placement p, Runnable done, Consumer<String> failed) {
        List<int[]> cells = new ArrayList<>();
        // 1. очистка объёма и основание, 2. сами блоки - всё в одном списке операций
        for (int y = 0; y < t.sizeY(); y++) {
            for (int z = 0; z < p.depth(); z++) {
                for (int x = 0; x < p.width(); x++) {
                    cells.add(new int[] {x, y, z});
                }
            }
        }
        BlockData air = Bukkit.createBlockData(Material.AIR);
        BlockData foundation = data(t.foundation());
        BlockData[] rotated = new BlockData[1];
        int perTick = Math.max(200, plugin.getConfig().getInt("auto-build.blocks-per-tick", 2500));
        int[] index = {0};
        var task = new Object() {
            org.bukkit.scheduler.BukkitTask handle;
        };
        Runnable step = () -> {
            try {
                int end = Math.min(cells.size(), index[0] + perTick);
                for (; index[0] < end; index[0]++) {
                    int[] c = cells.get(index[0]);
                    int[] src = t.source(c[0], c[2], p.quarters());
                    String s = t.blockAt(src[0], c[1], src[1]);
                    Block b = world.getBlockAt(p.originX() + c[0], p.originY() + c[1], p.originZ() + c[2]);
                    if (s == null) {
                        if (b.getType() != Material.AIR) {
                            b.setBlockData(air, false);
                        }
                    } else {
                        BlockData bd = data(s);
                        if (Math.floorMod(p.quarters(), 4) != 0) {
                            bd.rotate(rotation(p.quarters()));
                        }
                        b.setBlockData(bd, false);
                    }
                    if (c[1] == 0) {
                        // основание: от пола здания вниз до земли
                        for (int y = p.originY() - 1; y >= world.getMinHeight() && y >= p.originY() - 12; y--) {
                            Block under = world.getBlockAt(b.getX(), y, b.getZ());
                            if (terrain(under.getType())) {
                                break;
                            }
                            under.setBlockData(foundation, false);
                        }
                    }
                }
                if (index[0] >= cells.size()) {
                    if (task.handle != null) {
                        task.handle.cancel();
                    }
                    done.run();
                }
            } catch (RuntimeException e) {
                if (task.handle != null) {
                    task.handle.cancel();
                }
                failed.accept(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        };
        if (plugin.getConfig().getBoolean("auto-build.synchronous", false) || cells.size() <= perTick) {
            step.run();
            return;
        }
        task.handle = Bukkit.getScheduler().runTaskTimer(plugin, step, 1L, 1L);
    }
}
