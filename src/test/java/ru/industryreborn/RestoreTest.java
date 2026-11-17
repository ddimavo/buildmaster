package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.PlotService;
import ru.industryreborn.service.SnapshotStore;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.util.ChunkAccess;
import ru.industryreborn.util.ChunkJob;

/**
 * Возврат вещей при сбросе здания: каждому - его вещи. StructureManager в MockBukkit недоступен,
 * поэтому вставка структур подменяется заглушкой и проверяется только то, что она вызвана.
 */
class RestoreTest extends PluginTestBase {

    /** Хранилище снимков без настоящих структур: запоминает, какие куски "восстановлены". */
    private static final class RecordingSnapshots extends SnapshotStore {
        final List<String> placed = new ArrayList<>();

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
    }

    private RecordingSnapshots snapshots;
    private PlayerMock steve;
    private PlayerMock alex;
    private Plot plot;

    @BeforeEach
    void setUpPlot() {
        // MockBukkit не умеет getChunkAtAsync - грузим синхронно
        ChunkJob.setLoader((world, cx, cz, cb) -> cb.accept(world.getChunkAt(cx, cz), null));
        // MockBukkit не умеет Chunk.getTileEntities/getEntities - ищем блоки с контейнерами сканированием
        ChunkAccess.setSource(new ChunkAccess.Source() {
            @Override
            public List<org.bukkit.block.BlockState> tileEntities(org.bukkit.Chunk chunk) {
                List<org.bukkit.block.BlockState> result = new ArrayList<>();
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 12; y++) {
                        for (int z = 0; z < 16; z++) {
                            Block b = chunk.getBlock(x, y, z);
                            if (b.getType() == Material.CHEST || b.getType() == Material.BARREL) {
                                result.add(b.getState());
                            }
                        }
                    }
                }
                return result;
            }

            @Override
            public java.util.Collection<org.bukkit.entity.Entity> entities(org.bukkit.Chunk chunk) {
                return List.of();
            }
        });
        snapshots = new RecordingSnapshots();
        plugin.setSnapshots(snapshots);
        steve = player("Steve", 10_000);
        alex = player("Alex", 10_000);
        plot = addPlot("a", Category.SHOP, 0, -1, 10);
        plugin.service().rent(steve, plot, 5);
        plot.grantAll(alex.getUniqueId());
    }

    @AfterEach
    void restoreLoader() {
        ChunkJob.setLoader(null);
        ChunkAccess.setSource(null);
    }

    /** Задача по чанкам завершается в следующем тике сервера. */
    private void finishJobs() {
        server.getScheduler().performTicks(10);
    }

    private Block chestWith(int x, int y, int z, ItemStack... items) {
        Block b = server.getWorld("world").getBlockAt(x, y, z);
        b.setType(Material.CHEST);
        ((Chest) b.getState()).getBlockInventory().addItem(items);
        return b;
    }

    private static int count(List<StorageEntry> entries, Material m) {
        int n = 0;
        for (StorageEntry e : entries) {
            for (byte[] b : e.stacks) {
                ItemStack s = ItemStack.deserializeBytes(b);
                if (s.getType() == m) {
                    n += s.getAmount();
                }
            }
        }
        return n;
    }

    @Test
    void eachPlayerGetsBackTheirOwnPlacedBlocks() {
        server.getWorld("world").getBlockAt(1, 1, 1).setType(Material.FURNACE);
        server.getWorld("world").getBlockAt(2, 1, 1).setType(Material.ANVIL);
        plugin.ledgers().get("a").place(1, 1, 1, steve.getUniqueId(), "FURNACE", false);
        plugin.ledgers().get("a").place(2, 1, 1, alex.getUniqueId(), "ANVIL", false);

        plugin.service().evict(plot, new PlotService.Release("Steve", "отказ", Journal.Action.RELEASED, false,
                Plot.RestoreMode.FULL));
        finishJobs();

        List<StorageEntry> st = plugin.storage().entries(steve.getUniqueId());
        List<StorageEntry> al = plugin.storage().entries(alex.getUniqueId());
        assertEquals(1, count(st, Material.FURNACE));
        assertEquals(0, count(st, Material.ANVIL), "печь - мне, а не наковальня друга");
        assertEquals(1, count(al, Material.ANVIL));
        assertEquals(0, count(al, Material.FURNACE));
        assertFalse(plot.restorePending);
        assertFalse(plot.busy);
        assertEquals(0, plugin.ledgers().get("a").size(), "книга очищена");
        assertFalse(snapshots.placed.isEmpty(), "здание восстановлено из снимка");
    }

    @Test
    void chestContentsGoToWhoeverPlacedTheChest() {
        Block steveChest = chestWith(1, 1, 1, new ItemStack(Material.DIAMOND, 5));
        Block alexChest = chestWith(2, 1, 1, new ItemStack(Material.EMERALD, 7));
        plugin.ledgers().get("a").place(1, 1, 1, steve.getUniqueId(), "CHEST", false);
        plugin.ledgers().get("a").place(2, 1, 1, alex.getUniqueId(), "CHEST", false);
        // друг положил свои вещи в сундук Стива - вернутся Стиву (сундук его)
        ((Chest) steveChest.getState()).getBlockInventory().addItem(new ItemStack(Material.GOLD_INGOT, 3));

        plugin.service().evict(plot, new PlotService.Release("Steve", "отказ", Journal.Action.RELEASED, false,
                Plot.RestoreMode.FULL));
        finishJobs();

        List<StorageEntry> st = plugin.storage().entries(steve.getUniqueId());
        List<StorageEntry> al = plugin.storage().entries(alex.getUniqueId());
        assertEquals(5, count(st, Material.DIAMOND));
        assertEquals(3, count(st, Material.GOLD_INGOT));
        assertEquals(1, count(st, Material.CHEST));
        assertEquals(7, count(al, Material.EMERALD));
        assertEquals(1, count(al, Material.CHEST));
        assertEquals(0, count(al, Material.DIAMOND));
        assertTrue(((Chest) alexChest.getState()).getBlockInventory().isEmpty(), "сундук очищен");
    }

    @Test
    void originalContainerContentsGoToTheTenant() {
        chestWith(3, 1, 1, new ItemStack(Material.BREAD, 12)); // не в книге: часть здания

        plugin.service().evict(plot, new PlotService.Release("Steve", "отказ", Journal.Action.RELEASED, false,
                Plot.RestoreMode.FULL));
        finishJobs();

        List<StorageEntry> st = plugin.storage().entries(steve.getUniqueId());
        assertEquals(12, count(st, Material.BREAD));
        assertEquals(0, count(st, Material.CHEST), "исходный сундук - не предмет, он остаётся зданием");
    }

    @Test
    void slabsPlacedTwiceAreReturnedTwice() {
        server.getWorld("world").getBlockAt(1, 1, 1).setType(Material.OAK_SLAB);
        plugin.ledgers().get("a").place(1, 1, 1, steve.getUniqueId(), "OAK_SLAB", false);
        plugin.ledgers().get("a").place(1, 1, 1, steve.getUniqueId(), "OAK_SLAB", true);

        plugin.service().evict(plot, new PlotService.Release("Steve", "отказ", Journal.Action.RELEASED, false,
                Plot.RestoreMode.FULL));
        finishJobs();

        assertEquals(2, count(plugin.storage().entries(steve.getUniqueId()), Material.OAK_SLAB));
    }

    @Test
    void blockAlreadyGoneIsNotReturned() {
        // запись есть, а блока уже нет (сломан без события): предмета быть не должно, иначе дюп
        server.getWorld("world").getBlockAt(1, 1, 1).setType(Material.AIR);
        plugin.ledgers().get("a").place(1, 1, 1, steve.getUniqueId(), "FURNACE", false);

        plugin.service().evict(plot, new PlotService.Release("Steve", "отказ", Journal.Action.RELEASED, false,
                Plot.RestoreMode.FULL));
        finishJobs();

        assertEquals(0, count(plugin.storage().entries(steve.getUniqueId()), Material.FURNACE));
    }

    @Test
    void itemsOnlyModeKeepsTheBuildingButStillReturnsContents() {
        Block b = chestWith(1, 1, 1, new ItemStack(Material.DIAMOND, 2));
        plugin.ledgers().get("a").place(1, 1, 1, steve.getUniqueId(), "CHEST", false);

        plugin.service().evict(plot, new PlotService.Release("Steve", "отказ", Journal.Action.RELEASED, false,
                Plot.RestoreMode.ITEMS_ONLY));
        finishJobs();

        List<StorageEntry> st = plugin.storage().entries(steve.getUniqueId());
        assertEquals(2, count(st, Material.DIAMOND));
        assertEquals(0, count(st, Material.CHEST), "сам сундук остаётся в здании");
        assertEquals(Material.CHEST, b.getType());
        assertTrue(snapshots.placed.isEmpty(), "здание не сбрасывалось");
        assertEquals(0, plugin.ledgers().get("a").size());
    }

    @Test
    void seizureArrestsOnlyTheOffendersItems() {
        server.getWorld("world").getBlockAt(1, 1, 1).setType(Material.FURNACE);
        server.getWorld("world").getBlockAt(2, 1, 1).setType(Material.ANVIL);
        plugin.ledgers().get("a").place(1, 1, 1, steve.getUniqueId(), "FURNACE", false);
        plugin.ledgers().get("a").place(2, 1, 1, alex.getUniqueId(), "ANVIL", false);

        plugin.service().evict(plot, new PlotService.Release("Boss", "нарушение", Journal.Action.SEIZED, true,
                Plot.RestoreMode.FULL));
        finishJobs();

        StorageEntry stEntry = plugin.storage().entries(steve.getUniqueId()).get(0);
        StorageEntry alEntry = plugin.storage().entries(alex.getUniqueId()).get(0);
        assertEquals(StorageEntry.State.ARRESTED, stEntry.state);
        assertEquals("нарушение", stEntry.reason);
        assertEquals(StorageEntry.State.AVAILABLE, alEntry.state, "помощник не виноват");
        assertEquals(0, plugin.storage().countItems(steve.getUniqueId(), StorageEntry.State.AVAILABLE));
        assertEquals(1, plugin.storage().countItems(steve.getUniqueId(), StorageEntry.State.ARRESTED));
    }

    @Test
    void arrestedItemsCannotBeTakenUntilReleased() {
        server.getWorld("world").getBlockAt(1, 1, 1).setType(Material.FURNACE);
        plugin.ledgers().get("a").place(1, 1, 1, steve.getUniqueId(), "FURNACE", false);
        plugin.service().evict(plot, new PlotService.Release("Boss", "нарушение", Journal.Action.SEIZED, true,
                Plot.RestoreMode.FULL));
        finishJobs();
        StorageEntry e = plugin.storage().entries(steve.getUniqueId()).get(0);

        assertNull(plugin.storage().take(steve.getUniqueId(), e.id, e.stacks.get(0)), "арестованное не выдаётся");

        plugin.storage().decideArrested(steve.getUniqueId(), StorageEntry.State.AVAILABLE);
        assertNotNull(plugin.storage().take(steve.getUniqueId(), e.id, e.stacks.get(0)));
    }

    @Test
    void adminCanDeleteArrestedItems() {
        server.getWorld("world").getBlockAt(1, 1, 1).setType(Material.FURNACE);
        plugin.ledgers().get("a").place(1, 1, 1, steve.getUniqueId(), "FURNACE", false);
        plugin.service().evict(plot, new PlotService.Release("Boss", "нарушение", Journal.Action.SEIZED, true,
                Plot.RestoreMode.FULL));
        finishJobs();

        int n = plugin.storage().decideArrested(steve.getUniqueId(), null);

        assertEquals(1, n);
        assertEquals(0, plugin.storage().countItems(steve.getUniqueId(), StorageEntry.State.ARRESTED));
    }

    @Test
    void adminResetKeepsOwnerAndSendsItemsToTheirStorage() {
        server.getWorld("world").getBlockAt(1, 1, 1).setType(Material.FURNACE);
        plugin.ledgers().get("a").place(1, 1, 1, steve.getUniqueId(), "FURNACE", false);

        plugin.service().reset(plot, "Boss", "восстановление фасада");
        finishJobs();

        assertTrue(plot.isOwner(steve.getUniqueId()), "владелец прежний");
        assertEquals(Plot.Status.RENTED, plot.status);
        assertEquals(1, count(plugin.storage().entries(steve.getUniqueId()), Material.FURNACE));
        Journal.Entry e = plugin.journal().forPlayer(steve.getUniqueId()).get(0);
        assertEquals(Journal.Action.RESET, e.action());
        assertEquals("восстановление фасада", e.reason());
    }

    @Test
    void storageSurvivesRestart() {
        server.getWorld("world").getBlockAt(1, 1, 1).setType(Material.FURNACE);
        plugin.ledgers().get("a").place(1, 1, 1, steve.getUniqueId(), "FURNACE", false);
        plugin.service().evict(plot, new PlotService.Release("Steve", "отказ", Journal.Action.RELEASED, false,
                Plot.RestoreMode.FULL));
        finishJobs();

        var fresh = new ru.industryreborn.service.StorageService(plugin);

        assertEquals(1, fresh.countItems(steve.getUniqueId(), StorageEntry.State.AVAILABLE));
    }

    @Test
    void expiredStorageEntriesAreSweptButArrestedAreKept() {
        Map<UUID, Integer> dummy = new HashMap<>();
        dummy.put(steve.getUniqueId(), 1);
        plugin.storage().add(steve.getUniqueId(), "a", List.of(new ItemStack(Material.STONE, 4)),
                StorageEntry.State.AVAILABLE, null, "old");
        plugin.storage().add(steve.getUniqueId(), "a", List.of(new ItemStack(Material.DIRT, 4)),
                StorageEntry.State.ARRESTED, "r", "arrested");
        long future = System.currentTimeMillis() + 61L * 86_400_000L;

        int removed = plugin.storage().sweepExpired(future, 60);

        assertEquals(1, removed);
        assertEquals(0, plugin.storage().countItems(steve.getUniqueId(), StorageEntry.State.AVAILABLE));
        assertEquals(4, plugin.storage().countItems(steve.getUniqueId(), StorageEntry.State.ARRESTED));
    }

    @Test
    void failedChunkLoadDoesNotLeaveThePlotStuckBusy() {
        ChunkJob.setLoader((world, cx, cz, cb) -> {
            throw new IllegalStateException("чанк недоступен");
        });

        plugin.service().evict(plot, new PlotService.Release("Steve", "отказ", Journal.Action.RELEASED, false,
                Plot.RestoreMode.FULL));
        finishJobs();

        assertFalse(plot.busy, "иначе участок завис бы навсегда");
        assertTrue(plot.restorePending, "сброс будет повторён");
        assertEquals(1, plot.failures);
    }
}
