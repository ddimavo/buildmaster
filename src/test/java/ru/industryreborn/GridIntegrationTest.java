package ru.industryreborn;

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
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.PlotService;
import ru.industryreborn.support.PluginTestBase;

/** Электростанции, подключения, перегрузка, потребление в V, отключения и районы. */
class GridIntegrationTest extends PluginTestBase {

    private static final long DAY = 86_400;

    private final AtomicLong wall = new AtomicLong(1_000_000);
    private PlayerMock steve;
    private PlayerMock boss;

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
        plugin.getConfig().set("electricity.max-clock-step-seconds", 10_000_000);
        // у управляющего несколько мастерских: лимиты владения не мешают проверкам сети
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.workshop", 0);
        plugin.power().setTimeSource(wall::get);
        steve = player("Steve", 1_000_000);
        boss = admin("Boss");
    }

    private void pass(long seconds) {
        wall.addAndGet(seconds * 1000);
        plugin.power().tick();
    }

    private Plot plant(String id, int x, double capacity) {
        Plot p = addPlot(id, Category.POWER_PLANT, x, -1, -1);
        p.plantCapacity = capacity;
        return p;
    }

    private Plot workshop(String id, int x) {
        return addPlot(id, Category.WORKSHOP, x, -1, 10);
    }

    private void place(Plot plot, Material m, int dx) {
        Block b = server.getWorld("world").getBlockAt(plot.box.minX() + dx, 1, 1);
        b.setType(m);
        server.getPluginManager().callEvent(new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0),
                new ItemStack(m), steve, true, EquipmentSlot.HAND));
    }

    /** Две плавильни и наковальня: 3.6 V в сутки, 360 по цене 100. */
    private void equip(Plot plot) {
        place(plot, Material.FURNACE, 1);
        place(plot, Material.BLAST_FURNACE, 2);
        place(plot, Material.ANVIL, 3);
    }

    private boolean burnCancelled(Plot plot) {
        FurnaceBurnEvent e = new FurnaceBurnEvent(server.getWorld("world").getBlockAt(plot.box.minX() + 1, 1, 1),
                new ItemStack(Material.COAL), 1600);
        server.getPluginManager().callEvent(e);
        return e.isCancelled();
    }

    // ------------------------------------------------------------ без станций сеть не действует

    @Test
    void withoutPlantsTheGridIsInactiveAndWorkshopsWorkAsBefore() {
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        plugin.power().deposit(steve, w, 360);

        assertFalse(plugin.grid().active());
        assertTrue(plugin.grid().onGrid(w));
        assertTrue(plugin.power().isPowered(w));
        assertFalse(burnCancelled(w));
        assertEquals(360, w.account.ratePerDay(), 1e-9);
        assertEquals(3.6, w.account.unitsPerDay(), 1e-9);
    }

    // ------------------------------------------------------------ подключение

    @Test
    void unconnectedWorkshopHasNoPowerOnceAPlantExists() {
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        plugin.power().deposit(steve, w, 1000);
        plant("p1", 100, 1000);

        assertTrue(plugin.grid().active());
        assertFalse(plugin.grid().onGrid(w));
        plugin.power().recalc(w);
        assertFalse(plugin.power().isPowered(w));
        assertTrue(burnCancelled(w));
        assertEquals(0, w.account.ratePerDay(), 1e-9, "пока не подключено, тариф не начисляется");
        assertNotNull(plugin.grid().offReason(w));
    }

    @Test
    void newTenantIsConnectedToTheLeastLoadedPlant() {
        Plot big = plant("big", 100, 1000);
        Plot small = plant("small", 120, 10);
        Plot w1 = workshop("w1", 0);
        Plot w2 = workshop("w2", 20);
        plugin.service().rent(steve, w1, 5);
        equip(w1);
        // при равной нагрузке (0%) выбирается станция первая по номеру; первая мастерская заняла часть мощности big,
        // поэтому следующая идёт на small, где нагрузка 0%
        plugin.service().rent(steve, w2, 5);

        assertEquals("big", w1.plantId);
        assertEquals("small", w2.plantId, "нагрузка small 0%, big - 0.36%");
        assertEquals(1, plugin.grid().connected(small).size());
        assertEquals(1, plugin.grid().connected(big).size());
    }

    @Test
    void autoConnectBalancesLoadByRatioNotByAbsoluteFreeCapacity() {
        Plot big = plant("big", 100, 1000);
        Plot small = plant("small", 120, 10);
        Plot w1 = workshop("w1", 0);
        Plot w2 = workshop("w2", 20);
        Plot w3 = workshop("w3", 40);
        plugin.service().rent(steve, w1, 5);
        equip(w1);                                   // big: 0.36%
        plugin.grid().connect(w1, small, "Boss");    // теперь small: 36%, big: 0%
        plugin.service().rent(steve, w2, 5);
        plugin.service().rent(steve, w3, 5);

        assertEquals("big", w2.plantId, "big загружена меньше (0%)");
        assertEquals("big", w3.plantId);
        assertNotNull(big);
    }

    @Test
    void aWorkshopIsConnectedToExactlyOnePlant() {
        Plot p1 = plant("p1", 100, 1000);
        Plot p2 = plant("p2", 120, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        String first = w.plantId;
        Plot other = first.equals("p1") ? p2 : p1;

        assertNull(plugin.grid().connect(w, other, "Boss"));

        assertEquals(other.id, w.plantId);
        assertEquals(0, plugin.grid().connected(first.equals("p1") ? p1 : p2).size(),
                "прежнее подключение заменено, а не добавлено");
        assertEquals(1, plugin.grid().connected(other).size());
    }

    @Test
    void connectingToTheSamePlantTwiceIsAnError() {
        Plot p1 = plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        assertNotNull(plugin.grid().connect(w, p1, "Boss"));
    }

    @Test
    void onlyWorkshopsConnectAndOnlyToRealPlants() {
        Plot p1 = plant("p1", 100, 1000);
        Plot shop = addPlot("s", Category.SHOP, 20, 100, -1);
        Plot house = workshop("h", 40);
        assertNotNull(plugin.grid().connect(shop, p1, "Boss"));
        assertNotNull(plugin.grid().connect(house, shop, "Boss"), "магазин - не электростанция");
    }

    @Test
    void autoConnectAllConnectsEveryLooseWorkshop() {
        Plot w1 = workshop("w1", 0);
        Plot w2 = workshop("w2", 20);
        plugin.service().rent(steve, w1, 5);
        plugin.service().rent(steve, w2, 5);
        plant("p1", 100, 1000);
        assertNull(w1.plantId);

        assertEquals(2, plugin.grid().autoConnectAll("Boss"));
        assertEquals("p1", w1.plantId);
        assertEquals("p1", w2.plantId);
        assertEquals(0, plugin.grid().autoConnectAll("Boss"));
    }

    // ------------------------------------------------------------ отключение

    @Test
    void disconnectNeedsAReasonAndStopsTheTariff() {
        Plot p1 = plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        plugin.power().deposit(steve, w, 1000);
        assertEquals(360, w.account.ratePerDay(), 1e-9);

        assertNotNull(plugin.grid().disconnect(w, " ", "Boss"));
        assertEquals("p1", w.plantId);
        assertNull(plugin.grid().disconnect(w, "нарушение правил торговли", "Boss"));

        assertNull(w.plantId);
        assertEquals(0, w.account.ratePerDay(), 1e-9, "тариф не начисляется, пока участок отключён");
        pass(3 * DAY);
        assertEquals(1000, plugin.power().balance(w), 1e-6, "деньги со счёта не уходят");
        assertFalse(plugin.power().isPowered(w));
        assertTrue(burnCancelled(w));
        assertNotNull(p1);
    }

    @Test
    void theManagerSeesWhyTheyAreDisconnected() {
        plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        int before = plugin.warnings().forPlayer(steve.getUniqueId()).size();

        plugin.grid().disconnect(w, "плановые работы на станции", "Boss");

        var warnings = plugin.warnings().forPlayer(steve.getUniqueId());
        assertEquals(before + 1, warnings.size());
        assertTrue(warnings.get(0).text.contains("отключено от электростанции"));
        assertTrue(warnings.get(0).text.contains("плановые работы на станции"));
        assertEquals("плановые работы на станции", plugin.grid().offReason(w));
        assertTrue(plugin.journal().forPlot("w").get(0).reason().contains("плановые работы"));
    }

    @Test
    void restoreReconnectsToThePreviousPlant() {
        Plot p1 = plant("p1", 100, 1000);
        Plot p2 = plant("p2", 120, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        plugin.grid().connect(w, p2, "Boss");
        plugin.grid().disconnect(w, "причина", "Boss");

        assertNull(plugin.grid().restore(w, "Boss"));

        assertEquals("p2", w.plantId);
        assertNull(w.gridReason);
        assertNotNull(plugin.grid().restore(w, "Boss"), "уже подключено");
        assertNotNull(p1);
    }

    @Test
    void reconnectingResumesTheTariff() {
        plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        plugin.power().deposit(steve, w, 1000);
        plugin.grid().disconnect(w, "причина", "Boss");
        plugin.grid().restore(w, "Boss");

        assertEquals(360, w.account.ratePerDay(), 1e-9);
        assertTrue(plugin.power().isPowered(w));
    }

    @Test
    void disconnectionByAdminIsNotInheritedByTheNextTenant() {
        plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        plugin.grid().disconnect(w, "нарушение", "Boss");

        plugin.service().evict(w, new PlotService.Release("Boss", "выселение", Journal.Action.EVICTED, false,
                Plot.RestoreMode.ITEMS_ONLY));

        assertEquals("p1", w.plantId, "здание снова подключено");
        assertNull(w.gridReason);
    }

    @Test
    void connectionBelongsToTheBuildingAndSurvivesAChangeOfManager() {
        Plot p1 = plant("p1", 100, 1000);
        plant("p2", 120, 1000);
        Plot w = workshop("w", 0);
        plugin.grid().connect(w, p1, "Boss");
        plugin.service().rent(steve, w, 5);

        plugin.service().release(steve, w);

        assertEquals("p1", w.plantId);
    }

    @Test
    void stationsReportTheGridReasonInsteadOfAMissingBalance() {
        plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        plugin.power().deposit(steve, w, 1000);
        plugin.grid().disconnect(w, "авария", "Boss");

        assertFalse(plugin.power().useStation(w, "smelter"));
        FurnaceSmeltEvent e = new FurnaceSmeltEvent(server.getWorld("world").getBlockAt(w.box.minX() + 1, 1, 1),
                new ItemStack(Material.IRON_ORE), new ItemStack(Material.IRON_INGOT));
        server.getPluginManager().callEvent(e);
        assertTrue(e.isCancelled());
    }

    // ------------------------------------------------------------ мощность и перегрузка

    @Test
    void plantDemandIsTheSumOfConnectedWorkshops() {
        Plot p1 = plant("p1", 100, 10);
        Plot w1 = workshop("w1", 0);
        Plot w2 = workshop("w2", 20);
        plugin.service().rent(steve, w1, 5);
        plugin.service().rent(steve, w2, 5);
        equip(w1);
        equip(w2);

        assertEquals(7.2, plugin.grid().demand(p1), 1e-9);
        assertEquals(0.72, plugin.grid().load(p1), 1e-9);
    }

    @Test
    void overloadRaisesTheTariffGraduallyOneStepAtATime() {
        Plot p1 = plant("p1", 100, 3);              // мощности не хватает: потребление 3.6
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        plugin.power().deposit(steve, w, 100_000);

        plugin.grid().updatePlant(p1);
        assertEquals(1.02, p1.plantMultiplier, 1e-9);
        assertEquals(360 * 1.02, w.account.ratePerDay(), 1e-9);

        plugin.grid().updatePlant(p1);
        assertEquals(1.04, p1.plantMultiplier, 1e-9);
        assertEquals(360 * 1.04, w.account.ratePerDay(), 1e-9);
    }

    @Test
    void multiplierStopsAtTheConfiguredMaximum() {
        plugin.getConfig().set("grid.max-multiplier", 1.05);
        Plot p1 = plant("p1", 100, 1);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        for (int i = 0; i < 20; i++) {
            plugin.grid().updatePlant(p1);
        }
        assertEquals(1.05, p1.plantMultiplier, 1e-9);
    }

    @Test
    void tariffReturnsToNormalWhenLoadDropsOrCapacityGrows() {
        Plot p1 = plant("p1", 100, 3);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        for (int i = 0; i < 3; i++) {
            plugin.grid().updatePlant(p1);
        }
        assertEquals(1.06, p1.plantMultiplier, 1e-9);

        assertNull(plugin.grid().setCapacity(p1, 100, "Boss"));
        plugin.grid().updatePlant(p1);
        assertEquals(1.04, p1.plantMultiplier, 1e-9);
        plugin.grid().updatePlant(p1);
        plugin.grid().updatePlant(p1);
        assertEquals(1.0, p1.plantMultiplier, 1e-9);
        assertEquals(360, w.account.ratePerDay(), 1e-9);
    }

    @Test
    void oldMultiplierAppliesUntilTheMomentOfChange() {
        Plot p1 = plant("p1", 100, 3);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        plugin.power().deposit(steve, w, 10_000);
        pass(DAY / 2);                                     // 180 по тарифу ×1.0
        plugin.grid().updatePlant(p1);                     // дальше 367.2 в сутки
        assertEquals(10_000 - 180, plugin.power().balance(w), 1e-6);
        pass(DAY / 2);
        assertEquals(10_000 - 180 - 183.6, plugin.power().balance(w), 1e-6);
    }

    @Test
    void ownersAreToldWhenTheSurchargeStartsAndEnds() {
        Plot p1 = plant("p1", 100, 3);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        int base = plugin.warnings().forPlayer(steve.getUniqueId()).size();

        plugin.grid().updatePlant(p1);
        plugin.grid().updatePlant(p1);
        assertEquals(base + 1, plugin.warnings().forPlayer(steve.getUniqueId()).size(), "сообщение только в начале");
        assertTrue(plugin.warnings().forPlayer(steve.getUniqueId()).get(0).text.contains("превышена"));

        plugin.grid().setCapacity(p1, 1000, "Boss");
        plugin.grid().updatePlant(p1);
        plugin.grid().updatePlant(p1);
        assertEquals(base + 2, plugin.warnings().forPlayer(steve.getUniqueId()).size());
        assertTrue(plugin.warnings().forPlayer(steve.getUniqueId()).get(0).text.contains("в норме"));
    }

    @Test
    void multipliersOfDifferentPlantsAreIndependent() {
        Plot busy = plant("busy", 100, 1);
        Plot calm = plant("calm", 120, 1000);
        Plot w1 = workshop("w1", 0);
        Plot w2 = workshop("w2", 20);
        plugin.service().rent(steve, w1, 5);
        plugin.service().rent(steve, w2, 5);
        plugin.grid().connect(w1, busy, "Boss");
        plugin.grid().connect(w2, calm, "Boss");
        equip(w1);
        equip(w2);

        plugin.grid().updateAll();

        assertEquals(1.02, busy.plantMultiplier, 1e-9);
        assertEquals(1.0, calm.plantMultiplier, 1e-9);
        assertEquals(360 * 1.02, w1.account.ratePerDay(), 1e-9);
        assertEquals(360, w2.account.ratePerDay(), 1e-9);
    }

    @Test
    void capacityValidation() {
        Plot p1 = plant("p1", 100, 1000);
        Plot shop = addPlot("s", Category.SHOP, 20, 100, -1);
        assertNotNull(plugin.grid().setCapacity(p1, -5, "Boss"));
        assertNotNull(plugin.grid().setCapacity(shop, 100, "Boss"));
        assertNull(plugin.grid().setCapacity(p1, 0, "Boss"));
        assertEquals(0, p1.plantCapacity, 1e-9);
    }

    @Test
    void adminSetsCapacityByCommand() {
        Plot p1 = plant("p1", 100, 1000);
        boss.performCommand("district grid capacity p1 2500");
        assertEquals(2500, p1.plantCapacity, 1e-9);
        boss.performCommand("district grid capacity p1 abc");
        assertEquals(2500, p1.plantCapacity, 1e-9);
    }

    @Test
    void usageModePriceIncludesTheStationMultiplier() {
        Plot p1 = plant("p1", 100, 1);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        boss.performCommand("district power admin mode USAGE");
        plugin.grid().updatePlant(p1);
        plugin.grid().updatePlant(p1);                      // ×1.04
        plugin.power().deposit(steve, w, 100);

        assertEquals(1.0 * 1.04, plugin.power().operationPrice(w, "smelter"), 1e-9);
        assertTrue(plugin.power().useStation(w, "smelter"));
        assertEquals(100 - 1.04, plugin.power().balance(w), 1e-9);
    }

    // ------------------------------------------------------------ потребление в V

    @Test
    void consumptionIsRecordedInUnitsAndMoney() {
        plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        plugin.power().deposit(steve, w, 1000);

        pass(DAY / 2);

        var sum = plugin.power().consumption().total("w");
        assertEquals(1.8, sum.units(), 1e-6, "3.6 V в сутки за полсуток");
        assertEquals(180, sum.money(), 1e-6);
    }

    @Test
    void unitsDoNotDependOnThePrice() {
        plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        boss.performCommand("district grid price 500");
        plugin.power().deposit(steve, w, 100_000);

        pass(DAY);

        var sum = plugin.power().consumption().total("w");
        assertEquals(3.6, sum.units(), 1e-6);
        assertEquals(1800, sum.money(), 1e-6, "цена за 1 V выросла, а V остались прежними");
    }

    @Test
    void consumptionStopsWhenTheAccountRunsOut() {
        plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        plugin.power().deposit(steve, w, 90);              // на четверть суток

        pass(DAY);

        var sum = plugin.power().consumption().total("w");
        assertEquals(0.9, sum.units(), 1e-6);
        assertEquals(90, sum.money(), 1e-6);
    }

    @Test
    void surchargeIncreasesMoneyButNotUnits() {
        Plot p1 = plant("p1", 100, 3);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        plugin.power().deposit(steve, w, 100_000);
        plugin.grid().updatePlant(p1);                      // ×1.02

        pass(DAY);

        var sum = plugin.power().consumption().total("w");
        assertEquals(3.6, sum.units(), 1e-6);
        assertEquals(367.2, sum.money(), 1e-6);
    }

    @Test
    void usageOperationsAreRecordedInUnits() {
        plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        boss.performCommand("district power admin mode USAGE");
        plugin.power().deposit(steve, w, 100);

        plugin.power().useStation(w, "enchanting");

        var sum = plugin.power().consumption().total("w");
        assertEquals(4.0, sum.units(), 1e-9, "коэффициент станка - его V за операцию");
        assertEquals(4.0, sum.money(), 1e-9);
    }

    // ------------------------------------------------------------ районы и отчёты

    @Test
    void districtReportAddsUpItsWorkshops() {
        plant("p1", 100, 1000);
        Plot w1 = workshop("w1", 0);
        Plot w2 = workshop("w2", 20);
        plugin.service().rent(steve, w1, 5);
        plugin.service().rent(steve, w2, 5);
        equip(w1);
        equip(w2);
        plugin.power().deposit(steve, w1, 1000);
        plugin.power().deposit(steve, w2, 1000);
        boss.performCommand("district area create Север");
        boss.performCommand("district area add Север w1 w2");

        pass(DAY / 2);

        var report = plugin.grid().report(plugin.grid().plotsOf(plugin.districts().get("север")));
        assertEquals(2, report.plots());
        assertEquals(7.2, report.demand(), 1e-9);
        assertEquals(3.6, report.day().units(), 1e-6);
        assertEquals(360, report.day().money(), 1e-6);
    }

    @Test
    void districtCommandsManageMembership() {
        Plot w = workshop("w", 0);
        boss.performCommand("district area create Центр");
        boss.performCommand("district area add Центр w");
        assertEquals("Центр", plugin.districts().of("w").name);
        boss.performCommand("district area remove w");
        assertNull(plugin.districts().of("w"));
        boss.performCommand("district area delete Центр");
        assertNull(plugin.districts().get("Центр"));
        assertNotNull(w);
    }

    @Test
    void disconnectingADistrictCutsEveryWorkshopInIt() {
        Plot p1 = plant("p1", 100, 1000);
        Plot w1 = workshop("w1", 0);
        Plot w2 = workshop("w2", 20);
        plugin.service().rent(steve, w1, 5);
        plugin.service().rent(steve, w2, 5);
        boss.performCommand("district area create Юг");
        boss.performCommand("district area add Юг w1 w2");

        boss.performCommand("district grid disconnect area:Юг ремонт магистрали");

        assertNull(w1.plantId);
        assertNull(w2.plantId);
        assertEquals("ремонт магистрали", plugin.grid().offReason(w1));

        boss.performCommand("district grid restore area:Юг");
        assertEquals("p1", w1.plantId);
        assertEquals("p1", w2.plantId);
        assertNotNull(p1);
    }

    @Test
    void connectCommandMovesWholeDistrictToAPlant() {
        plant("p1", 100, 1000);
        Plot p2 = plant("p2", 120, 1000);
        Plot w1 = workshop("w1", 0);
        plugin.service().rent(steve, w1, 5);
        boss.performCommand("district area create Юг");
        boss.performCommand("district area add Юг w1");

        boss.performCommand("district grid connect area:Юг p2");

        assertEquals("p2", w1.plantId);
        assertEquals(1, plugin.grid().connected(p2).size());
    }

    @Test
    void reportAndInfoCommandsPrintSomething() {
        Plot p1 = plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);
        boss.performCommand("district area create Центр");
        boss.performCommand("district area add Центр w");
        drain(boss);

        boss.performCommand("district grid info");
        String info = drain(boss);
        assertTrue(info.contains("p1"), info);
        assertTrue(info.contains("1000.0"), info);

        boss.performCommand("district grid report");
        String report = drain(boss);
        assertTrue(report.contains("Центр"), report);
        assertTrue(report.contains("без района"), report);
        assertNotNull(p1);
    }

    @Test
    void priceCommandIsTheTariffBase() {
        plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        equip(w);

        boss.performCommand("district grid price 200");

        assertEquals(200, plugin.power().base(), 1e-9);
        assertEquals(720, w.account.ratePerDay(), 1e-9);
        boss.performCommand("district grid price reset");
        assertEquals(360, w.account.ratePerDay(), 1e-9);
    }

    // ------------------------------------------------------------ права

    @Test
    void gridCommandsNeedTheGridPermission() {
        Plot p1 = plant("p1", 100, 1000);
        Plot w = workshop("w", 0);
        plugin.service().rent(steve, w, 5);
        for (String cmd : new String[] {"grid info", "grid capacity p1 5", "grid disconnect w причина", "grid autoconnect",
                "area create X", "power admin base 1", "power calc smelter:1"}) {
            steve.performCommand("district " + cmd);
            assertTrue(drain(steve).contains("Недостаточно прав"), cmd);
        }
        assertEquals(1000, p1.plantCapacity, 1e-9);
        assertEquals("p1", w.plantId);
    }

    @Test
    void bankPermissionDoesNotGiveGridPermission() {
        PlayerMock banker = server.addPlayer("Banker");
        banker.addAttachment(plugin, "industryreborn.bank", true);
        plant("p1", 100, 1000);

        banker.performCommand("district grid info");
        assertTrue(drain(banker).contains("Недостаточно прав"));
        banker.performCommand("district treasury");
        assertTrue(drain(banker).contains("Казна"));
    }

    @Test
    void gridPermissionDoesNotGiveBankPermission() {
        PlayerMock operator = server.addPlayer("Operator");
        operator.addAttachment(plugin, "industryreborn.grid", true);
        plant("p1", 100, 1000);

        operator.performCommand("district grid info");
        assertTrue(drain(operator).contains("Электросеть"));
        operator.performCommand("district treasury");
        assertTrue(drain(operator).contains("Недостаточно прав"));
        operator.performCommand("district bank admin penalty 50");
        assertNull(plugin.bank().settings().seizurePenalty());
    }
}
