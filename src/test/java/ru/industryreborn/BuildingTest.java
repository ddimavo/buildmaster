package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.building.BuildingService;
import ru.industryreborn.building.BuildingTemplate;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Module;
import ru.industryreborn.service.SnapshotStore;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.util.ChunkJob;

/** Автостроительство: формат шаблонов, подбор места, расстановка и создание участка со зданием. */
class BuildingTest extends PluginTestBase {

    private static final String TINY = """
            {"name":"tiny","title":"Малый","category":"shop","size":[3,2,3],"foundation":"minecraft:stone_bricks",
             "palette":{"s":"minecraft:stone","d":"minecraft:oak_stairs[facing=north]"},
             "layers":[["sss","s.s","sss"],["d.."]]}
            """;

    private static final class FakeSnapshots extends SnapshotStore {
        int captured;

        @Override
        public void capture(File dir, World world, int cx, int cz, Box clip) {
            captured++;
        }

        @Override
        public void saveMeta(File dir, Box box, Set<UUID> originalEntities) { }

        @Override
        public Set<UUID> originalEntities(World world, Box box) {
            return Set.of();
        }

        @Override
        public boolean exists(File dir) {
            return true;
        }
    }

    private FakeSnapshots snapshots;
    private PlayerMock boss;
    private World world;
    private BuildingTemplate tiny;

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    @BeforeEach
    void setUp() throws IOException {
        ChunkJob.setLoader((w, cx, cz, cb) -> cb.accept(w.getChunkAt(cx, cz), null));
        snapshots = new FakeSnapshots();
        plugin.setSnapshots(snapshots);
        plugin.getConfig().set("auto-build.synchronous", true);
        boss = admin("Boss");
        world = Bukkit.getWorld("world");
        // голограмма участка ставится в позиции админа; в незагруженном чанке она не создаётся (сущности TextDisplay в MockBukkit не поддерживаются)
        boss.teleport(new org.bukkit.Location(world, 1600, 10, 1600));
        tiny = BuildingTemplate.parse(new StringReader(TINY));
    }

    @AfterEach
    void tearDownSeams() {
        ChunkJob.setLoader(null);
    }

    private Object plan(Box box, boolean force) {
        return plugin.buildings().plan(world, box, tiny, -1, force);
    }

    // ------------------------------------------------------------ формат

    @Test
    void templateParsesAndCountsBlocks() {
        assertEquals("tiny", tiny.name());
        assertEquals("Малый", tiny.title());
        assertEquals(3, tiny.sizeX());
        assertEquals(2, tiny.sizeY());
        assertEquals(3, tiny.sizeZ());
        assertEquals("minecraft:stone", tiny.blockAt(0, 0, 0));
        assertNull(tiny.blockAt(1, 0, 1), "точка - воздух");
        assertNull(tiny.blockAt(5, 0, 0), "за пределами - воздух");
        assertNull(tiny.blockAt(0, 5, 0));
        assertEquals(9 - 1 + 1, tiny.blockCount());
    }

    @Test
    void badTemplatesAreRejected() {
        String[] bad = {
            "не json",
            "{\"name\":\"x\",\"size\":[0,1,1],\"palette\":{},\"layers\":[]}",
            "{\"name\":\"x\",\"size\":[65,1,1],\"palette\":{},\"layers\":[]}",
            "{\"name\":\"x\",\"size\":[2,1,2],\"palette\":{\"a\":\"minecraft:stone\"},\"layers\":[[\"ab\"]]}",
            "{\"name\":\"x\",\"size\":[2,1,2],\"palette\":{\"a\":\"minecraft:stone\"},\"layers\":[[\"aaa\"]]}",
            "{\"name\":\"x\",\"size\":[2,1,2],\"palette\":{\"a\":\"minecraft:stone\"},\"layers\":[[\"a\",\"a\",\"a\"]]}",
            "{\"name\":\"x\",\"size\":[2,1,2],\"palette\":{\"a\":\"minecraft:stone\"},\"layers\":[[\"a\"],[\"a\"]]}",
            "{\"name\":\"x\",\"size\":[2,1,2],\"palette\":{\"ab\":\"minecraft:stone\"},\"layers\":[]}",
        };
        for (String s : bad) {
            assertThrows(IOException.class, () -> BuildingTemplate.parse(new StringReader(s)), s);
        }
    }

