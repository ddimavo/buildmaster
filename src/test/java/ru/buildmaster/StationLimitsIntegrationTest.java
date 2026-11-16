package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Station;
import ru.buildmaster.support.PluginTestBase;

/** Лимиты станков по типам зданий. */
class StationLimitsIntegrationTest extends PluginTestBase {

    private PlayerMock steve;

    @BeforeEach
    void setUp() {
        steve = player("Steve", 1_000_000);
    }

    private Plot rented(String id, Category c, int x) {
        Plot p = addPlot(id, c, x, -1, 10);
        plugin.service().rent(steve, p, 5);
        return p;
    }

    private boolean place(Plot plot, int dx, int y, Material m) {
        Block b = server.getWorld("world").getBlockAt(plot.box.minX() + dx, y, 1);
        b.setType(m);
        BlockPlaceEvent e = new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0), new ItemStack(m), steve, true,
                EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        if (e.isCancelled()) {
            b.setType(Material.AIR);
        }
        return !e.isCancelled();
    }

    private String lastWarning() {
        var list = plugin.warnings().forPlayer(steve.getUniqueId());
        return list.isEmpty() ? "" : list.get(0).text;
    }

    @Test
    void shopAllowsOneCraftingTableOneFurnaceOneAnvil() {
        Plot shop = rented("s", Category.SHOP, 0);
        assertTrue(place(shop, 1, 1, Material.CRAFTING_TABLE));
        assertTrue(place(shop, 2, 1, Material.FURNACE));
        assertTrue(place(shop, 3, 1, Material.ANVIL));
    }

    @Test
    void shopRefusesSecondOfEach() {
        Plot shop = rented("s", Category.SHOP, 0);
        place(shop, 1, 1, Material.CRAFTING_TABLE);
        place(shop, 2, 1, Material.FURNACE);
        place(shop, 3, 1, Material.ANVIL);

        assertFalse(place(shop, 4, 1, Material.CRAFTING_TABLE));
        assertFalse(place(shop, 5, 1, Material.FURNACE));
        assertFalse(place(shop, 6, 1, Material.ANVIL));
    }

    @Test
    void anyKindOfFurnaceCountsAsTheSameOne() {
        Plot shop = rented("s", Category.SHOP, 0);
        assertTrue(place(shop, 1, 1, Material.SMOKER));
        assertFalse(place(shop, 2, 1, Material.FURNACE));
        assertFalse(place(shop, 3, 1, Material.BLAST_FURNACE));
    }

    @Test
    void shopRefusesOtherStationsWithAHintToBuyAWorkshop() {
        Plot shop = rented("s", Category.SHOP, 0);
        assertFalse(place(shop, 1, 1, Material.ENCHANTING_TABLE));
        assertFalse(place(shop, 2, 1, Material.BREWING_STAND));
        assertFalse(place(shop, 3, 1, Material.GRINDSTONE));

        String warning = lastWarning();
        assertTrue(warning.contains("Магазин"), warning);
        assertTrue(warning.contains("мастерская"), warning);
        assertTrue(warning.contains("/district"), "есть ссылка на список мастерских");
    }

    @Test
    void refusalIsRecordedInTheWarningLog() {
        Plot shop = rented("s", Category.SHOP, 0);
        place(shop, 1, 1, Material.ENCHANTING_TABLE);
        assertEquals(1, plugin.warnings().unread(steve.getUniqueId()));
    }

    @Test
    void breakingAStationFreesTheSlot() {
        Plot shop = rented("s", Category.SHOP, 0);
        place(shop, 1, 1, Material.ANVIL);
        assertFalse(place(shop, 2, 1, Material.ANVIL));

        Block first = server.getWorld("world").getBlockAt(shop.box.minX() + 1, 1, 1);
        server.getPluginManager().callEvent(new BlockBreakEvent(first, steve));

        assertEquals(0, plugin.stations().count(shop, Station.ANVIL));
        assertTrue(place(shop, 2, 1, Material.ANVIL));
    }

    @Test
    void houseHasNoStationRestrictions() {
        Plot house = rented("h", Category.HOUSE, 0);
        for (int i = 1; i <= 4; i++) {
            assertTrue(place(house, i, 1, Material.FURNACE));
        }
        assertTrue(place(house, 5, 1, Material.ENCHANTING_TABLE));
        assertTrue(place(house, 6, 1, Material.ANVIL));
    }

    @Test
    void workshopHasNoStationRestrictions() {
        Plot w = rented("w", Category.WORKSHOP, 0);
        for (int i = 1; i <= 3; i++) {
            assertTrue(place(w, i, 1, Material.ANVIL));
        }
        assertTrue(place(w, 4, 1, Material.ENCHANTING_TABLE));
    }

    @Test
    void restaurantAllowsThreeFurnacesButNoAnvil() {
        Plot r = rented("r", Category.RESTAURANT, 0);
        assertTrue(place(r, 1, 1, Material.SMOKER));
        assertTrue(place(r, 2, 1, Material.SMOKER));
        assertTrue(place(r, 3, 1, Material.FURNACE));
        assertFalse(place(r, 4, 1, Material.FURNACE));
        assertFalse(place(r, 5, 1, Material.ANVIL));
        assertTrue(place(r, 6, 1, Material.CRAFTING_TABLE));
    }

    @Test
    void warehouseAllowsOnlyACraftingTableAndChests() {
        Plot w = rented("wh", Category.WAREHOUSE, 0);
        assertTrue(place(w, 1, 1, Material.CHEST));
        assertTrue(place(w, 2, 1, Material.BARREL));
        assertTrue(place(w, 3, 1, Material.CRAFTING_TABLE));
        assertFalse(place(w, 4, 1, Material.CRAFTING_TABLE));
        assertFalse(place(w, 5, 1, Material.FURNACE));
        assertFalse(place(w, 6, 1, Material.ANVIL));
    }

    @Test
    void limitsAreConfigurable() {
        plugin.getConfig().set("station-limits.shop.furnace", 3);
        Plot shop = rented("s", Category.SHOP, 0);
        assertTrue(place(shop, 1, 1, Material.FURNACE));
        assertTrue(place(shop, 2, 1, Material.FURNACE));
        assertTrue(place(shop, 3, 1, Material.FURNACE));
        assertFalse(place(shop, 4, 1, Material.FURNACE));
    }

    @Test
    void adminBypassIgnoresLimits() {
        Plot shop = rented("s", Category.SHOP, 0);
        PlayerMock boss = admin("Boss");
        Block b = server.getWorld("world").getBlockAt(5, 1, 1);
        b.setType(Material.ENCHANTING_TABLE);
        BlockPlaceEvent e = new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0),
                new ItemStack(Material.ENCHANTING_TABLE), boss, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        assertFalse(e.isCancelled());
    }

    @Test
    void unknownCountsRefuseLimitedPlacementButNotUnlimited() {
        Plot shop = rented("s", Category.SHOP, 0);
        Plot workshop = rented("w", Category.WORKSHOP, 20);
        plugin.stations().forget(shop);
        plugin.stations().forget(workshop);

        assertFalse(place(shop, 1, 1, Material.ANVIL), "счёт станков ещё не готов - ограниченным типам нельзя");
        assertTrue(lastWarning().contains("подсчёт"));
        assertTrue(place(workshop, 1, 1, Material.ANVIL));
    }

    @Test
    void replaceMenuCannotBeUsedToSneakStationsIn() {
        Plot shop = rented("s", Category.SHOP, 0);
        plugin.build().replace(steve, shop, shop.box, Material.STONE, Material.FURNACE);
        plugin.build().replace(steve, shop, shop.box, Material.ANVIL, Material.STONE);
        assertFalse(shop.busy, "замена даже не началась");
        assertEquals(0, plugin.stations().count(shop, Station.FURNACE));
    }

    @Test
    void stationsAreCountedByRescanOfRealBlocks() {
        Plot shop = rented("s", Category.SHOP, 0);
        server.getWorld("world").getBlockAt(2, 1, 1).setType(Material.ANVIL);
        server.getWorld("world").getBlockAt(3, 1, 1).setType(Material.FURNACE);
        server.getWorld("world").getBlockAt(4, 1, 1).setType(Material.SMOKER);
        server.getWorld("world").getBlockAt(5, 1, 1).setType(Material.STONE);
        ru.buildmaster.util.ChunkJob.setLoader((w, cx, cz, cb) -> cb.accept(w.getChunkAt(cx, cz), null));
        try {
            plugin.stations().rescan(shop);
            server.getScheduler().performTicks(10);
        } finally {
            ru.buildmaster.util.ChunkJob.setLoader(null);
        }
        assertEquals(1, plugin.stations().count(shop, Station.ANVIL));
        assertEquals(2, plugin.stations().count(shop, Station.FURNACE));
        assertEquals(2, plugin.stations().groupCount(shop, Station.FURNACE));
    }
}
