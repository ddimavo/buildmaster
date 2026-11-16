package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Ledger;
import ru.buildmaster.model.Plot;
import ru.buildmaster.support.PluginTestBase;

/** Защита участков, учёт блоков и антидюп. */
class ProtectionTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock alex;
    private Plot plot;

    @BeforeEach
    void setUpPlot() {
        steve = player("Steve", 1000);
        alex = player("Alex", 1000);
        plot = addPlot("a", Category.SHOP, 0, -1, 10);
        plugin.service().rent(steve, plot, 3);
    }

    private Block block(int x, int y, int z) {
        return server.getWorld("world").getBlockAt(x, y, z);
    }

    private BlockBreakEvent breakBlock(PlayerMock who, Block b) {
        BlockBreakEvent e = new BlockBreakEvent(b, who);
        server.getPluginManager().callEvent(e);
        return e;
    }

    private BlockPlaceEvent placeBlock(PlayerMock who, Block b, Material m) {
        BlockPlaceEvent e = new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0), new ItemStack(m), who, true,
                EquipmentSlot.HAND);
        b.setType(m);
        server.getPluginManager().callEvent(e);
        return e;
    }

    @Test
    void strangerCannotBreakOrPlace() {
        Block b = block(2, 2, 2);
        b.setType(Material.STONE);
        assertTrue(breakBlock(alex, b).isCancelled());
        assertTrue(placeBlock(alex, block(3, 3, 3), Material.STONE).isCancelled());
    }

    @Test
    void managerCanBuildAndBreak() {
        Block b = block(2, 2, 2);
        b.setType(Material.STONE);
        assertFalse(placeBlock(steve, block(3, 3, 3), Material.OAK_PLANKS).isCancelled());
        assertFalse(breakBlock(steve, b).isCancelled());
    }

    @Test
    void trustedHelperCanBuild() {
        plot.grantAll(alex.getUniqueId());
        assertFalse(placeBlock(alex, block(3, 3, 3), Material.OAK_PLANKS).isCancelled());
    }

    @Test
    void freePlotIsClosedToEveryoneButAdmins() {
        Plot free = addPlot("free", Category.SHOP, 20, 100, -1);
        assertTrue(placeBlock(steve, block(21, 3, 3), Material.STONE).isCancelled());
        PlayerMock boss = admin("Boss");
        assertFalse(placeBlock(boss, block(22, 3, 3), Material.STONE).isCancelled());
        assertTrue(free.isFree());
    }

    @Test
    void outsidePlotsNothingIsProtectedByThisPlugin() {
        assertFalse(placeBlock(alex, block(100, 3, 3), Material.STONE).isCancelled());
    }

    @Test
    void frozenPlotCannotBeChanged() {
        plot.graceUntil = System.currentTimeMillis() + 100_000;
        Block b = block(2, 2, 2);
        b.setType(Material.STONE);
        assertTrue(breakBlock(steve, b).isCancelled());
        assertTrue(placeBlock(steve, block(3, 3, 3), Material.STONE).isCancelled());
    }

    @Test
    void frozenPlotStillAllowsTradingWithSigns() {
        plot.graceUntil = System.currentTimeMillis() + 100_000;
        Block sign = block(4, 4, 4);
        sign.setType(Material.OAK_WALL_SIGN);
        PlayerInteractEvent e = new PlayerInteractEvent(alex, Action.RIGHT_CLICK_BLOCK, null, sign,
                org.bukkit.block.BlockFace.NORTH, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        assertNotEquals(org.bukkit.event.Event.Result.DENY, e.useInteractedBlock(), "табличку магазина трогать можно");
    }

    @Test
    void chestsAreProtectedFromStrangers() {
        Block chest = block(4, 4, 4);
        chest.setType(Material.CHEST);
        PlayerInteractEvent e = new PlayerInteractEvent(alex, Action.RIGHT_CLICK_BLOCK, null, chest,
                org.bukkit.block.BlockFace.NORTH, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        assertTrue(e.isCancelled());
    }

    @Test
    void doorsAreOpenToVisitors() {
        Block door = block(4, 4, 4);
        door.setType(Material.OAK_DOOR);
        PlayerInteractEvent e = new PlayerInteractEvent(alex, Action.RIGHT_CLICK_BLOCK, null, door,
                org.bukkit.block.BlockFace.NORTH, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        assertFalse(e.isCancelled());
    }

    @Test
    void bannedBlocksCannotBePlacedEvenByTheManager() {
        assertTrue(placeBlock(steve, block(3, 3, 3), Material.TNT).isCancelled());
        assertEquals(0, plugin.ledgers().get("a").size(), "запрещённое в книгу не попадает");
    }

    @Test
    void lavaBucketIsBanned() {
        PlayerBucketEmptyEvent e = new PlayerBucketEmptyEvent(steve, block(3, 3, 3), block(3, 2, 3),
                org.bukkit.block.BlockFace.UP, Material.LAVA_BUCKET, new ItemStack(Material.LAVA_BUCKET),
                EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        assertTrue(e.isCancelled());
    }

    // ---------------------------------------------------------------- книга учёта и антидюп

    @Test
    void placedBlocksAreRecordedPerPlayer() {
        plot.grantAll(alex.getUniqueId());
        placeBlock(steve, block(1, 1, 1), Material.FURNACE);
        placeBlock(alex, block(2, 1, 1), Material.CHEST);

        Ledger ledger = plugin.ledgers().get("a");
        assertEquals(new Ledger.Entry(steve.getUniqueId(), "FURNACE", 1), ledger.get(1, 1, 1));
        assertEquals(new Ledger.Entry(alex.getUniqueId(), "CHEST", 1), ledger.get(2, 1, 1));
    }

    @Test
    void secondSlabOnSameBlockIncreasesAmount() {
        Block b = block(1, 1, 1);
        b.setType(Material.OAK_SLAB);
        BlockPlaceEvent e = new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0), new ItemStack(Material.OAK_SLAB),
                steve, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        server.getPluginManager().callEvent(e);
        assertEquals(2, plugin.ledgers().get("a").get(1, 1, 1).amount());
    }

    @Test
    void breakingOwnBlockRemovesItFromLedgerAndKeepsDrops() {
        Block b = block(1, 1, 1);
        placeBlock(steve, b, Material.FURNACE);
        BlockBreakEvent e = breakBlock(steve, b);
        assertFalse(plugin.ledgers().get("a").contains(1, 1, 1));
        assertTrue(e.isDropItems(), "свои блоки ломаются с дропом");
    }

    @Test
    void originalBuildingBlocksDropNothing() {
        // блока нет в книге учёта - значит, он часть исходного здания и при сбросе вернётся
        Block lamp = block(5, 5, 5);
        lamp.setType(Material.SEA_LANTERN);
        BlockBreakEvent e = breakBlock(steve, lamp);
        assertFalse(e.isCancelled());
        assertFalse(e.isDropItems(), "иначе дюп: лампа в инвентаре и лампа после сброса");
        assertEquals(0, e.getExpToDrop());
    }

    @Test
    void originalBlocksDropNormallyWhenAntiDupeIsDisabled() {
        plugin.getConfig().set("original-blocks-drop-nothing", false);
        Block lamp = block(5, 5, 5);
        lamp.setType(Material.SEA_LANTERN);
        assertTrue(breakBlock(steve, lamp).isDropItems());
    }

    @Test
    void helperBlocksAreNotConfusedWithOriginals() {
        // блок помощника в книге - ломается с дропом, а не "без дропа"
        plot.grantAll(alex.getUniqueId());
        Block b = block(6, 6, 6);
        placeBlock(alex, b, Material.OAK_PLANKS);
        assertTrue(breakBlock(alex, b).isDropItems());
    }
}
