package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.StorageEntry;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.PlotService;
import ru.buildmaster.support.PluginTestBase;

/** Производственные линии и улучшения мастерской. */
class FactoryIntegrationTest extends PluginTestBase {

    private final AtomicLong wall = new AtomicLong(1_000_000);
    private PlayerMock steve;
    private PlayerMock alex;
    private PlayerMock boss;
    private Plot workshop;

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
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.workshop", 0);
        plugin.power().setTimeSource(wall::get);
        steve = player("Steve", 1_000_000);
        alex = player("Alex", 1_000_000);
        boss = admin("Boss");
        workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        plugin.service().rent(steve, workshop, 10);
        place(Material.FURNACE, 1);
        place(Material.BLAST_FURNACE, 2);
        place(Material.ANVIL, 3);
        assertTrue(plugin.power().deposit(steve, workshop, 200_000));
        plugin.factory().tick();       // запоминает начальный момент
    }

    private void place(Material m, int dx) {
        Block b = server.getWorld("world").getBlockAt(dx, 1, 1);
        b.setType(m);
        server.getPluginManager().callEvent(new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0),
                new ItemStack(m), steve, true, EquipmentSlot.HAND));
    }

    private void pass(long seconds) {
        wall.addAndGet(seconds * 1000);
        plugin.power().tick();
        plugin.factory().tick();
    }

    private void giveItems(PlayerMock p, Material m, int amount) {
        p.getInventory().addItem(new ItemStack(m, amount));
    }

    private static int count(PlayerMock p, Material m) {
        int n = 0;
        for (ItemStack s : p.getInventory().getContents()) {
            if (s != null && s.getType() == m) {
                n += s.getAmount();
            }
        }
        return n;
    }

    private int stored(UUID id, Material m, StorageEntry.State state) {
        int n = 0;
        for (StorageEntry e : plugin.storage().entries(id)) {
            if (e.state != state) {
                continue;
            }
            for (byte[] b : e.stacks) {
                ItemStack s = ItemStack.deserializeBytes(b);
                if (s.getType() == m) {
                    n += s.getAmount();
                }
            }
        }
        return n;
    }

    private Plot.Line ironLine() {
        return workshop.lines.get("iron-ingots");
    }

    private void installIron() {
        assertNull(plugin.factory().install(steve, workshop, "iron-ingots"));
    }

    private void runIron(int ore) {
        installIron();
        steve.getInventory().clear();
        giveItems(steve, Material.IRON_ORE, ore);
        assertNull(plugin.factory().load(steve, workshop, "iron-ingots", 0));
    }

    // ------------------------------------------------------------ установка линии

    @Test
    void installPaysFromTheAccountIntoTheTreasury() {
        double account = plugin.power().balance(workshop);
        double treasury = plugin.treasury().balance();

        installIron();

        assertNotNull(ironLine());
        assertEquals(account - 500, plugin.power().balance(workshop), 0.1);
        assertEquals(treasury + 500, plugin.treasury().balance(), 0.1);
        assertEquals(Journal.Action.FACTORY, plugin.journal().forPlot("w").get(0).action());
    }

    @Test
    void installNeedsMoneyOnTheAccountAndChangesNothingOtherwise() {
        plugin.power().touch(workshop);
        workshop.account.restore(100, workshop.account.ratePerDay(), workshop.account.updatedAtSec());
        double treasury = plugin.treasury().balance();

        String error = plugin.factory().install(steve, workshop, "iron-ingots");

        assertNotNull(error);
        assertTrue(error.contains("не хватает денег"), error);
        assertNull(ironLine());
        assertEquals(treasury, plugin.treasury().balance(), 1e-9);
    }

    @Test
    void installNeedsTheMatchingStation() {
        plugin.getConfig().set("upgrades.base.max-lines", 3);
        Block anvil = server.getWorld("world").getBlockAt(3, 1, 1);
        server.getPluginManager().callEvent(new org.bukkit.event.block.BlockBreakEvent(anvil, steve));
        String error = plugin.factory().install(steve, workshop, "tool-repair");
        assertNotNull(error);
        assertTrue(error.contains("нужен станок"), error);
    }

    @Test
    void theNumberOfLinesDependsOnTheLevel() {
        installIron();
        String error = plugin.factory().install(steve, workshop, "glass");
        assertNotNull(error);
        assertTrue(error.contains("слоты линий заняты"), error);

        assertNull(plugin.factory().upgrade(steve, workshop));      // уровень 1: две линии
        assertNull(plugin.factory().install(steve, workshop, "glass"));
        assertNotNull(plugin.factory().install(steve, workshop, "stone"));
    }

    @Test
    void duplicatesUnknownLinesAndForeignManagersAreRefused() {
        installIron();
        assertNotNull(plugin.factory().install(steve, workshop, "iron-ingots"));
        assertNotNull(plugin.factory().install(steve, workshop, "teleporter"));
        assertNotNull(plugin.factory().install(alex, workshop, "glass"));
        assertEquals(1, workshop.lines.size());
    }

    @Test
    void onlyWorkshopsHaveAFactory() {
        Plot shop = addPlot("s", Category.SHOP, 20, 100, -1);
        plugin.service().buy(steve, shop);
        assertNotNull(plugin.factory().install(steve, shop, "iron-ingots"));
    }

    // ------------------------------------------------------------ сырьё

    @Test
    void loadingTakesOrdinaryItemsFromTheInventory() {
        installIron();
        steve.getInventory().clear();
        giveItems(steve, Material.IRON_ORE, 100);

        assertNull(plugin.factory().load(steve, workshop, "iron-ingots", 0));

        assertEquals(100, ironLine().input);
        assertEquals(0, count(steve, Material.IRON_ORE));
    }

    @Test
    void rawOreItselfIsAcceptedAsRawMaterial() {
        installIron();
        steve.getInventory().clear();
        giveItems(steve, Material.RAW_IRON, 40);

        assertNull(plugin.factory().load(steve, workshop, "iron-ingots", 0));

        assertEquals(40, ironLine().input);
        assertEquals(0, count(steve, Material.RAW_IRON));
    }

    @Test
    void oreBlocksAndRawOreMixInOneStock() {
        installIron();
        steve.getInventory().clear();
        giveItems(steve, Material.RAW_IRON, 10);
        giveItems(steve, Material.IRON_ORE, 5);
        giveItems(steve, Material.DEEPSLATE_IRON_ORE, 3);
        giveItems(steve, Material.GOLD_ORE, 7);

        assertNull(plugin.factory().load(steve, workshop, "iron-ingots", 0));

        assertEquals(18, ironLine().input, "золото не подходит для железной линии");
        assertEquals(7, count(steve, Material.GOLD_ORE));
        assertEquals(0, count(steve, Material.RAW_IRON) + count(steve, Material.IRON_ORE) + count(steve, Material.DEEPSLATE_IRON_ORE));
    }

    @Test
    void loadingTakesThePrimaryRawMaterialFirstWhenLimited() {
        installIron();
        steve.getInventory().clear();
        giveItems(steve, Material.IRON_ORE, 10);
        giveItems(steve, Material.RAW_IRON, 10);

        assertNull(plugin.factory().load(steve, workshop, "iron-ingots", 12));

        assertEquals(12, ironLine().input);
        assertEquals(0, count(steve, Material.RAW_IRON));
        assertEquals(8, count(steve, Material.IRON_ORE));
    }

    @Test
    void renamedRawOreIsNotAccepted() {
        installIron();
        steve.getInventory().clear();
        ItemStack special = new ItemStack(Material.RAW_IRON, 10);
        special.editMeta(m -> m.displayName(net.kyori.adventure.text.Component.text("Особая")));
        steve.getInventory().addItem(special);

        String error = plugin.factory().load(steve, workshop, "iron-ingots", 0);

        assertNotNull(error);
        assertTrue(error.contains("RAW_IRON"), error);
        assertEquals(10, count(steve, Material.RAW_IRON));
    }

    @Test
    void goldAndCopperLinesAcceptTheirRawOres() {
        var gold = plugin.production().get("gold-ingots");
        var copper = plugin.production().get("copper-ingots");
        assertTrue(gold.accepts(Material.RAW_GOLD) && gold.accepts(Material.GOLD_ORE) && gold.accepts(Material.DEEPSLATE_GOLD_ORE));
        assertTrue(copper.accepts(Material.RAW_COPPER) && copper.accepts(Material.COPPER_ORE));
        assertEquals(Material.RAW_GOLD, gold.input(), "основное сырьё - первое в списке");
        assertFalse(gold.accepts(Material.RAW_IRON));
    }

    @Test
    void aLineWithASingleInputKeyStillWorks() {
        plugin.getConfig().set("production.lines.legacy.title", "Старая");
        plugin.getConfig().set("production.lines.legacy.station", "smelter");
        plugin.getConfig().set("production.lines.legacy.input", "COBBLESTONE");
        plugin.getConfig().set("production.lines.legacy.output", "STONE");
        var line = plugin.production().get("legacy");
        assertNotNull(line);
        assertEquals(java.util.List.of(Material.COBBLESTONE), line.inputs());
    }

    @Test
    void loadingRespectsTheRequestedAmountAndTheStockCapacity() {
        installIron();
        steve.getInventory().clear();
        giveItems(steve, Material.IRON_ORE, 500);

        assertNull(plugin.factory().load(steve, workshop, "iron-ingots", 50));
        assertEquals(50, ironLine().input);

        assertNull(plugin.factory().load(steve, workshop, "iron-ingots", 0));
        assertEquals(256, ironLine().input, "склад линии - 256 предметов");
        assertEquals(500 - 256, count(steve, Material.IRON_ORE));
        assertNotNull(plugin.factory().load(steve, workshop, "iron-ingots", 0), "склад полон");
    }

    @Test
    void renamedOrEnchantedItemsAreNotAccepted() {
        installIron();
        steve.getInventory().clear();
        ItemStack special = new ItemStack(Material.IRON_ORE, 10);
        special.editMeta(m -> m.displayName(net.kyori.adventure.text.Component.text("Особая руда")));
        steve.getInventory().addItem(special);

        String error = plugin.factory().load(steve, workshop, "iron-ingots", 0);

        assertNotNull(error);
        assertEquals(0, ironLine().input);
        assertEquals(10, count(steve, Material.IRON_ORE), "предметы остались у игрока");
    }

    @Test
    void loadingNeedsTheRightMaterial() {
        installIron();
        steve.getInventory().clear();
        giveItems(steve, Material.GOLD_ORE, 10);
        assertNotNull(plugin.factory().load(steve, workshop, "iron-ingots", 0));
    }

    // ------------------------------------------------------------ производство

    @Test
    void productionConvertsRawMaterialIntoProductsOverTime() {
        runIron(20);                       // 10 с на партию / 2 печи = 5 с
        pass(1);
        pass(49);                          // 50 с -> 10 партий
        assertEquals(10, ironLine().output);
        assertEquals(10, ironLine().input, "сырьё потребляется");

        pass(50);
        assertEquals(20, ironLine().output);
        assertEquals(0, ironLine().input);
    }

    @Test
    void nothingIsProducedWithoutRawMaterial() {
        installIron();
        pass(1);
        pass(1000);
        assertEquals(0, ironLine().output);
        assertEquals(0, ironLine().input);
    }

    @Test
    void idleTimeIsNotBankedForLater() {
        installIron();
        pass(1);
        pass(1000);                        // линия простаивала
        steve.getInventory().clear();
        giveItems(steve, Material.IRON_ORE, 10);
        plugin.factory().load(steve, workshop, "iron-ingots", 0);

        pass(1);
        assertEquals(0, ironLine().output, "накопленного за простой времени нет");
        pass(4);
        assertEquals(1, ironLine().output);
    }

    @Test
    void theBufferLimitsAccumulation() {
        plugin.getConfig().set("production.buffer-batches", 3);
        runIron(20);
        pass(1);
        pass(500);

        assertEquals(3, ironLine().output, "буфер продукции полон");
        assertEquals(17, ironLine().input, "сырьё не расходуется, пока некуда класть продукцию");
    }

    @Test
    void multiItemRecipeConsumesTheWholeInput() {
        plugin.getConfig().set("upgrades.base.max-lines", 3);
        assertNull(plugin.factory().install(steve, workshop, "tool-repair"));    // 9 слитков -> 1 блок, станок anvil
        steve.getInventory().clear();
        giveItems(steve, Material.IRON_INGOT, 20);
        plugin.factory().load(steve, workshop, "tool-repair", 0);

        pass(1);
        pass(100);

        Plot.Line line = workshop.lines.get("tool-repair");
        assertEquals(2, line.output, "20 слитков - две партии по 9");
        assertEquals(2, line.input, "остаток меньше партии лежит на складе");
    }

    @Test
    void moreStationsProduceFaster() {
        place(Material.SMOKER, 4);         // третья печь: 10/3 с
        runIron(30);
        pass(1);
        pass(29);                          // 30 с -> 9 партий
        assertEquals(9, ironLine().output);
    }

    @Test
    void timeFactorSpeedsLinesUp() {
        plugin.getConfig().set("production.time-factor", 0.1);
        runIron(20);                       // 0.5 с на партию
        pass(1);
        pass(9);
        assertEquals(20, ironLine().output);
    }

    @Test
    void linesStandStillWithoutElectricityAndDoNotBankTheDowntime() {
        runIron(40);
        pass(1);
        pass(24);                          // 5 партий
        assertEquals(5, ironLine().output);

        addPlot("p1", Category.POWER_PLANT, 100, -1, -1).plantCapacity = 1000;
        plugin.grid().connect(workshop, plugin.plots().get("p1"), "Boss");
        plugin.grid().disconnect(workshop, "авария", "Boss");
        pass(100_000);
        assertEquals(5, ironLine().output, "без электричества линия стоит");

        plugin.grid().restore(workshop, "Boss");
        pass(1);
        assertEquals(5, ironLine().output, "время простоя не засчитано");
        pass(5);
        assertTrue(ironLine().output > 5);
    }

    @Test
    void usageModeChargesEveryBatchAndStallsWhenTheAccountIsEmpty() {
        runIron(20);
        boss.performCommand("district power admin mode USAGE");
        plugin.power().touch(workshop);
        workshop.account.restore(3, 0, workshop.account.updatedAtSec());

        pass(1);
        pass(200);

        assertEquals(3, ironLine().output, "денег хватило на три партии");
        assertEquals(17, ironLine().input);

        plugin.power().deposit(steve, workshop, 1000);
        pass(1);
        pass(200);
        assertEquals(20, ironLine().output);
    }

    @Test
    void operationsOfTheLinesAreCountedInTheStatistics() {
        runIron(10);
        pass(1);
        pass(50);
        assertEquals(10, plugin.power().stats().byType("w").get("smelter").operations());
    }

    // ------------------------------------------------------------ продукция

    @Test
    void collectingMovesProductsToTheManager() {
        runIron(10);
        pass(1);
        pass(50);
        steve.getInventory().clear();

        assertNull(plugin.factory().collect(steve, workshop, "iron-ingots"));

        assertEquals(10, count(steve, Material.IRON_INGOT));
        assertEquals(0, ironLine().output);
        assertNotNull(plugin.factory().collect(steve, workshop, "iron-ingots"), "продукции больше нет");
    }

    @Test
    void collectingWithoutALineNameTakesEverything() {
        plugin.getConfig().set("upgrades.base.max-lines", 3);
        runIron(4);
        assertNull(plugin.factory().install(steve, workshop, "glass"));
        steve.getInventory().clear();
        giveItems(steve, Material.SAND, 6);
        plugin.factory().load(steve, workshop, "glass", 0);
        pass(1);
        pass(100);
        steve.getInventory().clear();

        assertNull(plugin.factory().collect(steve, workshop, null));

        assertEquals(4, count(steve, Material.IRON_INGOT));
        assertEquals(6, count(steve, Material.GLASS));
    }

    @Test
    void onlyTheManagerCanLoadOrCollect() {
        runIron(10);
        pass(1);
        pass(50);
        giveItems(alex, Material.IRON_ORE, 10);
        assertNotNull(plugin.factory().load(alex, workshop, "iron-ingots", 0));
        assertNotNull(plugin.factory().collect(alex, workshop, null));
        assertEquals(10, ironLine().output);
    }

    @Test
    void removingALineReturnsRawMaterialAndProductsButNotTheInstallPrice() {
        runIron(20);
        pass(1);
        pass(25);                          // 5 партий
        steve.getInventory().clear();
        double account = plugin.power().balance(workshop);

        assertNull(plugin.factory().remove(steve, workshop, "iron-ingots"));

        assertNull(ironLine());
        assertEquals(15, count(steve, Material.RAW_IRON));
        assertEquals(5, count(steve, Material.IRON_INGOT));
        assertEquals(account, plugin.power().balance(workshop), 0.1, "стоимость установки не возвращается");
    }

    // ------------------------------------------------------------ улучшения

    @Test
    void upgradePaysFromTheAccountAndAppliesTheEffects() {
        double account = plugin.power().balance(workshop);
        double treasury = plugin.treasury().balance();

        assertNull(plugin.factory().upgrade(steve, workshop));

        assertEquals(1, workshop.level);
        assertEquals(account - 3000, plugin.power().balance(workshop), 0.1);
        assertEquals(treasury + 3000, plugin.treasury().balance(), 0.1);
        assertEquals(2, plugin.upgrades().of(workshop).maxLines());
        assertEquals(512, plugin.factory().stockCapacity(workshop));
        assertEquals(Journal.Action.UPGRADE, plugin.journal().forPlot("w").get(0).action());
    }

    @Test
    void upgradeNeedsMoneyAndStopsAtTheMaximum() {
        plugin.power().touch(workshop);
        workshop.account.restore(100, workshop.account.ratePerDay(), workshop.account.updatedAtSec());
        assertNotNull(plugin.factory().upgrade(steve, workshop));
        assertEquals(0, workshop.level);

        workshop.account.restore(1_000_000, workshop.account.ratePerDay(), workshop.account.updatedAtSec());
        assertNull(plugin.factory().upgrade(steve, workshop));
        assertNull(plugin.factory().upgrade(steve, workshop));
        assertNull(plugin.factory().upgrade(steve, workshop));
        assertEquals(3, workshop.level);
        assertNotNull(plugin.factory().upgrade(steve, workshop), "максимальный уровень");
        assertEquals(3, workshop.level);
    }

    @Test
    void onlyTheManagerCanUpgrade() {
        assertNotNull(plugin.factory().upgrade(alex, workshop));
        assertEquals(0, workshop.level);
    }

    @Test
    void upgradesSpeedUpTheLines() {
        runIron(40);
        plugin.factory().upgrade(steve, workshop);       // скорость ×1.25: партия 4 с
        pass(1);
        pass(39);                                         // 40 с -> 10 партий (без улучшения было бы 8)
        assertEquals(10, ironLine().output);
    }

    @Test
    void upgradesEnlargeStockAndBuffer() {
        installIron();
        steve.getInventory().clear();
        giveItems(steve, Material.IRON_ORE, 1000);
        plugin.factory().upgrade(steve, workshop);        // склад ×2
        plugin.factory().load(steve, workshop, "iron-ingots", 0);
        assertEquals(512, ironLine().input);
        assertEquals(128, plugin.factory().bufferCapacity(workshop, plugin.production().get("iron-ingots")));
    }

    @Test
    void upgradesEnlargeTheOrderQueueAndSpeedUpOrders() {
        plugin.getConfig().set("services.max-active-orders-per-workshop", 1);
        assertNull(plugin.orders().configureOffer(steve, workshop, "smelt", 1.0, true));
        plugin.catalog().setSmeltLookup(in -> new ru.buildmaster.service.ServiceCatalog.SmeltResult(new ItemStack(Material.GLASS), 10));
        alex.getInventory().setItemInMainHand(new ItemStack(Material.SAND, 10));
        assertNull(plugin.orders().create(alex, workshop, "smelt", false));
        var order = plugin.orders().ofClient(alex.getUniqueId()).get(0);
        double slow = plugin.orders().etaSeconds(order);          // 10 предметов * 10 с / 2 печи = 50
        assertEquals(50, slow, 1e-6);

        PlayerMock mia = player("Mia", 10_000);
        mia.getInventory().setItemInMainHand(new ItemStack(Material.SAND, 1));
        assertNotNull(plugin.orders().create(mia, workshop, "smelt", false), "очередь на уровне 0 - один заказ");

        plugin.factory().upgrade(steve, workshop);                 // очередь +10, скорость ×1.25
        assertEquals(40, plugin.orders().etaSeconds(order), 1e-6);
        assertNull(plugin.orders().create(mia, workshop, "smelt", false));
    }

    // ------------------------------------------------------------ закрытие мастерской

    @Test
    void releasingReturnsStockAndProductsAndResetsTheUpgrades() {
        plugin.factory().upgrade(steve, workshop);
        runIron(20);
        pass(1);
        pass(25);
        steve.getInventory().clear();

        plugin.service().release(steve, workshop);

        // после улучшения до уровня 1 партия занимает 4 с: за 26 с готово 6 партий
        assertEquals(14, count(steve, Material.RAW_IRON) + stored(steve.getUniqueId(), Material.RAW_IRON, StorageEntry.State.AVAILABLE));
        assertEquals(6, count(steve, Material.IRON_INGOT) + stored(steve.getUniqueId(), Material.IRON_INGOT, StorageEntry.State.AVAILABLE));
        assertTrue(workshop.lines.isEmpty());
        assertEquals(0, workshop.level, "улучшения принадлежат участку, при смене управляющего сбрасываются");
    }

    @Test
    void seizureArrestsTheProductionStock() {
        runIron(20);
        pass(1);
        pass(25);
        steve.getInventory().clear();

        plugin.service().evict(workshop, new PlotService.Release("Boss", "нарушение", Journal.Action.SEIZED, true,
                Plot.RestoreMode.ITEMS_ONLY));

        assertEquals(0, count(steve, Material.IRON_ORE), "в инвентарь не выдаётся");
        assertEquals(15, stored(steve.getUniqueId(), Material.RAW_IRON, StorageEntry.State.ARRESTED));
        assertEquals(5, stored(steve.getUniqueId(), Material.IRON_INGOT, StorageEntry.State.ARRESTED));
    }

    @Test
    void anOfflineManagersItemsWaitInTheStorage() {
        runIron(10);
        UUID id = steve.getUniqueId();
        steve.getInventory().clear();
        steve.disconnect();

        plugin.service().evict(workshop, new PlotService.Release("Boss", "выселение", Journal.Action.EVICTED, false,
                Plot.RestoreMode.ITEMS_ONLY));

        assertEquals(10, stored(id, Material.RAW_IRON, StorageEntry.State.AVAILABLE));
    }

    @Test
    void theNextTenantStartsWithoutLinesOrLevel() {
        installIron();
        plugin.factory().upgrade(steve, workshop);
        plugin.service().release(steve, workshop);
        plugin.service().rent(alex, workshop, 3);
        assertTrue(workshop.lines.isEmpty());
        assertEquals(0, workshop.level);
    }

    // ------------------------------------------------------------ сохранение

    @Test
    void linesAndLevelSurviveARestart() {
        plugin.factory().upgrade(steve, workshop);
        runIron(20);
        pass(1);
        pass(12);
        plugin.plots().save();

        plugin.plots().load();
        Plot loaded = plugin.plots().get("w");

        assertEquals(1, loaded.level);
        Plot.Line line = loaded.lines.get("iron-ingots");
        assertNotNull(line);
        assertEquals(ironLineInput(loaded), line.input);
        assertTrue(line.output > 0);
    }

    private static int ironLineInput(Plot p) {
        return p.lines.get("iron-ingots").input;
    }

    // ------------------------------------------------------------ команды и меню

    @Test
    void commandsDriveTheWholeCycle() {
        steve.performCommand("district factory install w iron-ingots");
        assertNotNull(ironLine());
        steve.getInventory().clear();
        giveItems(steve, Material.IRON_ORE, 30);
        steve.performCommand("district factory load w iron-ingots 20");
        assertEquals(20, ironLine().input);
        steve.performCommand("district factory load w iron-ingots all");
        assertEquals(30, ironLine().input);
        pass(1);
        pass(50);
        steve.performCommand("district factory collect w");
        assertEquals(10, count(steve, Material.IRON_INGOT));
        steve.performCommand("district factory upgrade w");
        assertEquals(1, workshop.level);
        steve.performCommand("district factory remove w iron-ingots");
        assertNull(ironLine());
    }

    @Test
    void infoAndLevelsCommandsPrintTheNumbers() {
        installIron();
        drain(steve);
        steve.performCommand("district factory info w");
        String info = drain(steve);
        assertTrue(info.contains("iron-ingots"), info);
        assertTrue(info.contains("0/256"), info);

        steve.performCommand("district factory levels");
        String levels = drain(steve);
        assertTrue(levels.contains("3000"), levels);
        assertTrue(levels.contains("20000"), levels);
    }

    @Test
    void badCommandInputIsRejected() {
        installIron();
        steve.performCommand("district factory load w iron-ingots abc");
        steve.performCommand("district factory load w iron-ingots -5");
        steve.performCommand("district factory install w");
        assertEquals(0, ironLine().input);
        alex.performCommand("district factory upgrade w");
        assertEquals(0, workshop.level);
    }

    @Test
    void menuInstallsALineAndUpgrades() {
        new ru.buildmaster.gui.FactoryMenu(plugin, steve, workshop, null).open();
        steve.simulateInventoryClick(10);
        assertNotNull(workshop.lines.get("iron-ingots"));

        new ru.buildmaster.gui.FactoryMenu(plugin, steve, workshop, null).open();
        steve.simulateInventoryClick(4);
        assertEquals(1, workshop.level);
    }

    @Test
    void workshopMenuHasAFactoryButtonForTheManagerOnly() {
        new ru.buildmaster.gui.PlotMenu(plugin, steve, workshop, null).open();
        assertEquals(Material.BLAST_FURNACE, steve.getOpenInventory().getTopInventory().getItem(13).getType());
        new ru.buildmaster.gui.PlotMenu(plugin, alex, workshop, null).open();
        assertNotEquals(Material.BLAST_FURNACE, alex.getOpenInventory().getTopInventory().getItem(13).getType());
    }

    @Test
    void helpersListIsNotLeakedAndListsOfLinesAreCompletable() {
        List<String> lines = plugin.production().lines().stream().map(l -> l.id()).toList();
        assertTrue(lines.containsAll(List.of("iron-ingots", "gold-ingots", "glass", "stone", "tool-repair")));
    }
}