    @Test
    void rotationMapsCells() throws IOException {
        BuildingTemplate t = BuildingTemplate.parse(new StringReader(
                "{\"name\":\"r\",\"size\":[3,1,2],\"palette\":{\"a\":\"minecraft:stone\"},\"layers\":[[\"a..\",\"...\"]]}"));
        assertEquals(3, t.rotatedX(0));
        assertEquals(2, t.rotatedZ(0));
        assertEquals(2, t.rotatedX(1));
        assertEquals(3, t.rotatedZ(1));
        assertEquals(3, t.rotatedX(2));
        assertEquals(3, t.rotatedX(4));
        // каждая клетка повёрнутой постройки берётся из своей клетки шаблона: взаимно однозначно
        for (int q = 0; q < 4; q++) {
            Set<String> seen = new java.util.HashSet<>();
            for (int x = 0; x < t.rotatedX(q); x++) {
                for (int z = 0; z < t.rotatedZ(q); z++) {
                    int[] s = t.source(x, z, q);
                    assertTrue(s[0] >= 0 && s[0] < 3 && s[1] >= 0 && s[1] < 2, "q=" + q + " " + x + "," + z);
                    seen.add(s[0] + "," + s[1]);
                }
            }
            assertEquals(6, seen.size(), "q=" + q);
        }
        // угол (0,0) шаблона при повороте на 90° по часовой оказывается в правом верхнем углу
        int[] s = t.source(1, 0, 1);
        assertEquals(0, s[0]);
        assertEquals(0, s[1]);
    }

    @Test
    void allBundledTemplatesAreValid() {
        BuildingService svc = plugin.buildings();
        assertEquals(160, svc.all().size(), "по 20 зданий восьми типов");
        for (Category c : new Category[] {Category.HOUSE, Category.SHOP, Category.WORKSHOP, Category.RESTAURANT, Category.WAREHOUSE, Category.HOTEL,
                Category.BANK, Category.POWER_PLANT}) {
            assertEquals(20, svc.forCategory(c).size(), c.name());
        }
        assertEquals(160, svc.all().stream().map(BuildingTemplate::title).distinct().count(), "названия не повторяются");
        for (BuildingTemplate t : svc.all()) {
            assertTrue(t.blockCount() > 50, t.name());
            assertNotNull(Category.valueOf(t.category().toUpperCase()), t.name());
            assertTrue(validId(t.foundation()), t.name());
            for (int y = 0; y < t.sizeY(); y++) {
                for (int z = 0; z < t.sizeZ(); z++) {
                    for (int x = 0; x < t.sizeX(); x++) {
                        String s = t.blockAt(x, y, z);
                        if (s != null) {
                            assertTrue(validId(s), t.name() + " " + s);
                        }
                    }
                }
            }
        }
        assertNotNull(svc.get("BANK"), "поиск без учёта регистра");
        assertNull(svc.get("нет"));
        assertNull(svc.get(null));
        assertFalse(svc.forCategory(Category.HOTEL).isEmpty());
        assertTrue(svc.forCategory(Category.ROOM).isEmpty());
    }

    /** Блок шаблона: minecraft:id[свойства]; материал существует. (MockBukkit не знает части свойств, поэтому blockdata не создаём.) */
    private static boolean validId(String s) {
        var m = java.util.regex.Pattern.compile("minecraft:([a-z0-9_]+)(\\[[a-z_]+=[a-z0-9_]+(,[a-z_]+=[a-z0-9_]+)*\\])?").matcher(s);
        return m.matches() && Material.matchMaterial("minecraft:" + m.group(1)) != null;
    }

    // ------------------------------------------------------------ подбор

    private BuildingTemplate expectedLargest(Category c, Box box) {
        BuildingTemplate best = null;
        for (BuildingTemplate t : plugin.buildings().forCategory(c)) {
            if (plugin.buildings().fitsAnyRotation(t, box) != -1 && (best == null || t.blockCount() > best.blockCount())) {
                best = t;
            }
        }
        return best;
    }

