package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.PlotService;
import ru.industryreborn.service.SnapshotStore;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.util.ChunkAccess;
import ru.industryreborn.util.ChunkJob;

/** Здание остаётся на участке после отказа, окончания аренды, выселения и изъятия и становится его исходным видом. */
class KeepBuildingTest extends PluginTestBase {

    private static final class RecordingSnapshots extends SnapshotStore {
        final List<String> placed = new ArrayList<>();
        final List<String> captured = new ArrayList<>();
        int metas;

        @Override
        public boolean exists(File dir) {
            return true;
        }

        @Override
        public Set<UUID> loadOriginalEntities(File dir) {
            return Set.of();
        }

        @Override
        public boolean place(File dir, World world, int cx, int cz, Box clip) {
            placed.add(cx + "," + cz);
            return true;
        }

        @Override
        public void capture(File dir, World world, int cx, int cz, Box clip) {
            captured.add(dir.getName() + ":" + cx + "," + cz);
        }

        @Override
        public void saveMeta(File dir, Box box, Set<UUID> originalEntities) {
            metas++;
        }

        @Override
        public Set<UUID> originalEntities(World world, Box box) {
            return Set.of();
        }
    }

    private RecordingSnapshots snapshots;
    private PlayerMock steve;
    private PlayerMock boss;
    private Plot plot;
    private World world;

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
        snapshots = new RecordingSnapshots();
        plugin.setSnapshots(snapshots);
        plugin.getConfig().set("limits.total", 0);
        steve = player("Steve", 100_000);
        boss = admin("Boss");
        world = server.getWorld("world");
        plot = addPlot("p1", Category.SHOP, 0, 1000, -1);
        plugin.service().buy(steve, plot);
        assertTrue(plot.isOwner(steve.getUniqueId()));
        world.getBlockAt(3, 3, 3).setType(Material.STONE_BRICKS);      // «здание» на участке
    }

    @AfterEach
    void tearDown() {
        ChunkJob.setLoader(null);
        ChunkAccess.setSource(null);
    }

    private void ticks() {
        server.getScheduler().performTicks(10);
    }

    private Block block() {
        return world.getBlockAt(3, 3, 3);
    }

    @Test
    void voluntaryReleaseWithoutResetKeepsTheBuildingAndMakesItTheNewBaseline() {
        plot.resetOnRelease = false;
        plugin.service().release(steve, plot);
        ticks();
        assertTrue(plot.isFree());
        assertEquals(Material.STONE_BRICKS, block().getType(), "здание осталось");
        assertTrue(snapshots.placed.isEmpty(), "исходный вид не вставлялся");
        assertFalse(snapshots.captured.isEmpty(), "здание снято как новый исходный вид");
        assertTrue(snapshots.metas > 0);
        assertTrue(plot.hasBuilding);
        assertFalse(plot.restoreKeep);
        assertFalse(plot.restorePending);
        assertFalse(plot.busy);
    }

    @Test
    void releaseWithResetReturnsTheOriginalView() {
        plot.resetOnRelease = true;
        plugin.service().release(steve, plot);
        ticks();
        assertFalse(snapshots.placed.isEmpty());
        assertTrue(snapshots.captured.isEmpty());
        assertFalse(plot.hasBuilding);
    }

    @Test
    void rebaselineCanBeSwitchedOff() {
        plugin.getConfig().set("keep-building.rebaseline", false);
        plot.resetOnRelease = false;
        plugin.service().release(steve, plot);
        ticks();
        assertTrue(snapshots.captured.isEmpty());
        assertTrue(plot.hasBuilding, "здание всё равно отмечено как оставленное");
        assertEquals(Material.STONE_BRICKS, block().getType());
    }

    @Test
    void rentExpiryKeepsTheBuildingToo() {
        Plot rented = addPlot("p2", Category.SHOP, 20, -1, 10);
        plugin.service().rent(steve, rented, 1);
        rented.resetOnRelease = false;
        world.getBlockAt(23, 3, 3).setType(Material.BRICKS);
        rented.rentedUntil = System.currentTimeMillis() - 1000;
        plugin.service().tick();
        ticks();
        assertTrue(rented.isFree());
        assertEquals(Material.BRICKS, world.getBlockAt(23, 3, 3).getType());
        assertTrue(rented.hasBuilding);
    }

    @Test
    void adminEvictAndSeizeWithKeepOrReset() {
        boss.performCommand("district evict p1 keep нарушение");
        ticks();
        assertTrue(plot.isFree());
        assertEquals(Material.STONE_BRICKS, block().getType());
        assertTrue(plot.hasBuilding);
        assertTrue(snapshots.placed.isEmpty());
        // изъятие с оставлением здания: вещи арестованы, здание осталось
        plugin.service().buy(steve, plot);
        plot.hasBuilding = false;
        snapshots.captured.clear();
        boss.performCommand("district seize p1 keep нарушение правил");
        ticks();
        assertTrue(plot.isFree());
        assertTrue(plot.hasBuilding);
        assertFalse(snapshots.captured.isEmpty());
        // reset перекрывает настройку по умолчанию
        plugin.getConfig().set("keep-building.on-evict", true);
        plugin.service().buy(steve, plot);
        plot.hasBuilding = false;
        boss.performCommand("district evict p1 reset причина");
        ticks();
        assertFalse(snapshots.placed.isEmpty());
        assertFalse(plot.hasBuilding);
    }

    @Test
    void defaultsDecideWhenNothingIsSaid() {
        boss.performCommand("district evict p1 просто так");
        ticks();
        assertFalse(plot.hasBuilding, "по умолчанию администратор здание сбрасывает");
        assertFalse(snapshots.placed.isEmpty());
        snapshots.placed.clear();
        plugin.getConfig().set("keep-building.on-evict", true);
        plugin.service().buy(steve, plot);
        boss.performCommand("district evict p1 просто так");
        ticks();
        assertTrue(plot.hasBuilding, "настройка keep-building.on-evict");
        assertTrue(snapshots.placed.isEmpty());
    }

    @Test
    void evictWithKeepNeedsAReason() {
        boss.performCommand("district evict p1 keep");
        assertTrue(plot.isOwner(steve.getUniqueId()), "причина обязательна");
    }

    @Test
    void playerPlotsAndRoomsNeverRebaseline() {
        Plot reg = addPlot("reg", Category.SHOP, 40, -1, 10);
        reg.kind = Plot.Kind.PLAYER;
        plugin.service().rent(steve, reg, 1);
        plugin.service().evict(reg, PlotService.Release.keeping("Boss", "тест", Journal.Action.EVICTED, false));
        ticks();
        assertFalse(reg.hasBuilding);
        assertFalse(reg.restoreKeep);
    }

    @Test
    void keepFlagSurvivesARestart() {
        plot.restoreKeep = true;
        plot.hasBuilding = true;
        plugin.plots().save();
        plugin.plots().load();
        Plot loaded = plugin.plots().get("p1");
        assertTrue(loaded.restoreKeep);
        assertTrue(loaded.hasBuilding);
    }

    @Test
    void adminMenuHasKeepButtons() {
        new ru.industryreborn.gui.PlotMenu(plugin, boss, plot, null).open();
        var inv = boss.getOpenInventory().getTopInventory();
        assertEquals(Material.OAK_DOOR, inv.getItem(40).getType(), "выселить, здание оставить");
        assertEquals(Material.TRIPWIRE_HOOK, inv.getItem(42).getType(), "изъять, здание оставить");
    }
}
