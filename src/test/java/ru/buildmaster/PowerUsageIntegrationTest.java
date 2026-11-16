package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.PowerOverrides;
import ru.buildmaster.service.PowerService;
import ru.buildmaster.support.PluginTestBase;

/** Режим USAGE, переопределения тарифов, скидки, подарки, статистика. */
class PowerUsageIntegrationTest extends PluginTestBase {

    private static final long DAY = 86_400;

    private final AtomicLong wall = new AtomicLong(1_000_000);
    private PlayerMock steve;
    private PlayerMock boss;
    private Plot workshop;

    @BeforeEach
    void setUp() {
        plugin.getConfig().set("electricity.max-clock-step-seconds", 10_000_000);
        plugin.power().setTimeSource(wall::get);
        steve = player("Steve", 100_000);
        boss = admin("Boss");
        workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        plugin.service().rent(steve, workshop, 14);
    }

    private void pass(long seconds) {
        wall.addAndGet(seconds * 1000);
        plugin.power().tick();
    }

    private Block block(int x) {
        return server.getWorld("world").getBlockAt(x, 1, 1);
    }

    private void place(Material m, int x) {
        Block b = block(x);
        b.setType(m);
        server.getPluginManager().callEvent(new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0),
                new ItemStack(m), steve, true, EquipmentSlot.HAND));
    }

    private void equip360() {
        place(Material.FURNACE, 1);
        place(Material.BLAST_FURNACE, 2);
        place(Material.ANVIL, 3);
    }

    private boolean smelt() {
        FurnaceSmeltEvent e = new FurnaceSmeltEvent(block(1), new ItemStack(Material.IRON_ORE),
                new ItemStack(Material.IRON_INGOT));
        server.getPluginManager().callEvent(e);
        return !e.isCancelled();
    }

    private void usageMode() {
        boss.performCommand("district power admin mode USAGE");
        assertEquals(PowerService.Mode.USAGE, plugin.power().mode());
    }

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ USAGE

    @Test
    void usageModeHasNoContinuousTariff() {
        equip360();
        assertEquals(360, workshop.account.ratePerDay(), 1e-9);
        usageMode();
        assertEquals(0, workshop.account.ratePerDay(), 1e-9);
        plugin.power().deposit(steve, workshop, 100);
        pass(10 * DAY);
        assertEquals(100, plugin.power().balance(workshop), 1e-9, "время деньги не списывает");
    }

    @Test
    void eachSmeltCostsUsageBaseTimesCoefficient() {
        equip360();
        usageMode();
        plugin.power().deposit(steve, workshop, 10);
        double treasury = plugin.treasury().balance();

        assertTrue(smelt());

        assertEquals(9, plugin.power().balance(workshop), 1e-9);
        assertEquals(treasury + 1, plugin.treasury().balance(), 1e-9);
    }

    @Test
    void smeltIsRefusedWhenTheAccountIsEmpty() {
        equip360();
        usageMode();
        assertFalse(smelt());
        assertEquals(0, plugin.power().stats().total("w").operations(), "отменённая операция не считается");
    }

    @Test
    void smeltIsRefusedWhenBalanceIsBelowThePrice() {
        equip360();
        usageMode();
        plugin.power().deposit(steve, workshop, 0.5);
        assertFalse(smelt());
        assertEquals(0.5, plugin.power().balance(workshop), 1e-9, "ничего не списано");
    }

    @Test
    void pricesFollowCoefficients() {
        usageMode();
        plugin.power().deposit(steve, workshop, 100);
        assertTrue(plugin.power().useStation(workshop, "enchanting"));
        assertEquals(96, plugin.power().balance(workshop), 1e-9);
        assertTrue(plugin.power().useStation(workshop, "anvil"));
        assertEquals(94.5, plugin.power().balance(workshop), 1e-9);
        assertTrue(plugin.power().useStation(workshop, "alchemy"));
        assertEquals(92, plugin.power().balance(workshop), 1e-9);
    }

    @Test
    void discountHalvesTheOperationPrice() {
        usageMode();
        plugin.power().setDiscount("Boss", workshop, 50);
        plugin.power().deposit(steve, workshop, 100);
        plugin.power().useStation(workshop, "enchanting");
        assertEquals(98, plugin.power().balance(workshop), 1e-9);
    }

    @Test
    void usageBaseIsConfigurable() {
        usageMode();
        boss.performCommand("district power admin usage 5");
        plugin.power().deposit(steve, workshop, 100);
        plugin.power().useStation(workshop, "smelter");
        assertEquals(95, plugin.power().balance(workshop), 1e-9);
    }

    @Test
    void operationsOutsideWorkshopsAreFreeAndNotCounted() {
        usageMode();
        Plot house = addPlot("h", Category.HOUSE, 20, 100, -1);
        plugin.service().buy(steve, house);
        assertTrue(plugin.power().useStation(house, "smelter"));
        assertEquals(0, plugin.power().stats().total("h").operations());
    }

    @Test
    void switchingModeClosesTheOldTariffFirst() {
        equip360();
        plugin.power().deposit(steve, workshop, 360);
        pass(DAY / 2);                       // по суточному тарифу ушло 180
        usageMode();
        assertEquals(180, plugin.power().balance(workshop), 1e-6);
        pass(DAY);
        assertEquals(180, plugin.power().balance(workshop), 1e-6, "после перехода на USAGE время не списывает");

        boss.performCommand("district power admin mode DAILY");
        assertEquals(360, workshop.account.ratePerDay(), 1e-9);
    }

    @Test
    void usageWarningsWhenTheAccountRunsLow() {
        equip360();
        usageMode();
        plugin.power().deposit(steve, workshop, 50);   // самая дорогая операция 1.5: больше 20 операций
        plugin.power().tick();
        int quiet = plugin.warnings().forPlayer(steve.getUniqueId()).size();

        plugin.power().useStation(workshop, "anvil");
        workshop.account.restore(20, 0, workshop.account.updatedAtSec());   // < 20 × 1.5
        plugin.power().tick();
        assertEquals(quiet + 1, plugin.warnings().forPlayer(steve.getUniqueId()).size(), "мало денег");

        workshop.account.restore(1, 0, workshop.account.updatedAtSec());    // < 1.5
        plugin.power().tick();
        assertEquals(quiet + 2, plugin.warnings().forPlayer(steve.getUniqueId()).size(), "не хватает");
    }

    // ------------------------------------------------------------ DAILY: статистика

    @Test
    void dailyModeCountsOperationsWithoutCharging() {
        equip360();
        plugin.power().deposit(steve, workshop, 360);
        assertTrue(smelt());
        assertTrue(smelt());

        assertEquals(2, plugin.power().stats().byType("w").get("smelter").operations());
        assertEquals(0, plugin.power().stats().byType("w").get("smelter").spent(), 1e-9);
        assertEquals(360, plugin.power().balance(workshop), 1e-6);
    }

    @Test
    void dailyModeRefusesOperationsWithoutPower() {
        equip360();
        assertFalse(smelt());
        assertEquals(0, plugin.power().stats().total("w").operations());
    }

    // ------------------------------------------------------------ переопределения командами

    @Test
    void baseOverrideChangesTheTariffAndNotifiesOwners() {
        equip360();
        int before = plugin.warnings().forPlayer(steve.getUniqueId()).size();

        boss.performCommand("district power admin base 200");

        assertEquals(720, workshop.account.ratePerDay(), 1e-9);
        assertEquals(before + 1, plugin.warnings().forPlayer(steve.getUniqueId()).size());
        assertTrue(plugin.warnings().forPlayer(steve.getUniqueId()).get(0).text.contains("720"));
        assertEquals(Journal.Action.TARIFF, plugin.journal().forPlot("w").get(0).action());
    }

    @Test
    void oldTariffIsSettledBeforeTheNewOneStarts() {
        equip360();
        plugin.power().deposit(steve, workshop, 1000);
        pass(DAY / 2);                                       // 180 по 360 в сутки
        boss.performCommand("district power admin base 200"); // дальше 720 в сутки
        assertEquals(820, plugin.power().balance(workshop), 1e-6);
        pass(DAY / 2);
        assertEquals(820 - 360, plugin.power().balance(workshop), 1e-6);
    }

    @Test
    void coefficientOverrideBeatsConfigAndResetRestoresIt() {
        equip360();
        boss.performCommand("district power admin coef set smelter 2");
        assertEquals(2.0, plugin.power().coefficient("smelter"), 1e-9);
        assertEquals(100 * (2 * 2.1 + 1.5), workshop.account.ratePerDay(), 1e-9);

        boss.performCommand("district power admin coef reset smelter");
        assertEquals(1.0, plugin.power().coefficient("smelter"), 1e-9);
        assertEquals(360, workshop.account.ratePerDay(), 1e-9);
    }

    @Test
    void progressionOverride() {
        equip360();
        boss.performCommand("district power admin progression 0");
        assertEquals(100 * (2.0 + 1.5), workshop.account.ratePerDay(), 1e-9);
        boss.performCommand("district power admin progression reset");
        assertEquals(360, workshop.account.ratePerDay(), 1e-9);
    }

    @Test
    void overridesArePersistedToTheirOwnFile() {
        boss.performCommand("district power admin base 123");
        boss.performCommand("district power admin coef set anvil 7");
        File file = new File(plugin.getDataFolder(), "overrides.yml");
        assertTrue(file.exists());
        PowerOverrides loaded = PowerOverrides.load(file);
        assertEquals(123.0, loaded.base(), 1e-9);
        assertEquals(7.0, loaded.coefficient("anvil"), 1e-9);
    }

    @Test
    void invalidAdminInputIsRejected() {
        boss.performCommand("district power admin base -5");
        boss.performCommand("district power admin base abc");
        boss.performCommand("district power admin mode weekly");
        boss.performCommand("district power admin coef set laser 3");
        assertNull(plugin.power().overrides().base());
        assertNull(plugin.power().overrides().mode());
        assertNull(plugin.power().overrides().coefficient("laser"));
    }

    @Test
    void adminCommandsAreForAdminsOnly() {
        steve.performCommand("district power admin base 1");
        assertTrue(drain(steve).contains("Недостаточно прав"));
        assertNull(plugin.power().overrides().base());
    }

    @Test
    void infoShowsSourcesOfValues() {
        boss.performCommand("district power admin base 150");
        drain(boss);
        boss.performCommand("district power admin info");
        String out = drain(boss);
        assertTrue(out.contains("150"), out);
        assertTrue(out.contains("команда админа"), out);
        assertTrue(out.contains("config.yml"), out);
    }

    // ------------------------------------------------------------ скидка

    @Test
    void discountLowersTheDailyTariffAndIsPersisted() {
        equip360();
        boss.performCommand("district power admin discount w 50");
        assertEquals(180, workshop.account.ratePerDay(), 1e-9);

        plugin.plots().save();
        plugin.plots().load();
        assertEquals(50, plugin.plots().get("w").discountPercent, 1e-9);
    }

    @Test
    void discountRangeIsValidated() {
        equip360();
        assertNotNull(plugin.power().setDiscount("Boss", workshop, 150));
        assertNotNull(plugin.power().setDiscount("Boss", workshop, -1));
        assertEquals(0, workshop.discountPercent, 1e-9);
        assertEquals(360, workshop.account.ratePerDay(), 1e-9);
    }

    @Test
    void discountDoesNotPassToTheNextTenant() {
        equip360();
        plugin.power().setDiscount("Boss", workshop, 50);
        plugin.service().release(steve, workshop);
        assertEquals(0, workshop.discountPercent, 1e-9);
    }

    // ------------------------------------------------------------ подарок из казны

    @Test
    void giftComesFromTheTreasuryNotFromThinAir() {
        plugin.treasury().deposit(1000, "тест");
        double before = plugin.treasury().balance();

        boss.performCommand("district power admin add w 300 компенсация за сбой");

        assertEquals(300, plugin.power().balance(workshop), 1e-9);
        assertEquals(before - 300, plugin.treasury().balance(), 1e-9);
        Journal.Entry e = plugin.journal().forPlot("w").get(0);
        assertEquals(Journal.Action.ACCOUNT_GIFT, e.action());
        assertTrue(e.reason().contains("компенсация за сбой"));
        assertTrue(plugin.warnings().forPlayer(steve.getUniqueId()).get(0).text.contains("компенсация за сбой"));
    }

    @Test
    void giftIsRefusedWhenTheTreasuryIsEmpty() {
        double treasury = plugin.treasury().balance();
        String error = plugin.power().gift("Boss", workshop, treasury + 500, "причина");
        assertNotNull(error);
        assertEquals(0, plugin.power().balance(workshop), 1e-9);
        assertEquals(treasury, plugin.treasury().balance(), 1e-9);
    }

    @Test
    void giftNeedsAReasonAndAValidAmount() {
        plugin.treasury().deposit(1000, "тест");
        boss.performCommand("district power admin add w 100");
        assertEquals(0, plugin.power().balance(workshop), 1e-9);
        assertNotNull(plugin.power().gift("Boss", workshop, -5, "x"));
        assertNotNull(plugin.power().gift("Boss", workshop, 0, "x"));
    }

    @Test
    void giftToAFreePlotIsRefused() {
        Plot free = addPlot("f", Category.WORKSHOP, 20, -1, 10);
        plugin.treasury().deposit(1000, "тест");
        assertNotNull(plugin.power().gift("Boss", free, 10, "x"));
    }

    // ------------------------------------------------------------ статистика командой

    @Test
    void statsCommandShowsOperations() {
        equip360();
        plugin.power().deposit(steve, workshop, 360);
        smelt();
        smelt();
        drain(boss);

        boss.performCommand("district power admin stats w");
        String out = drain(boss);
        assertTrue(out.contains("smelter"), out);
        assertTrue(out.contains("2 опер"), out);

        boss.performCommand("district power admin stats");
        assertTrue(drain(boss).contains("Самые загруженные"));
    }

    @Test
    void statsArePersistedByTheTick() {
        equip360();
        plugin.power().deposit(steve, workshop, 360);
        smelt();
        plugin.power().tick();
        File file = new File(plugin.getDataFolder(), "stats.yml");
        assertTrue(file.exists());
        assertEquals(1, ru.buildmaster.service.StationStats.load(file).byType("w").get("smelter").operations());
    }
}
