package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.SelectionVisualizer;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.util.ChunkAccess;
import ru.industryreborn.util.ChunkJob;

/**
 * Номер по двери: поиск помещения за дверью, подтверждение и отмена; видимое выделение частицами.
 * Отель 0..19 x 0..5 x 0..9. Коридор у южной стены (z = 1..2), номер 101 - x 4..7, z 4..7 (стены вокруг), дверь в стене z = 3.
 */
class RoomAutoTest extends PluginTestBase {

    private PlayerMock boss;
    private PlayerMock steve;
    private Plot hotel;
    private World world;

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    @BeforeEach
    void setUp() {
        ChunkJob.setLoader((w, cx, cz, cb) -> cb.accept(w.getChunkAt(cx, cz), null));
        ChunkAccess.setSource(new ChunkAccess.Source() {
            @Override
            public List<org.bukkit.block.BlockState> tileEntities(org.bukkit.Chunk chunk) {
                return List.of();
            }

            @Override
            public java.util.Collection<org.bukkit.entity.Entity> entities(org.bukkit.Chunk chunk) {
                return List.of();
            }
        });
        plugin.setSnapshots(new ru.industryreborn.service.SnapshotStore() {
            @Override
            public void capture(java.io.File dir, World w, int cx, int cz, Box clip) { }

            @Override
            public void saveMeta(java.io.File dir, Box box, java.util.Set<java.util.UUID> e) { }

            @Override
            public java.util.Set<java.util.UUID> originalEntities(World w, Box box) {
                return java.util.Set.of();
            }
        });
        plugin.roomAuto().setPassable(b -> b.getType().isAir());
        world = server.getWorld("world");
        boss = admin("Boss");
        steve = player("Steve", 1000);
        hotel = new Plot("h", "world", new Box(0, 0, 0, 19, 5, 9));
        hotel.category = Category.HOTEL;
        plugin.plots().add(hotel);
        plugin.stations().register(hotel);
        buildRoom(3, 3, 8, 8);      // стены x 3..8, z 3..8 -> интерьер x 4..7, z 4..7, y 1..3
    }

    @AfterEach
    void tearDown() {
        ChunkJob.setLoader(null);
        ChunkAccess.setSource(null);
        plugin.roomAuto().setTargetFinder(null);
        plugin.roomAuto().setPassable(null);
    }

