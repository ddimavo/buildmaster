package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.PlotService;
import ru.buildmaster.support.PluginTestBase;
import ru.buildmaster.util.ChunkJob;

/** Электричество мастерских, счёт предприятия, штраф при изъятии. */
class PowerIntegrationTest extends PluginTestBase {

    private static final long HOUR = 3600;
    private static final long DAY = 86_400;

    private final AtomicLong wall = new AtomicLong(1_000_000);
    private PlayerMock steve;
    private Plot workshop;

    @BeforeEach
    void setUpWorkshop() {
        ChunkJob.setLoader((world, cx, cz, cb) -> cb.accept(world.getChunkAt(cx, cz), null));
        plugin.getConfig().set("electricity.max-clock-step-seconds", 10_000_000);
        plugin.power().setTimeSource(wall::get);
        steve = player("Steve", 100_000);
        workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        plugin.service().rent(steve, workshop, 14);
    }

    private void pass(long seconds) {
        wall.addAndGet(seconds * 1000);
        plugin.power().tick();
    }

    private Block block(int x, int y, int z) {
        return server.getWorld("world").getBlockAt(x, y, z);
    }

    private void place(PlayerMock who, Block b, Material m) {
        b.setType(m);
        server.getPluginManager().callEvent(new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0),
                new ItemStack(m), who, true, EquipmentSlot.HAND));
    }

    private void equip360() {
        // две плавильни и наковальня: 100 × (1.0 + 1.1 + 1.5) = 360 в сутки
        place(steve, block(1, 1, 1), Material.FURNACE);
        place(steve, block(2, 1, 1), Material.BLAST_FURNACE);
        place(steve, block(3, 1, 1), Material.ANVIL);
    }

    private boolean burnCancelled(int x) {
        FurnaceBurnEvent e = new FurnaceBurnEvent(block(x, 1, 1), new ItemStack(Material.COAL), 1600);
        server.getPluginManager().callEvent(e);
        return e.isCancelled();
    }

    @Test
    void tariffIsComputedFromPlacedStations() {
        equip360();
        assertEquals(360, workshop.account.ratePerDay(), 1e-9);
        assertEquals(2, plugin.stations().count(workshop, ru.buildmaster.model.Station.FURNACE));
        assertEquals(1, plugin.stations().count(workshop, ru.buildmaster.model.Station.ANVIL));
    }

    @Test
    void noStationsMeansNoTariffAndNothingToSwitchOff() {
        assertEquals(0, workshop.account.ratePerDay(), 1e-9);
        assertTrue(plugin.power().isPowered(workshop));
    }

    @Test
    void workshopWithStationsAndEmptyAccountIsDark() {
        equip360();
        assertFalse(plugin.power().isPowered(workshop));
        assertTrue(burnCancelled(1), "без оплаты печь не топится");
    }

    @Test
    void smeltingIsBlockedWithoutPowerToo() {
        equip360();
        FurnaceSmeltEvent e = new FurnaceSmeltEvent(block(1, 1, 1), new ItemStack(Material.IRON_ORE),
                new ItemStack(Material.IRON_INGOT));
        server.getPluginManager().callEvent(e);
        assertTrue(e.isCancelled());
    }

    @Test
    void depositSwitchesPowerOn() {
        equip360();
        assertTrue(plugin.power().deposit(steve, workshop, 360));
        assertTrue(plugin.power().isPowered(workshop));
        assertFalse(burnCancelled(1));
        assertEquals(Journal.Action.ACCOUNT_DEPOSIT, plugin.journal().forPlot("w").get(0).action());
    }

    @Test
    void anyoneCanTopUpTheAccount() {
        equip360();
        PlayerMock alex = player("Alex", 1000);
        assertTrue(plugin.power().deposit(alex, workshop, 500));
        assertEquals(500, plugin.power().balance(workshop), 1e-9);
        assertEquals(500, economy.getBalance(alex), 1e-9, "деньги списаны у Alex");
    }

    @Test
    void accountIsConsumedWithTimeAndPowerGoesOutAtZero() {
        equip360();
        plugin.power().deposit(steve, workshop, 360);

        pass(DAY / 2);
        assertEquals(180, plugin.power().balance(workshop), 1e-6);
        assertTrue(plugin.power().isPowered(workshop));

        pass(DAY / 2 + 1);
        assertEquals(0, plugin.power().balance(workshop), 1e-6);
        assertFalse(plugin.power().isPowered(workshop));
        assertTrue(burnCancelled(2));
    }

    @Test
    void consumedElectricityGoesToTreasury() {
        equip360();
        double before = plugin.treasury().balance();
        plugin.power().deposit(steve, workshop, 360);
        assertEquals(before, plugin.treasury().balance(), 1e-9, "внесённые деньги пока принадлежат игроку");

        pass(DAY / 4);
        assertEquals(before + 90, plugin.treasury().balance(), 1e-6);
    }

    @Test
    void tariffChangeAppliesFromTheMomentOfChange() {
        equip360();
        plugin.power().deposit(steve, workshop, 1000);
        pass(DAY / 2);                      // 180 по старому тарифу
        place(steve, block(4, 1, 1), Material.BREWING_STAND); // + алхимия 2.5 = 610 в сутки
        assertEquals(610, workshop.account.ratePerDay(), 1e-9);
        assertEquals(820, plugin.power().balance(workshop), 1e-6, "до смены тарифа списано только по старому");
    }

    @Test
    void removingAStationLowersTheTariff() {
        equip360();
        Block anvil = block(3, 1, 1);
        server.getPluginManager().callEvent(new org.bukkit.event.block.BlockBreakEvent(anvil, steve));
        assertEquals(210, workshop.account.ratePerDay(), 1e-9);
    }

    @Test
    void stationsOutsideWorkshopsAreOrdinaryBlocks() {
        Plot house = addPlot("h", Category.HOUSE, 20, 100, -1);
        plugin.service().buy(steve, house);
        place(steve, block(21, 1, 1), Material.FURNACE);
        place(steve, block(22, 1, 1), Material.ANVIL);

        assertEquals(0, house.account.ratePerDay(), 1e-9);
        assertTrue(plugin.power().isPowered(house));
        FurnaceBurnEvent e = new FurnaceBurnEvent(block(21, 1, 1), new ItemStack(Material.COAL), 1600);
        server.getPluginManager().callEvent(e);
        assertFalse(e.isCancelled(), "в доме печь работает бесплатно");
    }

    @Test
    void freeWorkshopIsNotBilled() {
        Plot other = addPlot("w2", Category.WORKSHOP, 20, -1, 10);
        assertFalse(plugin.power().isBilled(other));
        assertEquals(0, plugin.power().rateFor(other), 1e-9);
    }

    // ------------------------------------------------------------ предупреждения

    @Test
    void warningsAtTwentyFourSixAndZeroHours() {
        equip360();
        plugin.power().deposit(steve, workshop, 360);   // ровно на сутки
        plugin.power().tick();
        assertEquals(1, plugin.warnings().forPlayer(steve.getUniqueId()).size(), "за 24 часа");

        pass(19 * HOUR);                                 // осталось 5 часов
        assertEquals(2, plugin.warnings().forPlayer(steve.getUniqueId()).size(), "за 6 часов");

        pass(6 * HOUR);
        assertEquals(3, plugin.warnings().forPlayer(steve.getUniqueId()).size(), "обесточено");

        pass(HOUR);
        assertEquals(3, plugin.warnings().forPlayer(steve.getUniqueId()).size(), "повторно не предупреждаем");
        assertTrue(plugin.warnings().unread(steve.getUniqueId()) >= 3);
    }

    @Test
    void depositResetsWarnings() {
        equip360();
        plugin.power().deposit(steve, workshop, 360);
        pass(DAY + 10);
        int before = plugin.warnings().forPlayer(steve.getUniqueId()).size();
        plugin.power().deposit(steve, workshop, 3600);   // на 10 суток
        pass(10);
        assertEquals(before, plugin.warnings().forPlayer(steve.getUniqueId()).size(), "хватает надолго - тишина");
        pass(9 * DAY + 2 * HOUR);                         // осталось меньше суток
        assertTrue(plugin.warnings().forPlayer(steve.getUniqueId()).size() > before);
    }

    // ------------------------------------------------------------ счёт: снятие

    @Test
    void withdrawalKeepsOneDayOfElectricityInReserve() {
        equip360();
        plugin.power().deposit(steve, workshop, 1000);
        double money = economy.getBalance(steve);

        assertFalse(plugin.power().withdraw(steve, workshop, 700), "резерв 360 неприкосновенен");
        assertTrue(plugin.power().withdraw(steve, workshop, 640));
        assertEquals(money + 640, economy.getBalance(steve), 1e-9);
        assertEquals(360, plugin.power().balance(workshop), 1e-6);
    }

    @Test
    void onlyTheManagerCanWithdraw() {
        equip360();
        PlayerMock alex = player("Alex", 1000);
        plugin.power().deposit(alex, workshop, 1000);
        assertFalse(plugin.power().withdraw(alex, workshop, 10));
    }

    @Test
    void shopAccountHasNoReserve() {
        Plot shop = addPlot("s", Category.SHOP, 20, 100, -1);
        plugin.service().buy(steve, shop);
        plugin.power().deposit(steve, shop, 500);
        assertTrue(plugin.power().withdraw(steve, shop, 500));
    }

    // ------------------------------------------------------------ возврат и штраф

    @Test
    void voluntaryReleaseReturnsTheWholeBalance() {
        equip360();
        plugin.power().deposit(steve, workshop, 1000);
        double money = economy.getBalance(steve);

        plugin.service().release(steve, workshop);

        assertEquals(money + 1000, economy.getBalance(steve), 1e-6);
        assertEquals(0, workshop.account.rawBalance(), 1e-9);
        assertEquals(Journal.Action.ACCOUNT_REFUND, plugin.journal().forPlayer(steve.getUniqueId()).stream()
                .filter(e -> e.action() == Journal.Action.ACCOUNT_REFUND).findFirst().orElseThrow().action());
    }

    @Test
    void seizureWithholdsTenPercentForTheTreasury() {
        equip360();
        plugin.power().deposit(steve, workshop, 1000);
        double money = economy.getBalance(steve);
        double treasury = plugin.treasury().balance();

        plugin.service().evict(workshop, new PlotService.Release("Boss", "нарушение", Journal.Action.SEIZED, true,
                Plot.RestoreMode.FULL));

        assertEquals(money + 900, economy.getBalance(steve), 1e-6, "игроку возвращается 90%");
        assertEquals(treasury + 100, plugin.treasury().balance(), 1e-6, "10% в казну");
        assertTrue(plugin.journal().forPlayer(steve.getUniqueId()).stream()
                .anyMatch(e -> e.action() == Journal.Action.ACCOUNT_PENALTY));
    }

    @Test
    void penaltyIsTakenFromWhatIsLeftAfterConsumption() {
        equip360();
        plugin.power().deposit(steve, workshop, 720);
        pass(DAY);                                         // 360 израсходовано, осталось 360
        double money = economy.getBalance(steve);

        plugin.service().evict(workshop, new PlotService.Release("Boss", "нарушение", Journal.Action.SEIZED, true,
                Plot.RestoreMode.FULL));

        assertEquals(money + 324, economy.getBalance(steve), 1e-3);
    }

    @Test
    void expiredRentReturnsTheBalanceWithoutPenalty() {
        equip360();
        plugin.power().deposit(steve, workshop, 1000);
        double money = economy.getBalance(steve);
        workshop.rentedUntil = System.currentTimeMillis() - 1000;

        plugin.service().tick();

        assertTrue(workshop.isFree());
        assertEquals(money + 1000, economy.getBalance(steve), 1e-3);
    }

    @Test
    void newTenantStartsWithAnEmptyAccount() {
        equip360();
        plugin.power().deposit(steve, workshop, 1000);
        plugin.service().release(steve, workshop);
        PlayerMock alex = player("Alex", 5000);

        plugin.service().rent(alex, workshop, 3);

        assertEquals(0, workshop.account.rawBalance(), 1e-9);
    }

    @Test
    void accountSurvivesRestart() {
        equip360();
        plugin.power().deposit(steve, workshop, 1000);
        plugin.plots().save();

        plugin.plots().load();
        Plot loaded = plugin.plots().get("w");

        assertEquals(1000, loaded.account.rawBalance(), 1e-9);
        assertEquals(360, loaded.account.ratePerDay(), 1e-9);
    }

    @Test
    void depositNeedsMoneyAndPositiveAmount() {
        PlayerMock poor = player("Poor", 5);
        assertFalse(plugin.power().deposit(poor, workshop, 100));
        assertFalse(plugin.power().deposit(poor, workshop, 0));
        assertFalse(plugin.power().deposit(poor, workshop, -5));
        assertEquals(5, economy.getBalance(poor), 1e-9);
        assertEquals(0, workshop.account.rawBalance(), 1e-9);
    }
}