    @Test
    void defaultPicksLargestThatFits() {
        BuildingService svc = plugin.buildings();
        Box big = new Box(0, 0, 0, 40, 30, 40);
        Box medium = new Box(0, 0, 0, 15, 15, 12);
        for (Category c : new Category[] {Category.HOUSE, Category.SHOP, Category.WORKSHOP, Category.RESTAURANT, Category.WAREHOUSE, Category.HOTEL,
                Category.BANK, Category.POWER_PLANT}) {
            for (Box box : new Box[] {big, medium}) {
                BuildingTemplate expected = expectedLargest(c, box);
                assertTrue(expected != null || box == medium, c + " " + box);
                assertSame(expected, svc.defaultFor(c, box), c + " " + box);
            }
        }
        assertTrue(svc.defaultFor(Category.WAREHOUSE, big).blockCount() > svc.defaultFor(Category.WAREHOUSE, medium).blockCount());
        assertNull(svc.defaultFor(Category.WAREHOUSE, new Box(0, 0, 0, 5, 15, 5)));
        assertNull(svc.defaultFor(Category.ROOM, big));
        plugin.getConfig().set("auto-build.defaults.warehouse", "none");
        assertNull(svc.defaultFor(Category.WAREHOUSE, big));
        plugin.getConfig().set("auto-build.defaults.warehouse", "warehouse-small");
        assertEquals("warehouse-small", svc.defaultFor(Category.WAREHOUSE, big).name());
    }

    @Test
    void fitsWithRotation() {
        assertEquals(0, plugin.buildings().fitsAnyRotation(tiny, new Box(0, 0, 0, 2, 1, 2)));
        BuildingTemplate wide = plugin.buildings().get("house-large");
        // 11x9: на участок 9 в ширину и 11 в глубину помещается поворотом
        assertEquals(1, plugin.buildings().fitsAnyRotation(wide, new Box(0, 0, 0, 8, 9, 10)));
        assertEquals(-1, plugin.buildings().fitsAnyRotation(wide, new Box(0, 0, 0, 7, 9, 10)));
        assertEquals(-1, plugin.buildings().fitsAnyRotation(wide, new Box(0, 0, 0, 20, 8, 20)), "высоты не хватает");
    }

    // Плоский мир MockBukkit: бедрок на y=0, трава на y=1..4, выше воздух - земля на высоте 4, здание встаёт с y=5.

    @Test
    void planCentersOnPlotAtGroundLevel() {
        Box box = new Box(10, 0, 10, 20, 20, 20);     // 11x11
        var p = (BuildingService.Placement) plan(box, false);
        assertEquals(14, p.originX());
        assertEquals(14, p.originZ());
        assertEquals(5, p.originY(), "здание стоит на земле");
        assertEquals(3, p.width());
        assertEquals(3, p.depth());
        // холм под зданием: выше всего в колонках здания
        world.getBlockAt(15, 5, 15).setType(Material.DIRT);
        world.getBlockAt(15, 6, 15).setType(Material.DIRT);
        assertEquals(7, ((BuildingService.Placement) plan(box, false)).originY());
        // холм за пределами здания не влияет
        world.getBlockAt(11, 8, 11).setType(Material.DIRT);
        assertEquals(7, ((BuildingService.Placement) plan(box, false)).originY());
    }

    @Test
    void planRefusals() {
        Box box = new Box(10, 0, 10, 20, 20, 20);
        // не помещается по размеру
        assertInstanceOf(String.class, plugin.buildings().plan(world, new Box(0, 0, 0, 1, 20, 1), tiny, -1, false));
        // не хватает высоты: земля на 4, здание 5..6, а участок кончается на 5
        Object low = plugin.buildings().plan(world, new Box(10, 0, 10, 20, 5, 20), tiny, -1, false);
        assertTrue(low instanceof String s && s.contains("высоте"), String.valueOf(low));
        // неровный: столб в 4 блока в области здания (перепад 4 при допустимых 3)
        for (int y = 5; y <= 8; y++) {
            world.getBlockAt(14, y, 14).setType(Material.DIRT);
        }
        Object slope = plan(box, false);
        assertTrue(slope instanceof String s && s.contains("неровный"), String.valueOf(slope));
        assertInstanceOf(BuildingService.Placement.class, plan(box, true), "force игнорирует неровность");
        plugin.getConfig().set("auto-build.max-slope", 4);
        assertInstanceOf(BuildingService.Placement.class, plan(box, false), "допуск настраивается");
    }

