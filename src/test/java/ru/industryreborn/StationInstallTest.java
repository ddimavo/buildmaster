package ru.industryreborn;

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
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Station;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.SnapshotStore;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.model.Box;

/** Платная установка станков в мастерской: деньги списываются автоматически, без денег блок не ставится. */
class StationInstallTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock alex;
    private PlayerMock boss;
    private Plot workshop;
    private double wallet;      // кошелёк Стива после оплаты аренды
    private double treasuryBase;

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
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.workshop", 0);
        plugin.getConfig().set("station-install.smelter", 200);
        plugin.getConfig().set("station-install.anvil", 300);
        plugin.getConfig().set("station-install.alchemy", 500);
        plugin.getConfig().set("station-install.enchanting", 1000);
        steve = player("Steve", 10_000);
        alex = player("Alex", 10_000);
        boss = admin("Boss");
        workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        assertTrue(plugin.service().rent(steve, workshop, 14));
        wallet = economy.getBalance(steve);
        treasuryBase = plugin.treasury().balance();
    }

    private Block block(int x) {
        return server.getWorld("world").getBlockAt(x, 1, 1);
    }

    private BlockPlaceEvent place(PlayerMock who, Block b, Material m) {
        b.setType(m);
        BlockPlaceEvent e = new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0), new ItemStack(m), who, true,
                EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        return e;
    }

    @Test
    void placingAStationChargesTheAccountAndRegistersAMachine() {
        workshop.account.deposit(1000);
        assertFalse(place(steve, block(1), Material.FURNACE).isCancelled());
        assertEquals(800, plugin.power().balance(workshop), 1e-6);
        assertEquals(wallet, economy.getBalance(steve), 1e-9, "кошелёк не тронут, хватило счёта");
        assertEquals(treasuryBase + 200, plugin.treasury().balance(), 1e-9);
        assertEquals(Station.FURNACE, plugin.stations().machines(workshop).get("1,1,1"));
        assertEquals(1, plugin.stations().count(workshop, Station.FURNACE));
        assertTrue(drain(steve).contains("установлен"));
        assertTrue(plugin.journal().forPlot("w").stream().anyMatch(e -> e.action() == Journal.Action.STATION));
    }

    @Test
    void theShortfallIsTakenFromTheManagersWallet() {
        workshop.account.deposit(120);
        assertFalse(place(steve, block(1), Material.ANVIL).isCancelled());     // 300 = 120 со счёта + 180 кошелёк
        assertEquals(0, plugin.power().balance(workshop), 1e-6);
        assertEquals(wallet - 180, economy.getBalance(steve), 1e-9);
    }

    @Test
    void withoutMoneyTheBlockIsNotPlacedAndTheReasonIsShown() {
        economy.set(steve, 50);
        workshop.account.deposit(100);
        BlockPlaceEvent e = place(steve, block(1), Material.ENCHANTING_TABLE);
        assertTrue(e.isCancelled());
        String msg = drain(steve);
        assertTrue(msg.contains("Не хватает денег"), msg);
        assertTrue(msg.contains("стол зачарований"));
        assertEquals(100, plugin.power().balance(workshop), 1e-6, "ничего не списано");
        assertEquals(50, economy.getBalance(steve), 1e-9);
        assertTrue(plugin.stations().machines(workshop).isEmpty());
        assertEquals(0, plugin.stations().count(workshop, Station.ENCHANTING));
        assertEquals(0, workshop.account.ratePerDay(), 1e-9, "тариф не вырос");
    }

    @Test
    void onlyTheManagerInstallsStationsInAWorkshop() {
        workshop.grant(alex.getUniqueId(), Plot.Perm.BUILD, Plot.Perm.STATIONS);
        BlockPlaceEvent e = place(alex, block(1), Material.FURNACE);
        assertTrue(e.isCancelled());
        assertTrue(drain(alex).contains("только управляющий"));
        assertEquals(10_000, economy.getBalance(alex), 1e-9);
    }

    @Test
    void adminsInstallForFreeAndStillRegisterTheMachine() {
        assertFalse(place(boss, block(1), Material.BREWING_STAND).isCancelled());
        assertEquals(treasuryBase, plugin.treasury().balance(), 1e-9);
        assertEquals(Station.ALCHEMY, plugin.stations().machines(workshop).get("1,1,1"));
    }

    @Test
    void breakingAMachineRemovesTheRecord() {
        workshop.account.deposit(1000);
        place(steve, block(1), Material.FURNACE);
        server.getPluginManager().callEvent(new BlockBreakEvent(block(1), steve));
        assertTrue(plugin.stations().machines(workshop).isEmpty());
        assertEquals(0, plugin.stations().count(workshop, Station.FURNACE));
    }

    @Test
    void aFreeInstallPriceMeansNoCharge() {
        plugin.getConfig().set("station-install.smelter", 0);
        economy.set(steve, 0);
        assertFalse(place(steve, block(1), Material.SMOKER).isCancelled());
        assertEquals(treasuryBase, plugin.treasury().balance(), 1e-9);
    }

    @Test
    void stationsWithoutATariffAndOtherBuildingsAreNotCharged() {
        assertFalse(place(steve, block(1), Material.CRAFTING_TABLE).isCancelled());
        assertEquals(wallet, economy.getBalance(steve), 1e-9);
        Plot house = addPlot("h", Category.HOUSE, 20, 100, -1);
        plugin.service().buy(steve, house);
        double before = economy.getBalance(steve);
        server.getWorld("world").getBlockAt(21, 1, 1).setType(Material.AIR);
        BlockPlaceEvent e = place(steve, server.getWorld("world").getBlockAt(21, 1, 1), Material.FURNACE);
        assertFalse(e.isCancelled());
        assertEquals(before, economy.getBalance(steve), 1e-9, "в доме станки бесплатны");
        assertTrue(plugin.stations().machines(house).isEmpty());
    }

    @Test
    void theTariffGrowsOnlyAfterASuccessfulInstall() {
        workshop.account.deposit(1000);
        place(steve, block(1), Material.FURNACE);
        assertTrue(workshop.account.ratePerDay() > 0);
    }

    @Test
    void rescanRegistersExistingStationsAsMachines() {
        server.getWorld("world").getBlockAt(2, 1, 2).setType(Material.BLAST_FURNACE);
        server.getWorld("world").getBlockAt(3, 1, 2).setType(Material.ANVIL);
        server.getWorld("world").getBlockAt(4, 1, 2).setType(Material.CRAFTING_TABLE);
        ru.industryreborn.util.ChunkJob.setLoader((world, cx, cz, cb) -> cb.accept(world.getChunkAt(cx, cz), null));
        try {
            plugin.stations().rescan(workshop);
            server.getScheduler().performTicks(5);
        } finally {
            ru.industryreborn.util.ChunkJob.setLoader(null);
        }
        assertEquals(2, plugin.stations().machines(workshop).size(), "верстак без тарифа не считается");
        assertEquals(Station.FURNACE, plugin.stations().machines(workshop).get("2,1,2"));
    }

    @Test
    void theSnapshotSizeIncludesBothBoundaries() {
        assertEquals(new org.bukkit.util.BlockVector(4, 3, 4), SnapshotStore.sizeOf(new Box(10, 1, 20, 13, 3, 23)));
        assertEquals(new org.bukkit.util.BlockVector(1, 1, 1), SnapshotStore.sizeOf(new Box(5, 5, 5, 5, 5, 5)),
                "область в один блок не должна давать размер 0");
        assertEquals(new org.bukkit.util.BlockVector(6, 1, 6), SnapshotStore.sizeOf(Box.of(8, 4, 8, 3, 4, 3)),
                "плоская комната (одна клетка по высоте)");
    }
}
