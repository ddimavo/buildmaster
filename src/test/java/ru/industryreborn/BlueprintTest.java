package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.items.BlueprintService;
import ru.industryreborn.items.ItemRef;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Employee;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.support.PluginTestBase;

/** Чертежи: одноразовое изучение рецепта, защита от дюпа, магазин, хранилище администрации и запрет аукциона. */
class BlueprintTest extends PluginTestBase {

    private PlayerMock steve;      // управляющий мастерской
    private PlayerMock worker;     // работник
    private PlayerMock alex;       // посторонний
    private PlayerMock boss;       // администратор
    private Plot workshop;
    private RecipeBook.Recipe gear;

    private void write(String dir, String name, String text) throws Exception {
        File d = new File(plugin.getDataFolder(), dir);
        d.mkdirs();
        Files.writeString(new File(d, name).toPath(), text, StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() throws Exception {
        write("items", "metal.yml", """
                steel:
                  material: IRON_INGOT
                  name: "Стальной слиток"
                bp_gear:
                  material: MAP
                  name: "<gold>Старый чертёж"
                """);
        write("recipes", "metal.yml", """
                gear_press:
                  station: furnace
                  seconds: 10
                  blueprint_price: 500
                  inputs: [{item: IRON_INGOT, amount: 2}]
                  output: {item: ir:steel, amount: 1}
                alloy:
                  station: blast
                  seconds: 10
                  blueprint_price: 900
                  blueprint_item: ir:bp_gear
                  inputs: [{item: COAL, amount: 2}]
                  output: {item: ir:steel, amount: 3}
                free_one:
                  station: furnace
                  seconds: 10
                  free: true
                  blueprint_price: 100
                  inputs: [{item: COAL, amount: 1}]
                  output: {item: ir:steel, amount: 1}
                """);
        assertTrue(plugin.reloadCustomContent().isEmpty());
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.workshop", 0);
        server.getWorld("world").loadChunk(0, 0);
        steve = player("Steve", 100_000);
        worker = player("Worker", 100_000);
        alex = player("Alex", 100_000);
        boss = admin("Boss");
        workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        assertTrue(plugin.service().rent(steve, workshop, 10));
        var field = ru.industryreborn.service.JobService.class.getDeclaredField("employees");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Employee> employees = (List<Employee>) field.get(plugin.jobs());
        employees.add(new Employee(worker.getUniqueId(), "Worker", "w", 1, System.currentTimeMillis()));
        gear = plugin.recipes().get("custom:gear_press");
        steve.teleport(new org.bukkit.Location(server.getWorld("world"), 400, 1, 400));
    }

    private ItemStack bp(String recipe) {
        return plugin.blueprints().create(plugin.recipes().get("custom:" + recipe));
    }

    private void hold(PlayerMock p, ItemStack s) {
        p.getInventory().setItemInMainHand(s);
    }

    private int bpCount(PlayerMock p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getContents()) {
            if (plugin.blueprints().isBlueprint(s)) {
                n += s.getAmount();
            }
        }
        return n;
    }

    // ------------------------------------------------------------ предмет

    @Test
    void aBlueprintCarriesTheRecipeAndAUniqueSerial() {
        ItemStack a = bp("gear_press");
        ItemStack b = bp("gear_press");
        var ia = plugin.blueprints().read(a);
        var ib = plugin.blueprints().read(b);
        assertEquals("custom:gear_press", ia.recipeId());
        assertNotEquals(ia.serial(), ib.serial(), "у каждого чертежа свой номер");
        assertFalse(a.isSimilar(b), "предметы не складываются в стопку");
        assertEquals(Material.PAPER, a.getType());
        assertTrue(plugin.blueprints().isBlueprint(a));
        assertFalse(plugin.blueprints().isBlueprint(new ItemStack(Material.PAPER)));
        assertNull(plugin.blueprints().read(null));
        assertNull(plugin.blueprints().create(null));
        assertNull(plugin.blueprints().create(plugin.recipes().get("furnace:IRON_INGOT")), "только рецепты администратора");
    }

    @Test
    void theAppearanceCanComeFromAnItemOfTheRecipe() {
        ItemStack s = bp("alloy");
        assertEquals(Material.MAP, s.getType(), "blueprint_item задаёт основу");
        assertEquals("custom:alloy", plugin.blueprints().read(s).recipeId());
        assertEquals("bp_gear", plugin.customItems().tagOf(s), "метка предмета основы сохраняется");
    }

    // ------------------------------------------------------------ изучение

    @Test
    void theManagerLearnsARecipeAndTheBlueprintIsConsumed() {
        ItemStack s = bp("gear_press");
        String serial = plugin.blueprints().read(s).serial();
        hold(steve, s);
        assertNull(plugin.blueprints().learnFromHand(steve, workshop));
        assertTrue(workshop.learnedRecipes.contains("custom:gear_press"));
        assertTrue(plugin.blueprints().used(serial));
        assertEquals(0, bpCount(steve), "чертёж израсходован");
        assertTrue(plugin.journal().forPlot("w").stream().anyMatch(e -> e.reason() != null && e.reason().contains("gear_press")));
        assertTrue(plugin.factory().recipesFor(workshop, Material.FURNACE).stream().anyMatch(r -> r.id().equals("custom:gear_press")));
    }

    @Test
    void onlyOneBlueprintOfAStackIsConsumed() {
        ItemStack s = bp("gear_press");
        s.setAmount(2);                      // искусственная стопка (серийный номер общий)
        hold(steve, s);
        assertNull(plugin.blueprints().learnFromHand(steve, workshop));
        assertEquals(1, steve.getInventory().getItemInMainHand().getAmount());
        assertNotNull(plugin.blueprints().learnFromHand(steve, workshop), "второй чертёж тоже использован: номер один");
        assertEquals(1, steve.getInventory().getItemInMainHand().getAmount());
    }

    @Test
    void aCopiedBlueprintIsUsedOnlyOnce() {
        ItemStack original = bp("gear_press");
        ItemStack copy = original.clone();
        hold(steve, original);
        assertNull(plugin.blueprints().learnFromHand(steve, workshop));
        workshop.learnedRecipes.clear();                       // допустим, рецепт ещё не изучен у второй мастерской
        hold(steve, copy);
        String error = plugin.blueprints().learnFromHand(steve, workshop);
        assertTrue(error.contains("уже использован"), error);
        assertEquals(1, bpCount(steve), "копия не расходуется и не работает");
        assertFalse(workshop.learnedRecipes.contains("custom:gear_press"));
    }

    @Test
    void workersCanLearnButOutsidersAndAdminsWhoDoNotWorkThereCannot() {
        hold(worker, bp("gear_press"));
        assertNull(plugin.blueprints().learnFromHand(worker, workshop), "работник изучает");
        assertTrue(workshop.learnedRecipes.contains("custom:gear_press"));
        hold(alex, bp("alloy"));
        assertNotNull(plugin.blueprints().learnFromHand(alex, workshop), "посторонний не может");
        assertEquals(1, bpCount(alex));
        hold(boss, bp("alloy"));
        assertNotNull(plugin.blueprints().learnFromHand(boss, workshop), "администратор не изучает за чужое предприятие");
        assertEquals(1, bpCount(boss));
        assertFalse(workshop.learnedRecipes.contains("custom:alloy"));
    }

    @Test
    void refusalsNeverConsumeTheBlueprint() throws Exception {
        // рука пуста
        assertEquals("Возьмите чертёж в основную руку.", plugin.blueprints().learnFromHand(steve, workshop));
        // не чертёж
        hold(steve, new ItemStack(Material.PAPER));
        assertNotNull(plugin.blueprints().learnFromHand(steve, workshop));
        // свободный рецепт
        ItemStack free = bp("free_one");
        hold(steve, free);
        assertTrue(plugin.blueprints().learnFromHand(steve, workshop).contains("доступен всем"));
        assertEquals(1, bpCount(steve));
        // уже изучен
        workshop.learnedRecipes.add("custom:alloy");
        hold(steve, bp("alloy"));
        assertTrue(plugin.blueprints().learnFromHand(steve, workshop).contains("уже знает"));
        assertEquals(1, bpCount(steve));
        // рецепта больше нет
        ItemStack stale = bp("gear_press");
        write0("recipes", "metal.yml", "other:\n  station: furnace\n  seconds: 5\n  inputs: [{item: COAL}]\n  output: {item: ir:steel}\n");
        plugin.reloadCustomContent();
        hold(steve, stale);
        assertTrue(plugin.blueprints().learnFromHand(steve, workshop).contains("больше не существует"));
        assertEquals(1, bpCount(steve));
        // не мастерская и пустое место
        Plot shop = addPlot("s", Category.SHOP, 30, -1, 10);
        assertNotNull(plugin.blueprints().learnFromHand(steve, shop));
        assertNotNull(plugin.blueprints().learnFromHand(steve, null));
        assertNotNull(plugin.blueprints().learnFromHand(steve, addPlot("w2", Category.WORKSHOP, 60, -1, 10)), "свободная мастерская");
    }

    @Test
    void aForgedBlueprintForAVanillaRecipeIsRefused() {
        plugin.recipes().setSource(() -> List.<org.bukkit.inventory.Recipe>of(new org.bukkit.inventory.FurnaceRecipe(
                new org.bukkit.NamespacedKey(plugin, "f1"), new ItemStack(Material.IRON_INGOT), Material.RAW_IRON, 0.7f, 200)).iterator());
        assertNotNull(plugin.recipes().get("furnace:IRON_INGOT"), "обычный рецепт существует");
        ItemStack forged = new ItemStack(Material.PAPER);
        forged.editMeta(m -> {
            m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("industryreborn", "blueprint"), org.bukkit.persistence.PersistentDataType.STRING, "furnace:IRON_INGOT");
            m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("industryreborn", "blueprint_serial"), org.bukkit.persistence.PersistentDataType.STRING, UUID.randomUUID().toString());
        });
        hold(steve, forged);
        assertTrue(plugin.blueprints().learnFromHand(steve, workshop).contains("больше не существует"));
        assertEquals(1, bpCount(steve));
        assertTrue(workshop.learnedRecipes.isEmpty());
    }

    private void write0(String dir, String name, String text) throws Exception {
        write(dir, name, text);
    }

    @Test
    void aBusyWorkshopAndADisabledFeatureRefuseWithoutConsuming() {
        hold(steve, bp("gear_press"));
        workshop.busy = true;
        assertTrue(plugin.blueprints().learnFromHand(steve, workshop).contains("обрабатывается"));
        workshop.busy = false;
        plugin.modules().set(ru.industryreborn.service.Module.PRODUCTION, false, "Boss");
        assertTrue(plugin.blueprints().learnFromHand(steve, workshop).contains("отключены"));
        plugin.modules().set(ru.industryreborn.service.Module.PRODUCTION, true, "Boss");
        assertEquals(1, bpCount(steve));
        plugin.getConfig().set("custom-items.enabled", false);
        assertFalse(plugin.blueprints().enabled());
    }

    @Test
    void thePlotIsResolvedFromTheArgumentThePlaceOrTheOnlyWorkshop() {
        assertEquals(workshop, plugin.blueprints().resolvePlot(steve, "w"));
        assertEquals(workshop, plugin.blueprints().resolvePlot(steve, null), "единственная мастерская игрока");
        assertEquals(workshop, plugin.blueprints().resolvePlot(worker, null), "работник");
        assertNull(plugin.blueprints().resolvePlot(alex, null));
        Plot second = addPlot("w2", Category.WORKSHOP, 60, -1, 10);
        assertTrue(plugin.service().rent(steve, second, 5));
        assertNull(plugin.blueprints().resolvePlot(steve, null), "две мастерские - нужно указать");
        steve.teleport(new org.bukkit.Location(server.getWorld("world"), 62, 1, 3));
        assertEquals(second, plugin.blueprints().resolvePlot(steve, null), "та, где стоит");
    }

    @Test
    void learnedRecipesStayWithTheEnterpriseWhenItChangesHands() {
        hold(steve, bp("gear_press"));
        assertNull(plugin.blueprints().learnFromHand(steve, workshop));
        plugin.service().release(steve, workshop);
        assertTrue(workshop.learnedRecipes.contains("custom:gear_press"), "после отказа рецепт остаётся");
        workshop.restorePending = false;                   // сброс здания в тестах не выполняется
        assertTrue(plugin.service().rent(alex, workshop, 5));
        assertTrue(plugin.factory().recipesFor(workshop, Material.FURNACE).stream().anyMatch(r -> r.id().equals("custom:gear_press")),
                "новый арендатор получает изученные рецепты");
        boss.performCommand("district evict w тест");
        assertTrue(workshop.learnedRecipes.contains("custom:gear_press"), "и после выселения");
    }

    @Test
    void usedSerialsAndTheVaultSurviveARestart() {
        ItemStack s = bp("gear_press");
        String serial = plugin.blueprints().read(s).serial();
        hold(steve, s);
        plugin.blueprints().learnFromHand(steve, workshop);
        plugin.blueprints().deposit(bp("alloy"), "тест");
        var fresh = new BlueprintService(plugin);
        assertTrue(fresh.used(serial));
        assertEquals(1, fresh.vaultSize());
        assertEquals("custom:alloy", fresh.read(fresh.vaultItems().get(0)).recipeId());
    }

    // ------------------------------------------------------------ выдача и магазин

    @Test
    void anAdminGivesBlueprintsWithFreshSerials() {
        assertNull(plugin.blueprints().give(steve, "gear_press", 3));
        assertEquals(3, bpCount(steve));
        var serials = new java.util.HashSet<String>();
        for (ItemStack s : steve.getInventory().getContents()) {
            if (plugin.blueprints().isBlueprint(s)) {
                serials.add(plugin.blueprints().read(s).serial());
            }
        }
        assertEquals(3, serials.size());
        assertNotNull(plugin.blueprints().give(steve, "nope", 1));
        assertNotNull(plugin.blueprints().give(steve, "gear_press", 0));
        assertNotNull(plugin.blueprints().give(steve, "gear_press", 65));
        boss.performCommand("district blueprint give Alex alloy 2");
        assertEquals(2, bpCount(alex));
        alex.performCommand("district blueprint give Alex alloy 2");
        assertEquals(2, bpCount(alex), "выдаёт только администратор");
    }

    @Test
    void theAdministrationShopSellsForMoneyIntoTheTreasury() {
        double before = plugin.treasury().balance();
        double money = economy.getBalance(steve);
        assertNull(plugin.blueprints().buy(steve, "gear_press"));
        assertEquals(money - 500, economy.getBalance(steve), 1e-9);
        assertEquals(before + 500, plugin.treasury().balance(), 1e-9);
        assertEquals(1, bpCount(steve));
        assertEquals("custom:gear_press", plugin.blueprints().read(findBp(steve)).recipeId());
    }

    private ItemStack findBp(PlayerMock p) {
        for (ItemStack s : p.getInventory().getContents()) {
            if (plugin.blueprints().isBlueprint(s)) {
                return s;
            }
        }
        return null;
    }

    @Test
    void theShopRefusesWhatItDoesNotSell() {
        economy.set(alex, 100);
        assertTrue(plugin.blueprints().buy(alex, "gear_press").contains("Не хватает денег"));
        assertEquals(100, economy.getBalance(alex), 1e-9);
        assertEquals(0, bpCount(alex));
        double money = economy.getBalance(steve);
        assertNotNull(plugin.blueprints().buy(steve, "free_one"), "свободный рецепт не продаётся");
        assertNotNull(plugin.blueprints().buy(steve, "nope"));
        assertEquals(money, economy.getBalance(steve), 1e-9);
        plugin.getConfig().set("custom-items.enabled", false);
        assertNotNull(plugin.blueprints().buy(steve, "alloy"));
    }

    @Test
    void theShopMenuListsOnlyPricedNonFreeRecipesAndBuys() {
        double money = economy.getBalance(steve);
        new ru.industryreborn.gui.BlueprintShopMenu(plugin, steve, null, 0).open();
        var inv = steve.getOpenInventory().getTopInventory();
        assertEquals(Material.PAPER, inv.getItem(9).getType());
        assertEquals(Material.MAP, inv.getItem(10).getType(), "основа второго чертежа");
        assertTrue(inv.getItem(11).getType().name().endsWith("PANE"), "free_one в продаже нет");
        steve.simulateInventoryClick(steve.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, 9);
        assertEquals(1, bpCount(steve));
        assertEquals(money - 500, economy.getBalance(steve), 1e-9);
        steve.performCommand("district blueprint shop");
        steve.performCommand("district blueprint buy alloy");
        assertEquals(2, bpCount(steve));
    }

    @Test
    void recipeFilesValidatePricesAndBlueprintItems() throws Exception {
        write("recipes", "bad.yml", """
                neg:
                  station: furnace
                  seconds: 5
                  blueprint_price: -5
                  inputs: [{item: COAL}]
                  output: {item: ir:steel}
                badbp:
                  station: furnace
                  seconds: 5
                  blueprint_item: ir:nope
                  inputs: [{item: COAL}]
                  output: {item: ir:steel}
                """);
        List<String> errors = plugin.recipes().loadCustom();
        assertEquals(2, errors.size(), errors.toString());
        assertEquals(500, plugin.recipes().blueprintPrice("custom:gear_press"), 1e-9);
        assertEquals(0, plugin.recipes().blueprintPrice("custom:neg"), 1e-9);
        assertEquals("ir:bp_gear", plugin.recipes().blueprintItem("custom:alloy"));
    }

    // ------------------------------------------------------------ аукцион казны и хранилище администрации

    private UUID uid() {
        return steve.getUniqueId();
    }

    private void addTo(StorageEntry.State state, ItemStack... items) {
        plugin.storage().add(uid(), "w", List.of(items), state, "тест", "b-" + UUID.randomUUID());
    }

    private int stacksIn(StorageEntry.State state) {
        return plugin.storage().entries(uid()).stream().filter(e -> e.state == state).mapToInt(e -> e.stacks.size()).sum();
    }

    @Test
    void blueprintsNeverReachTheTreasuryAuctionQueue() {
        addTo(StorageEntry.State.AUCTION, bp("gear_press"), new ItemStack(Material.DIRT, 5));
        assertEquals(1, stacksIn(StorageEntry.State.AUCTION), "в очереди аукциона остался только обычный предмет");
        assertEquals(1, plugin.blueprints().vaultSize(), "чертёж ушёл в хранилище администрации");
    }

    @Test
    void anAdminDecisionToAuctionOrDeleteMovesBlueprintsToTheVault() {
        addTo(StorageEntry.State.ARRESTED, bp("gear_press"), new ItemStack(Material.DIRT, 5));
        addTo(StorageEntry.State.ARRESTED, bp("alloy"));
        assertNull(plugin.arrests().decide("Boss", uid(), null, ru.industryreborn.service.ArrestService.Decision.AUCTION, "причина"));
        assertEquals(2, plugin.blueprints().vaultSize());
        assertEquals(1, stacksIn(StorageEntry.State.AUCTION), "на аукцион ушла только земля");
        assertEquals(0, stacksIn(StorageEntry.State.ARRESTED));
        addTo(StorageEntry.State.ARRESTED, bp("gear_press"), new ItemStack(Material.DIRT, 1));
        assertNull(plugin.arrests().decide("Boss", uid(), null, ru.industryreborn.service.ArrestService.Decision.DELETE, "мусор"));
        assertEquals(3, plugin.blueprints().vaultSize(), "при удалении чертёж не пропадает");
    }

    @Test
    void releasingArrestedBlueprintsReturnsThemToThePlayer() {
        addTo(StorageEntry.State.ARRESTED, bp("gear_press"));
        assertNull(plugin.arrests().decide("Boss", uid(), null, ru.industryreborn.service.ArrestService.Decision.RELEASE, "ошибка"));
        assertEquals(0, plugin.blueprints().vaultSize(), "вернуть игроку - его же вещь");
        assertEquals(1, stacksIn(StorageEntry.State.AVAILABLE));
    }

    @Test
    void anExpiredStorageEntryDoesNotLoseBlueprints() {
        addTo(StorageEntry.State.AVAILABLE, bp("gear_press"), new ItemStack(Material.DIRT, 5));
        long future = System.currentTimeMillis() + 100L * 86_400_000L;
        plugin.storage().sweepExpired(future, 30);
        assertEquals(1, plugin.blueprints().vaultSize(), "удаление по сроку не уничтожает чертёж");
        assertEquals(0, stacksIn(StorageEntry.State.AVAILABLE));
        addTo(StorageEntry.State.AVAILABLE, bp("alloy"), new ItemStack(Material.DIRT, 5));
        plugin.storage().sweepExpired(future, 30, true);
        assertEquals(2, plugin.blueprints().vaultSize(), "передача на аукцион по сроку тоже");
        assertEquals(1, stacksIn(StorageEntry.State.AUCTION));
    }

    @Test
    void anAuctionStackThatIsABlueprintIsRedirectedWhenTaken() {
        addTo(StorageEntry.State.AUCTION, new ItemStack(Material.DIRT, 5));
        StorageEntry e = plugin.storage().entries(uid()).get(0);
        // искусственно подкладываем чертёж в уже существующую запись, минуя add()
        e.stacks.add(bp("gear_press").serializeAsBytes());
        byte[] ref = e.stacks.get(1);
        assertNull(plugin.storage().takeForAuction(uid(), e.id, ref), "чертёж не отдаётся аукциону");
        assertEquals(1, plugin.blueprints().vaultSize());
    }

    @Test
    void aPlayerTakingHisOwnStoredBlueprintGetsItNotTheVault() {
        addTo(StorageEntry.State.AVAILABLE, bp("gear_press"));
        StorageEntry e = plugin.storage().entries(uid()).get(0);
        ItemStack taken = plugin.storage().take(uid(), e.id, e.stacks.get(0));
        assertNotNull(taken);
        assertTrue(plugin.blueprints().isBlueprint(taken), "свой чертёж из хранилища игрока возвращается игроку");
        assertEquals(0, plugin.blueprints().vaultSize());
    }

    @Test
    void ordinaryItemsAreUntouchedByTheVaultRules() {
        addTo(StorageEntry.State.AUCTION, new ItemStack(Material.DIRT, 5));
        assertEquals(1, stacksIn(StorageEntry.State.AUCTION));
        assertEquals(0, plugin.blueprints().vaultSize());
    }

    @Test
    void anAdminWithTheVaultRightTakesBlueprintsAndTheyWorkAgain() {
        ItemStack s = bp("gear_press");
        plugin.blueprints().deposit(s, "тест");
        assertTrue(plugin.blueprints().mayUseVault(boss));
        assertFalse(plugin.blueprints().mayUseVault(steve));
        new ru.industryreborn.gui.BlueprintVaultMenu(plugin, boss, 0).open();
        assertEquals(Material.PAPER, boss.getOpenInventory().getTopInventory().getItem(9).getType());
        boss.simulateInventoryClick(boss.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, 9);
        assertEquals(0, plugin.blueprints().vaultSize());
        assertEquals(1, bpCount(boss));
        // администратор передаёт чертёж управляющему: он снова рабочий
        ItemStack back = findBp(boss);
        hold(steve, back);
        assertNull(plugin.blueprints().learnFromHand(steve, workshop));
        assertTrue(workshop.learnedRecipes.contains("custom:gear_press"));
    }

    @Test
    void theVaultNeedsTheRightAndKeepsItemsWhenTheInventoryIsFull() {
        plugin.blueprints().deposit(bp("gear_press"), "тест");
        new ru.industryreborn.gui.BlueprintVaultMenu(plugin, steve, 0).open();
        steve.simulateInventoryClick(steve.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, 9);
        assertEquals(1, plugin.blueprints().vaultSize(), "без права ничего не забрать");
        steve.performCommand("district blueprint vault");
        for (int i = 0; i < boss.getInventory().getSize(); i++) {            // в MockBukkit addItem использует и слоты брони
            boss.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64));
        }
        new ru.industryreborn.gui.BlueprintVaultMenu(plugin, boss, 0).open();
        boss.simulateInventoryClick(boss.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, 9);
        assertEquals(1, plugin.blueprints().vaultSize(), "нет места - предмет остался в хранилище");
    }

    @Test
    void anAdminCanPutItemsIntoTheVaultByCommand() {
        hold(boss, bp("alloy"));
        boss.performCommand("district blueprint vault put");
        assertEquals(1, plugin.blueprints().vaultSize());
        assertEquals(0, bpCount(boss));
        steve.getInventory().setItemInMainHand(bp("alloy"));
        steve.performCommand("district blueprint vault put");
        assertEquals(1, plugin.blueprints().vaultSize(), "без права нельзя");
        boss.performCommand("district blueprint vault");
    }

    // ------------------------------------------------------------ команды и меню

    @Test
    void theUseAndInfoCommandsWork() {
        ItemStack s = bp("gear_press");
        hold(steve, s);
        steve.performCommand("district blueprint info");
        steve.performCommand("district blueprint use w");
        assertTrue(workshop.learnedRecipes.contains("custom:gear_press"));
        hold(alex, bp("alloy"));
        alex.performCommand("district blueprint use w");
        assertEquals(1, bpCount(alex));
        alex.performCommand("district blueprint");
        alex.performCommand("district blueprint wat");
    }

    @Test
    void theFactoryMenuButtonLearnsFromTheHand() {
        hold(steve, bp("alloy"));
        new ru.industryreborn.gui.FactoryMenu(plugin, steve, workshop, null).open();
        assertEquals(Material.MAP, steve.getOpenInventory().getTopInventory().getItem(15).getType());
        steve.simulateInventoryClick(steve.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, 15);
        assertTrue(workshop.learnedRecipes.contains("custom:alloy"));
        new ru.industryreborn.gui.FactoryMenu(plugin, steve, workshop, null).open();
        steve.simulateInventoryClick(steve.getOpenInventory(), org.bukkit.event.inventory.ClickType.RIGHT, 15);
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof ru.industryreborn.gui.BlueprintShopMenu);
    }

    // ------------------------------------------------------------ ChestShop

    @Test
    void chestShopDealsWithBlueprintsAreCancelled() {
        org.mockbukkit.mockbukkit.MockBukkit.loadSimple(ru.industryreborn.service.ChestShop.class, "ChestShop", "1.0");
        new ru.industryreborn.service.ChestShopHook(plugin).register();
        var withBp = new com.Acrobot.ChestShop.Events.PreTransactionEvent(alex, new ItemStack[] {new ItemStack(Material.DIRT), bp("gear_press")});
        server.getPluginManager().callEvent(withBp);
        assertTrue(withBp.isCancelled(), "сделка с чертежом отменена");
        var plain = new com.Acrobot.ChestShop.Events.PreTransactionEvent(alex, new ItemStack[] {new ItemStack(Material.DIRT)});
        server.getPluginManager().callEvent(plain);
        assertFalse(plain.isCancelled());
    }
}