    @Test
    void occupiedPlotIsRefusedUnlessForced() {
        Box box = new Box(10, 0, 10, 20, 20, 20);
        // чужая постройка в области здания: доски над грунтом
        world.getBlockAt(15, 6, 15).setType(Material.OAK_PLANKS);
        Object r = plan(box, false);
        assertTrue(r instanceof String s && s.contains("уже что-то построено") && s.contains("oak_planks"), String.valueOf(r));
        assertInstanceOf(BuildingService.Placement.class, plan(box, true));
        // постройка вне области здания (края участка) не мешает
        world.getBlockAt(15, 6, 15).setType(Material.AIR);
        world.getBlockAt(10, 6, 10).setType(Material.OAK_PLANKS);
        assertInstanceOf(BuildingService.Placement.class, plan(box, false));
        // вода в области здания тоже считается занятым местом
        world.getBlockAt(15, 5, 15).setType(Material.WATER);
        assertInstanceOf(String.class, plan(box, false));
    }

    @Test
    void treesAndGrassCountAsNatural() {
        Box box = new Box(10, 0, 10, 20, 20, 20);
        world.getBlockAt(15, 5, 15).setType(Material.SHORT_GRASS);
        world.getBlockAt(15, 5, 14).setType(Material.OAK_LEAVES);
        world.getBlockAt(14, 5, 15).setType(Material.OAK_LOG);
        world.getBlockAt(14, 6, 15).setType(Material.OAK_LOG);
        world.getBlockAt(16, 5, 16).setType(Material.POPPY);
        var p = (BuildingService.Placement) plan(box, false);
        assertEquals(5, p.originY(), "трава, листва, брёвна и цветы не считаются землёй и не мешают");
    }

    // ------------------------------------------------------------ строительство

    @Test
    void buildPlacesBlocksAndClearsVolume() {
        Box box = new Box(10, 0, 10, 20, 20, 20);
        world.getBlockAt(15, 5, 15).setType(Material.OAK_LEAVES);      // внутри здания: будет очищено
        world.getBlockAt(15, 5, 18).setType(Material.OAK_LEAVES);      // вне здания (14..16): не трогаем
        var p = (BuildingService.Placement) plan(box, false);
        boolean[] done = {false};
        List<String> errors = new ArrayList<>();
        plugin.buildings().build(world, tiny, p, () -> done[0] = true, errors::add);
        assertTrue(done[0]);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(Material.STONE, world.getBlockAt(14, 5, 14).getType());
        assertEquals(Material.STONE, world.getBlockAt(16, 5, 16).getType());
        assertEquals(Material.AIR, world.getBlockAt(15, 5, 15).getType(), "внутри здания очищено");
        assertEquals(Material.OAK_STAIRS, world.getBlockAt(14, 6, 14).getType());
        assertEquals(Material.AIR, world.getBlockAt(15, 6, 14).getType());
        assertEquals(Material.OAK_LEAVES, world.getBlockAt(15, 5, 18).getType(), "вне здания не трогаем");
    }