    /** Коробка из камня: стены по периметру, пол y=0, потолок y=4; внутри воздух. */
    private void buildRoom(int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = 0; y <= 4; y++) {
                    boolean wall = x == x0 || x == x1 || z == z0 || z == z1 || y == 0 || y == 4;
                    world.getBlockAt(x, y, z).setType(wall ? Material.STONE : Material.AIR);
                }
            }
        }
    }

    /** Дверь в стене z = 3 на x = 5, смотрит вдоль оси z. */
    private Block door(int x, int z) {
        Block lower = world.getBlockAt(x, 1, z);
        Block upper = world.getBlockAt(x, 2, z);
        world.getBlockAt(x, 1, z).setType(Material.OAK_DOOR, false);
        world.getBlockAt(x, 2, z).setType(Material.OAK_DOOR, false);
        Door d1 = (Door) lower.getBlockData();
        d1.setFacing(BlockFace.SOUTH);
        d1.setHalf(Bisected.Half.BOTTOM);
        lower.setBlockData(d1, false);
        Door d2 = (Door) upper.getBlockData();
        d2.setFacing(BlockFace.SOUTH);
        d2.setHalf(Bisected.Half.TOP);
        upper.setBlockData(d2, false);
        return lower;
    }

    private void lookAt(Block b, double px, double pz) {
        plugin.roomAuto().setTargetFinder(p -> b);
        boss.teleport(new Location(world, px, 1, pz));
    }

    // ------------------------------------------------------------ поиск помещения

    @Test
    void lookingAtTheDoorFindsTheRoomBehindIt() {
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);                       // игрок в коридоре (z < 3)
        assertNull(plugin.roomAuto().analyze(boss, "101", 50));
        var pending = plugin.roomAuto().pending(boss);
        assertNotNull(pending);
        assertEquals(new Box(4, 1, 4, 7, 3, 7), pending.box());
        assertEquals("h", pending.hotelId());
        assertEquals("101", pending.roomId());
        assertEquals(50, pending.rate(), 1e-9);
        assertArrayEquals(new int[] {5, 1, 3}, pending.door());
        String msg = drain(boss);
        assertTrue(msg.contains("h-101") && msg.contains("Подтвердить") && msg.contains("Отменить"), msg);
        assertTrue(plugin.plots().get("h-101") == null, "пока ничего не создано");
    }

    @Test
    void lookingAtTheUpperHalfOfTheDoorWorksToo() {
        door(5, 3);
        lookAt(world.getBlockAt(5, 2, 3), 5.5, 1.5);
        assertNull(plugin.roomAuto().analyze(boss, "101", 50));
        assertArrayEquals(new int[] {5, 1, 3}, plugin.roomAuto().pending(boss).door(), "запоминается нижняя половина");
    }

    @Test
    void thePlayerSideDecidesWhichSideIsTheRoom() {
        // стоя в самом номере и глядя на дверь, "номер" - это коридор; он слишком большой/незамкнут
        Block door = door(5, 3);
        plugin.getConfig().set("hotel-auto.max-cells", 30);
        lookAt(door, 5.5, 5.5);
        String error = plugin.roomAuto().analyze(boss, "101", 50);
        assertNotNull(error, "изнутри заливка идёт в коридор и упирается в лимит");
        assertNull(plugin.roomAuto().pending(boss));
    }

    @Test
    void furnitureInsideDoesNotChangeTheArea() {
        world.getBlockAt(6, 1, 6).setType(Material.CHEST);
        world.getBlockAt(7, 1, 7).setType(Material.RED_BED);
        world.getBlockAt(4, 1, 7).setType(Material.CRAFTING_TABLE);
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);
        assertNull(plugin.roomAuto().analyze(boss, "101", 50));
        assertEquals(new Box(4, 1, 4, 7, 3, 7), plugin.roomAuto().pending(boss).box());
    }

    @Test
    void carpetOnTheFloorIsPartOfTheRoom() {
        for (int x = 4; x <= 7; x++) {
            for (int z = 4; z <= 7; z++) {
                world.getBlockAt(x, 1, z).setType(Material.RED_CARPET);
            }
        }
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);
        assertNull(plugin.roomAuto().analyze(boss, "101", 50));
        assertEquals(1, plugin.roomAuto().pending(boss).box().minY(), "ковёр на полу не поднимает пол комнаты");
    }

    @Test
    void aHoleInTheWallJoinsTheRoomWithTheHallAndIsRejectedByTheLimit() {
        world.getBlockAt(8, 2, 5).setType(Material.AIR);          // пробита восточная стена
        world.getBlockAt(8, 1, 5).setType(Material.AIR);
        plugin.getConfig().set("hotel-auto.max-cells", 40);
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);
        String error = plugin.roomAuto().analyze(boss, "101", 50);
        assertNotNull(error);
        assertTrue(error.contains("40") || error.contains("не замкнута"), error);
    }

    @Test
    void notADoorNoTargetOrOutsideTheHotelAreRefused() {
        lookAt(world.getBlockAt(5, 0, 3), 5.5, 1.5);
        assertTrue(plugin.roomAuto().analyze(boss, "101", 50).contains("дверь"));
        plugin.roomAuto().setTargetFinder(p -> null);
        assertTrue(plugin.roomAuto().analyze(boss, "101", 50).contains("Наведите"));
        Block far = door(40, 3);
        lookAt(far, 40.5, 1.5);
        assertTrue(plugin.roomAuto().analyze(boss, "101", 50).contains("внутри отеля"));
    }

    @Test
    void invalidIdsAndOverlapsAreRefused() {
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);
        assertNotNull(plugin.roomAuto().analyze(boss, "bad id!", 50));
        assertNull(plugin.roomAuto().analyze(boss, "101", 50));
        plugin.roomAuto().confirm(boss);
        server.getScheduler().performTicks(5);
        assertNotNull(plugin.plots().get("h-101"));
        String again = plugin.roomAuto().analyze(boss, "102", 50);
        assertNotNull(again, "тот же номер уже существует: область пересекается");
        assertNotNull(plugin.roomAuto().analyze(boss, "101", 50), "номер занят");
    }

    // ------------------------------------------------------------ подтверждение и отмена

    @Test
    void confirmCreatesTheRoomWithTheDoorAndRate() {
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);
        plugin.roomAuto().analyze(boss, "101", 75);
        assertNull(plugin.roomAuto().confirm(boss));
        server.getScheduler().performTicks(5);
        Plot room = plugin.plots().get("h-101");
        assertNotNull(room);
        assertEquals(Category.ROOM, room.category);
        assertEquals(new Box(4, 1, 4, 7, 3, 7), room.box);
        assertEquals(75, room.rentPerDay, 1e-9);
        assertEquals(1, room.doors.size());
        assertArrayEquals(new int[] {5, 1, 3}, room.doors.get(0));
        assertNull(plugin.roomAuto().pending(boss));
        assertSame(room, plugin.hotels().roomAtDoor(door));
    }

    @Test
    void cancelDiscardsThePendingRoom() {
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);
        plugin.roomAuto().analyze(boss, "101", 75);
        assertTrue(plugin.roomAuto().cancel(boss));
        assertNull(plugin.roomAuto().pending(boss));
        assertFalse(plugin.roomAuto().cancel(boss));
        assertNotNull(plugin.roomAuto().confirm(boss), "подтверждать нечего");
        assertNull(plugin.plots().get("h-101"));
    }

    @Test
    void aStaleProposalExpires() {
        plugin.getConfig().set("hotel-auto.confirm-seconds", 0);
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);
        plugin.roomAuto().analyze(boss, "101", 75);
        try {
            Thread.sleep(5);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        assertNull(plugin.roomAuto().pending(boss));
        assertNotNull(plugin.roomAuto().confirm(boss));
    }

    @Test
    void confirmRechecksTheAreaBecauseSomethingMayHaveChanged() {
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);
        plugin.roomAuto().analyze(boss, "101", 75);
        Plot taken = new Plot("h-other", "world", new Box(5, 1, 5, 6, 2, 6));
        taken.category = Category.ROOM;
        taken.parentId = "h";
        plugin.plots().add(taken);
        String error = plugin.roomAuto().confirm(boss);
        assertNotNull(error);
        assertTrue(error.contains("пересекается"), error);
        assertNull(plugin.roomAuto().pending(boss), "предложение сброшено");
    }

    @Test
    void theProposalsAreIndividualPerAdmin() {
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);
        plugin.roomAuto().analyze(boss, "101", 75);
        PlayerMock other = admin("Other");
        assertNull(plugin.roomAuto().pending(other));
        assertNotNull(plugin.roomAuto().confirm(other));
    }

    // ------------------------------------------------------------ команды

    @Test
    void theCommandsDriveTheWholeFlowAndAreAdminOnly() {
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);
        boss.performCommand("district room auto 101 60");
        assertNotNull(plugin.roomAuto().pending(boss));
        drain(boss);
        boss.performCommand("district room confirm");
        server.getScheduler().performTicks(5);
        assertNotNull(plugin.plots().get("h-101"));
        boss.performCommand("district room auto 102 60");
        assertTrue(drain(boss).length() > 0);
        boss.performCommand("district room cancel");
        assertNull(plugin.roomAuto().pending(boss));
        boss.performCommand("district room cancel");
        assertTrue(drain(boss).contains("Нет номера"));
        steve.performCommand("district room auto 103 60");
        assertTrue(drain(steve).contains("Недостаточно прав"));
        assertNull(plugin.roomAuto().pending(steve));
    }

    @Test
    void theCommandReportsProblemsToTheAdmin() {
        lookAt(world.getBlockAt(5, 0, 3), 5.5, 1.5);
        drain(boss);
        boss.performCommand("district room auto 101 60");
        assertTrue(drain(boss).contains("дверь"));
        boss.performCommand("district room auto");
        assertTrue(drain(boss).contains("Использование"));
    }

    // ------------------------------------------------------------ частицы

    /** Собирает все частицы, выведенные визуализатором. */
    private static final class Capture implements SelectionVisualizer.Sink {
        final List<Object[]> shown = new ArrayList<>();

        @Override
        public void spawn(org.bukkit.entity.Player p, double x, double y, double z, Color color, float size) {
            shown.add(new Object[] {p.getName(), x, y, z, color, size});
        }

        long count(Color c) {
            return shown.stream().filter(s -> c.equals(s[4])).count();
        }
    }

    private Capture capture() {
        Capture c = new Capture();
        plugin.visualizer().setSink(c);
        return c;
    }

    @Test
    void theWandSelectionIsDrawnAsAGoldOutlineWithMarkedPoints() {
        Capture c = capture();
        boss.teleport(new Location(world, 10, 1, 10));
        plugin.selection().setPoint(boss, 1, 4, 1, 4);
        plugin.selection().setPoint(boss, 2, 7, 3, 7);
        plugin.visualizer().tick();
        assertTrue(c.count(SelectionVisualizer.GOLD) > 20, "контур области");
        assertTrue(c.count(SelectionVisualizer.GREEN) > 0, "первая точка");
        assertTrue(c.count(SelectionVisualizer.RED) > 0, "вторая точка");
        assertTrue(c.count(SelectionVisualizer.WHITE) >= 8, "углы области");
        assertTrue(c.shown.stream().allMatch(s -> s[0].equals("Boss")), "частицы видит только сам игрок");
    }

    @Test
    void aSingleSelectedPointIsAlreadyVisible() {
        Capture c = capture();
        boss.teleport(new Location(world, 10, 1, 10));
        plugin.selection().setPoint(boss, 1, 4, 1, 4);
        plugin.visualizer().tick();
        assertTrue(c.count(SelectionVisualizer.GREEN) > 0);
        assertEquals(0, c.count(SelectionVisualizer.GOLD), "области ещё нет");
    }

    @Test
    void aPendingRoomIsDrawnGreenWithACyanDoor() {
        Capture c = capture();
        Block door = door(5, 3);
        lookAt(door, 5.5, 1.5);
        plugin.roomAuto().analyze(boss, "101", 75);
        plugin.visualizer().tick();
        assertTrue(c.count(SelectionVisualizer.GREEN) > 20, "контур предложенного номера");
        assertTrue(c.count(SelectionVisualizer.CYAN) > 0, "дверь отмечена");
        double minX = c.shown.stream().filter(s -> s[4].equals(SelectionVisualizer.GREEN)).mapToDouble(s -> (double) s[1]).min().orElse(0);
        double maxX = c.shown.stream().filter(s -> s[4].equals(SelectionVisualizer.GREEN)).mapToDouble(s -> (double) s[1]).max().orElse(0);
        assertEquals(4, minX, 1e-9);
        assertEquals(8, maxX, 1e-9, "граница блока: maxX + 1");
        c.shown.clear();
        plugin.roomAuto().cancel(boss);
        plugin.visualizer().tick();
        assertEquals(0, c.shown.size(), "после отмены подсветка исчезает");
    }

    @Test
    void farAwayPointsAreNotDrawnAndTheBudgetIsRespected() {
        Capture c = capture();
        boss.teleport(new Location(world, 500, 1, 500));
        plugin.selection().setPoint(boss, 1, 0, 1, 0);
        plugin.visualizer().tick();
        assertEquals(0, c.shown.size(), "дальше 64 блоков не рисуется");
        boss.teleport(new Location(world, 50, 60, 50));
        plugin.selection().setPoint(boss, 1, 0, 0, 0);
        plugin.selection().setPoint(boss, 2, 100, 120, 100);
        plugin.getConfig().set("visual.range", 1000);
        plugin.getConfig().set("visual.max-points", 200);
        c.shown.clear();
        plugin.visualizer().tick();
        assertTrue(c.shown.size() < 600, "для огромной области частиц ограниченное число: " + c.shown.size());
    }

    @Test
    void onlyAdminsSeeAndItCanBeSwitchedOff() {
        Capture c = capture();
        steve.teleport(new Location(world, 10, 1, 10));
        plugin.selection().setPoint(steve, 1, 4, 1, 4);
        plugin.visualizer().tick();
        assertEquals(0, c.shown.size(), "игроку без прав выделение не рисуется");
        boss.teleport(new Location(world, 10, 1, 10));
        plugin.selection().setPoint(boss, 1, 4, 1, 4);
        plugin.getConfig().set("visual.selection-particles", false);
        plugin.visualizer().tick();
        assertEquals(0, c.shown.size());
    }

    @Test
    void selectionInAnotherWorldIsNotDrawn() {
        Capture c = capture();
        plugin.selection().setPoint(boss, 1, 4, 1, 4);
        plugin.selection().get(boss).world = "nether";
        plugin.visualizer().tick();
        assertEquals(0, c.shown.size());
    }

    @Test
    void theShowCommandHighlightsAPlotForAWhile() {
        Capture c = capture();
        boss.teleport(new Location(world, 10, 1, 10));
        boss.performCommand("district show h");
        plugin.visualizer().tick();
        assertTrue(c.count(SelectionVisualizer.CYAN) > 20);
        drain(boss);
        boss.performCommand("district show nothing");
        assertTrue(drain(boss).contains("не найден"));
        boss.performCommand("district show");
        assertTrue(drain(boss).contains("Использование"));
    }
}