    @Test
    void foundationFillsGapsDownToGround() {
        // яма под зданием: убираем землю в углу, пол здания окажется над пустотой
        world.getBlockAt(14, 4, 14).setType(Material.AIR);
        world.getBlockAt(14, 3, 14).setType(Material.AIR);
        var p = new BuildingService.Placement(14, 5, 14, 0, 3, 3);
        plugin.buildings().build(world, tiny, p, () -> { }, e -> fail(e));
        assertEquals(Material.STONE_BRICKS, world.getBlockAt(14, 4, 14).getType(), "основание подсыпано");
        assertEquals(Material.STONE_BRICKS, world.getBlockAt(14, 3, 14).getType());
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(15, 4, 15).getType(), "где земля есть - не трогаем");
    }

    @Test
    void buildInBatches() {
        plugin.getConfig().set("auto-build.synchronous", false);
        plugin.getConfig().set("auto-build.blocks-per-tick", 200);
        BuildingTemplate big = plugin.buildings().get("warehouse-large");
        var p = new BuildingService.Placement(0, 5, 0, 0, big.sizeX(), big.sizeZ());
        boolean[] done = {false};
        plugin.buildings().build(world, big, p, () -> done[0] = true, e -> fail(e));
        assertFalse(done[0], "за один тик не успевает");
        server.getScheduler().performTicks(1);
        assertFalse(done[0]);
        for (int i = 0; i < 200 && !done[0]; i++) {
            server.getScheduler().performTicks(1);
        }
        assertTrue(done[0]);
        assertNotEquals(Material.AIR, world.getBlockAt(0, 5, 0).getType());
    }

    @Test
    void buildFailureIsReported() {
        // отрицательная высота: координаты вне мира дают исключение, о нём сообщается через failed
        var p = new BuildingService.Placement(0, 500, 0, 0, tiny.sizeX(), tiny.sizeZ());
        List<String> errors = new ArrayList<>();
        boolean[] done = {false};
        plugin.buildings().build(world, tiny, p, () -> done[0] = true, errors::add);
        assertFalse(done[0]);
        assertEquals(1, errors.size());
    }

    // ------------------------------------------------------------ создание участка

    private void select(int x1, int y1, int z1, int x2, int y2, int z2) {
        plugin.selection().setPoint(boss, 1, x1, y1, z1);
        plugin.selection().setPoint(boss, 2, x2, y2, z2);
    }

    private void run(String line) {
        boss.performCommand("district " + line);
        server.getScheduler().performTicks(5);
    }

    @Test
    void createBuildsDefaultBuildingAndSnapshotsAfter() {
        select(0, 0, 0, 14, 20, 10);
        run("create w1 workshop 1000 -");
        Plot plot = plugin.plots().get("w1");
        assertNotNull(plot, drain(boss));
        assertTrue(snapshots.captured > 0, "снимок снят после постройки");
        assertTrue(countSolid(0, 14, 0, 10) > 100, "здание поставлено");
    }

    @Test
    void autoPickPrefersLargestRegardlessOfOrder() throws IOException {
        // файл «aaa» идёт в алфавитном порядке первым, но он меньше любого встроенного магазина
        java.nio.file.Files.writeString(new File(plugin.buildings().folder(), "aaa.ir.json").toPath(), TINY.replace("\"tiny\"", "\"aaa\""));
        plugin.buildings().load();
        assertEquals(161, plugin.buildings().all().size());
        assertNotEquals("aaa", plugin.buildings().defaultFor(Category.SHOP, new Box(0, 0, 0, 20, 20, 20)).name());
        // сломанный файл пропускается, остальные читаются
        java.nio.file.Files.writeString(new File(plugin.buildings().folder(), "bad.ir.json").toPath(), "{}");
        plugin.buildings().load();
        assertEquals(161, plugin.buildings().all().size());
    }

    @Test
    void noBuildSkipsBuilding() {
        select(0, 0, 0, 14, 20, 10);
        run("create w2 workshop 1000 - nobuild");
        assertNotNull(plugin.plots().get("w2"), drain(boss));
        assertEquals(0, countSolid(0, 14, 0, 10));
    }

    @Test
    void autoBuildCanBeDisabledAndBlocksEvenAdmin() {
        plugin.modules().set(Module.AUTO_BUILD, false, "test");
        select(0, 0, 0, 14, 20, 10);
        run("create w3 workshop 1000 -");
        assertNotNull(plugin.plots().get("w3"), "участок создан без здания");
        assertEquals(0, countSolid(0, 14, 0, 10));
        drain(boss);
        select(40, 0, 0, 54, 20, 10);
        run("create w4 workshop 1000 - build");
        assertNull(plugin.plots().get("w4"));
        assertTrue(drain(boss).contains("отключ"));
        drain(boss);
        run("build list");
        assertTrue(drain(boss).toLowerCase().contains("отключ"), "команда build закрыта и для администратора");
    }

    @Test
    void onCreateSettingOffKeepsPlotEmpty() {
        plugin.getConfig().set("auto-build.on-create", false);
        select(0, 0, 0, 14, 20, 10);
        run("create w5 workshop 1000 -");
        assertNotNull(plugin.plots().get("w5"));
        assertEquals(0, countSolid(0, 14, 0, 10));
        run("create w6 workshop 1000 -");   // тот же участок пересекается - ошибка, не падение
        assertNull(plugin.plots().get("w6"));
    }

    @Test
    void explicitTemplateFailureCancelsPlot() {
        select(0, 0, 0, 5, 20, 5);
        run("create w7 workshop 1000 - build workshop-small");
        assertNull(plugin.plots().get("w7"), "здание не помещается - участок не создаётся");
        assertTrue(drain(boss).contains("не помещается"));
        run("create w8 workshop 1000 - build nosuch");
        assertNull(plugin.plots().get("w8"));
        assertTrue(drain(boss).contains("не найдено"));
        run("create w9 workshop 1000 - build");
        assertNull(plugin.plots().get("w9"), "для маленького участка нет подходящего здания");
    }

    @Test
    void autoPickFailureStillCreatesEmptyPlot() {
        select(0, 0, 0, 5, 20, 5);
        run("create w10 workshop 1000 -");
        assertNotNull(plugin.plots().get("w10"), "автоподбор не нашёл здание - участок создан пустым");
    }

    @Test
    void explicitTemplateIsBuilt() {
        select(0, 0, 0, 14, 20, 12);
        run("create w11 shop 1000 - build shop-small");
        assertNotNull(plugin.plots().get("w11"), drain(boss));
        assertTrue(countSolid(0, 14, 0, 12) > 50);
    }

    @Test
    void buildCommandOnFreePlot() {
        Plot plot = freePlot("free1", new Box(0, 0, 0, 12, 20, 10));
        int before = snapshots.captured;
        run("build free1 shop-small");
        assertTrue(countSolid(0, 12, 0, 10) > 50, drain(boss));
        assertTrue(snapshots.captured > before, "исходный вид пересохранён");
    }

    @Test
    void buildCommandRefusals() {
        Plot plot = freePlot("free2", new Box(0, 0, 0, 12, 20, 10));
        run("build free2 нет");
        assertTrue(drain(boss).contains("не найдено"));
        run("build нет shop-small");
        assertTrue(drain(boss).contains("не найден"));
        plot.status = Plot.Status.OWNED;
        run("build free2 shop-small");
        assertTrue(drain(boss).contains("администратор строит на свободных участках и на своих"));
        assertEquals(0, countSolid(0, 12, 0, 10));
    }

    @Test
    void buildCommandRespectsOccupiedAndForce() {
        freePlot("free3", new Box(0, 0, 0, 12, 20, 10));
        world.getBlockAt(6, 6, 5).setType(Material.OAK_PLANKS);       // чья-то постройка в области здания
        run("build free3 shop-small");
        assertTrue(drain(boss).contains("уже что-то построено"));
        assertEquals(0, countSolid(0, 12, 0, 10) - 1, "ничего не построено");
        run("build free3 shop-small force");
        assertTrue(countSolid(0, 12, 0, 10) > 50);
    }

    private Plot freePlot(String id, Box box) {
        Plot plot = new Plot(id, "world", box);
        plot.category = Category.SHOP;
        plot.price = 100;
        plugin.plots().add(plot);
        return plot;
    }

    private int countSolid(int x1, int x2, int z1, int z2) {
        int solid = 0;
        for (int x = x1; x <= x2; x++) {
            for (int y = 5; y <= 20; y++) {
                for (int z = z1; z <= z2; z++) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) {
                        solid++;
                    }
                }
            }
        }
        return solid;
    }
}
