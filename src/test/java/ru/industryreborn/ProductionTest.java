package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Station;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.ProductionService;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.util.Targeting;

/** Производство мастерской: рецепты из реестра игры, задания станкам, склад, сундуки, голограммы, улучшения. */
class ProductionTest extends PluginTestBase {

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

    private NamespacedKey key(String s) {
        return new NamespacedKey(plugin, s);
    }

    /** Небольшой набор рецептов «реестра игры». */
    private List<Recipe> registry() {
        List<Recipe> list = new ArrayList<>();
        list.add(new FurnaceRecipe(key("f1"), new ItemStack(Material.IRON_INGOT), Material.RAW_IRON, 0.7f, 200));
        list.add(new FurnaceRecipe(key("f2"), new ItemStack(Material.IRON_INGOT), Material.IRON_ORE, 0.7f, 200));
        list.add(new FurnaceRecipe(key("f3"), new ItemStack(Material.IRON_INGOT), Material.DEEPSLATE_IRON_ORE, 0.7f, 200));
        list.add(new BlastingRecipe(key("b1"), new ItemStack(Material.GOLD_INGOT), Material.RAW_GOLD, 1f, 100));
        list.add(new FurnaceRecipe(key("f4"), new ItemStack(Material.GOLD_INGOT), Material.RAW_GOLD, 1f, 200));
        list.add(new SmokingRecipe(key("s1"), new ItemStack(Material.COOKED_BEEF), Material.BEEF, 0.35f, 100));
        ShapedRecipe block = new ShapedRecipe(key("c1"), new ItemStack(Material.IRON_BLOCK));
        block.shape("III", "III", "III");
        block.setIngredient('I', Material.IRON_INGOT);
        list.add(block);
        ShapedRecipe pick = new ShapedRecipe(key("c2"), new ItemStack(Material.IRON_PICKAXE));
        pick.shape("III", " S ", " S ");
        pick.setIngredient('I', Material.IRON_INGOT);
        pick.setIngredient('S', Material.STICK);
        list.add(pick);
        ShapelessRecipe cream = new ShapelessRecipe(key("c3"), new ItemStack(Material.MAGMA_CREAM));
        cream.addIngredient(Material.BLAZE_POWDER);
        cream.addIngredient(Material.SLIME_BALL);
        list.add(cream);
        ShapedRecipe planks = new ShapedRecipe(key("c4"), new ItemStack(Material.CHEST));
        planks.shape("PPP", "P P", "PPP");
        planks.setIngredient('P', new RecipeChoice.MaterialChoice(Material.OAK_PLANKS, Material.BIRCH_PLANKS));
        list.add(planks);
        ShapedRecipe tnt = new ShapedRecipe(key("c5"), new ItemStack(Material.TNT));
        tnt.shape("GSG", "SGS", "GSG");
        tnt.setIngredient('G', Material.GUNPOWDER);
        tnt.setIngredient('S', Material.SAND);
        list.add(tnt);
        ShapedRecipe exact = new ShapedRecipe(key("c6"), new ItemStack(Material.DIAMOND_BLOCK));
        exact.shape("D");
        exact.setIngredient('D', new RecipeChoice.ExactChoice(new ItemStack(Material.DIAMOND)));
        list.add(exact);
        ItemStack named = new ItemStack(Material.PAPER);
        named.editMeta(m -> m.displayName(net.kyori.adventure.text.Component.text("Особая")));
        ShapelessRecipe special = new ShapelessRecipe(key("c7"), named);
        special.addIngredient(Material.PAPER);
        list.add(special);
        ShapedRecipe second = new ShapedRecipe(key("c8"), new ItemStack(Material.IRON_BLOCK));
        second.shape("II", "II");
        second.setIngredient('I', Material.IRON_NUGGET);
        list.add(second);
        return list;
    }

    @BeforeEach
    void setUp() {
        plugin.recipes().setSource(() -> registry().iterator());
        plugin.getConfig().set("electricity.max-clock-step-seconds", 10_000_000);
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.workshop", 0);
        plugin.power().setTimeSource(wall::get);
        server.getWorld("world").loadChunk(0, 0);
        steve = player("Steve", 1_000_000);
        alex = player("Alex", 1_000_000);
        boss = admin("Boss");
        workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        assertTrue(plugin.service().rent(steve, workshop, 10));
        machine(Material.FURNACE, 1);
        machine(Material.BLAST_FURNACE, 2);
        machine(Material.ANVIL, 3);
        assertTrue(plugin.power().deposit(steve, workshop, 200_000));
        plugin.factory().tick();       // запоминает начальный момент
    }

    @AfterEach
    void tearDown() {
        Targeting.set(null);
    }

    private void machine(Material m, int x) {
        Block b = server.getWorld("world").getBlockAt(x, 1, 1);
        b.setType(m);
        plugin.stations().addMachine(workshop, x, 1, 1, Station.fromName(m.name()));
        plugin.stations().inc(workshop, Station.fromName(m.name()));
        plugin.power().recalc(workshop);
    }

    private static String pos(int x) {
        return x + ",1,1";
    }

    private void pass(long seconds) {
        wall.addAndGet(seconds * 1000);
        plugin.power().tick();
        plugin.factory().tick();
    }

    private void stock(Material m, int n) {
        workshop.stock.merge(m, n, Integer::sum);
    }

    private String assign(int x, String recipe) {
        return plugin.factory().assign(steve, workshop, pos(x), recipe);
    }

    private Block chest(int x, int y, int z) {
        Block b = server.getWorld("world").getBlockAt(x, y, z);
        b.setType(Material.CHEST);
        return b;
    }

    private static int count(org.bukkit.inventory.Inventory inv, Material m) {
        int n = 0;
        for (ItemStack s : inv.getContents()) {
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

    // ------------------------------------------------------------ каталог рецептов

    @Test
    void furnaceRecipesMergeAllOresOfTheSameResult() {
        RecipeBook.Recipe r = plugin.recipes().get("furnace:IRON_INGOT");
        assertNotNull(r);
        assertEquals(RecipeBook.Kind.SMELT, r.kind());
        assertEquals(List.of(Material.RAW_IRON, Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE), r.inputs().get(0).options());
        assertEquals(10, r.seconds(), 1e-9, "200 тиков = 10 секунд");
        assertEquals(1, r.outputAmount());
    }

    @Test
    void eachFurnaceTypeHasItsOwnRecipes() {
        assertNotNull(plugin.recipes().get("blast:GOLD_INGOT"));
        assertEquals(5, plugin.recipes().get("blast:GOLD_INGOT").seconds(), 1e-9);
        assertNotNull(plugin.recipes().get("furnace:GOLD_INGOT"));
        assertNotNull(plugin.recipes().get("smoker:COOKED_BEEF"));
        assertNull(plugin.recipes().get("furnace:COOKED_BEEF"), "мясо готовит только коптильня");
        var forBlast = plugin.recipes().forBlock(Material.BLAST_FURNACE);
        assertEquals(1, forBlast.size());
        assertEquals(Material.GOLD_INGOT, forBlast.get(0).output());
        assertEquals(2, plugin.recipes().forBlock(Material.FURNACE).size());
        assertEquals(1, plugin.recipes().forBlock(Material.SMOKER).size());
    }

    @Test
    void shapedRecipesCountEveryIngredient() {
        RecipeBook.Recipe block = plugin.recipes().get("craft:IRON_BLOCK");
        assertEquals(RecipeBook.Kind.ASSEMBLE, block.kind());
        assertEquals(1, block.inputs().size());
        assertEquals(9, block.inputs().get(0).count());
        RecipeBook.Recipe pick = plugin.recipes().get("craft:IRON_PICKAXE");
        assertEquals(2, pick.inputs().size());
        assertEquals(3, pick.inputs().get(0).count());
        assertEquals(2, pick.inputs().get(1).count());
        assertEquals("Инструменты", pick.category());
        assertEquals("Блоки", block.category());
    }

    @Test
    void shapelessAndMultiMaterialChoicesAreSupported() {
        RecipeBook.Recipe cream = plugin.recipes().get("craft:MAGMA_CREAM");
        assertEquals(2, cream.inputs().size());
        RecipeBook.Recipe chest = plugin.recipes().get("craft:CHEST");
        assertEquals(8, chest.inputs().get(0).count());
        assertEquals(List.of(Material.OAK_PLANKS, Material.BIRCH_PLANKS), chest.inputs().get(0).options());
    }

    @Test
    void dangerousAndNonPlainRecipesAreLeftOut() {
        assertNull(plugin.recipes().get("craft:TNT"), "в banned-outputs");
        assertNull(plugin.recipes().get("craft:DIAMOND_BLOCK"), "точный предмет с метаданными не поддерживается");
        assertNull(plugin.recipes().get("craft:PAPER"), "результат с названием");
    }

    @Test
    void oneRecipePerResultTheFirstWins() {
        RecipeBook.Recipe block = plugin.recipes().get("craft:IRON_BLOCK");
        assertEquals(Material.IRON_INGOT, block.inputs().get(0).options().get(0), "не вариант из самородков");
    }

    @Test
    void assemblyTimeGrowsWithTheIngredientCount() {
        assertEquals(4 + 0.5 * 9, plugin.recipes().get("craft:IRON_BLOCK").seconds(), 1e-9);
        plugin.getConfig().set("production.assembly-seconds-base", 10);
        plugin.recipes().invalidate();
        assertEquals(10 + 0.5 * 9, plugin.recipes().get("craft:IRON_BLOCK").seconds(), 1e-9);
    }

    @Test
    void familiesFollowTheBlock() {
        assertEquals("furnace", RecipeBook.familyOf(Material.FURNACE));
        assertEquals("blast", RecipeBook.familyOf(Material.BLAST_FURNACE));
        assertEquals("smoker", RecipeBook.familyOf(Material.SMOKER));
        assertEquals("craft", RecipeBook.familyOf(Material.CHIPPED_ANVIL));
        assertEquals("enchant", RecipeBook.familyOf(Material.ENCHANTING_TABLE));
        assertNull(RecipeBook.familyOf(Material.BREWING_STAND));
        assertNull(RecipeBook.familyOf(Material.DIRT));
    }

    @Test
    void categoriesOfAssemblyResults() {
        assertEquals("Броня", RecipeBook.categoryOf(Material.IRON_HELMET));
        assertEquals("Оружие", RecipeBook.categoryOf(Material.DIAMOND_SWORD));
        assertEquals("Инструменты", RecipeBook.categoryOf(Material.GOLDEN_SHOVEL));
        assertEquals("Еда", RecipeBook.categoryOf(Material.BREAD));
        assertEquals("Блоки", RecipeBook.categoryOf(Material.STONE));
        assertEquals("Прочее", RecipeBook.categoryOf(Material.STICK));
    }

    // ------------------------------------------------------------ задания

    @Test
    void aMachineGetsARecipeAndTheWorkIsLogged() {
        assertNull(assign(1, "furnace:IRON_INGOT"));
        assertEquals("furnace:IRON_INGOT", workshop.machineStates.get(pos(1)).recipe);
        assertEquals(1, plugin.factory().assignedCount(workshop));
        assertEquals(1, plugin.factory().assignedCount(workshop, "smelter"));
        assertEquals(0, plugin.factory().assignedCount(workshop, "anvil"));
        assertTrue(plugin.journal().forPlot("w").stream().anyMatch(e -> e.reason().contains("furnace:IRON_INGOT")));
    }

    @Test
    void aRecipeMustMatchTheMachineType() {
        assertNotNull(assign(1, "blast:GOLD_INGOT"), "это рецепт плавильной печи");
        assertNotNull(assign(3, "furnace:IRON_INGOT"), "наковальня не плавит");
        assertNotNull(assign(1, "furnace:DIAMOND"), "неизвестный рецепт");
        assertNull(assign(2, "blast:GOLD_INGOT"));
        assertNull(assign(3, "craft:IRON_BLOCK"));
        assertNotNull(assign(9, "furnace:IRON_INGOT"), "в этой позиции нет станка");
    }

    @Test
    void onlyTheManagerAssignsTasks() {
        assertNotNull(plugin.factory().assign(alex, workshop, pos(1), "furnace:IRON_INGOT"));
        assertNull(plugin.factory().assign(boss, workshop, pos(1), "furnace:IRON_INGOT"), "администратор может");
        Plot house = addPlot("h", Category.HOUSE, 20, 100, 5);
        assertNotNull(plugin.factory().assign(steve, house, "1,1,1", "furnace:IRON_INGOT"));
    }

    @Test
    void theNumberOfMachinesWithATaskIsLimitedByTheLevel() {
        plugin.getConfig().set("upgrades.base.max-lines", 2);
        machine(Material.FURNACE, 4);
        assertNull(assign(1, "furnace:IRON_INGOT"));
        assertNull(assign(4, "furnace:IRON_INGOT"));
        String error = plugin.factory().assign(steve, workshop, pos(2), "blast:GOLD_INGOT");
        assertNotNull(error);
        assertTrue(error.contains("2"), error);
        assertNull(assign(1, "furnace:GOLD_INGOT"), "смена рецепта на станке со слотом разрешена");
    }

    @Test
    void changingTheRecipeNeedsAnEmptyOutputBuffer() {
        stock(Material.RAW_IRON, 10);
        assign(1, "furnace:IRON_INGOT");
        pass(10);
        assertEquals(1, workshop.machineStates.get(pos(1)).output);
        assertNotNull(assign(1, "furnace:GOLD_INGOT"), "в буфере слитки прежнего рецепта");
        assertNull(plugin.factory().collect(steve, workshop));
        assertNull(assign(1, "furnace:GOLD_INGOT"));
    }

    @Test
    void clearingATaskKeepsTheProduct() {
        stock(Material.RAW_IRON, 10);
        assign(1, "furnace:IRON_INGOT");
        pass(10);
        assertNull(plugin.factory().clear(steve, workshop, pos(1)));
        assertNull(workshop.machineStates.get(pos(1)).recipe);
        assertEquals(1, workshop.machineStates.get(pos(1)).output, "продукция осталась в буфере");
        assertNotNull(plugin.factory().clear(steve, workshop, pos(3)), "у наковальни нет задания");
    }

    // ------------------------------------------------------------ работа

    @Test
    void aMachineProducesFromTheSharedStock() {
        stock(Material.RAW_IRON, 20);
        assign(1, "furnace:IRON_INGOT");
        pass(25);
        assertEquals(2, workshop.machineStates.get(pos(1)).output);
        assertEquals(18, plugin.factory().stock(workshop, Material.RAW_IRON));
        pass(5);
        assertEquals(3, workshop.machineStates.get(pos(1)).output);
    }

    @Test
    void anyOfTheAcceptedOresIsUsedAndPrimaryFirst() {
        stock(Material.IRON_ORE, 2);
        stock(Material.RAW_IRON, 1);
        assign(1, "furnace:IRON_INGOT");
        pass(30);
        assertEquals(3, workshop.machineStates.get(pos(1)).output);
        assertEquals(0, plugin.factory().stockTotal(workshop));
        assertEquals(0, plugin.factory().stock(workshop, Material.RAW_IRON), "сначала основное сырьё");
    }

    @Test
    void multiIngredientAssemblyConsumesEveryComponent() {
        stock(Material.IRON_INGOT, 4);
        stock(Material.STICK, 5);
        assign(3, "craft:IRON_PICKAXE");
        pass(60);
        assertEquals(1, workshop.machineStates.get(pos(3)).output, "из 4 слитков и 5 палок получается одна кирка");
        assertEquals(1, plugin.factory().stock(workshop, Material.IRON_INGOT));
        assertEquals(3, plugin.factory().stock(workshop, Material.STICK));
        pass(60);
        assertEquals(1, workshop.machineStates.get(pos(3)).output, "сырья на вторую нет");
    }

    @Test
    void outputPerBatchFollowsTheRecipe() {
        stock(Material.RAW_GOLD, 1);
        assign(2, "blast:GOLD_INGOT");
        pass(5);
        assertEquals(1, workshop.machineStates.get(pos(2)).output);
    }

    @Test
    void noRawMaterialMeansNoProgressAndNoBankedTime() {
        assign(1, "furnace:IRON_INGOT");
        pass(100);
        assertEquals(0, workshop.machineStates.get(pos(1)).output);
        assertEquals(0, workshop.machineStates.get(pos(1)).progress, 1e-9);
        stock(Material.RAW_IRON, 50);
        pass(10);
        assertEquals(1, workshop.machineStates.get(pos(1)).output, "простой время не копит: за 10 секунд ровно одна партия");
    }

    @Test
    void timeLeftOverWhenTheStockRunsOutIsNotBanked() {
        stock(Material.RAW_IRON, 1);
        assign(1, "furnace:IRON_INGOT");
        pass(25);                                        // одна партия за 10 секунд, остальные 15 секунд сырья нет
        assertEquals(1, workshop.machineStates.get(pos(1)).output);
        assertEquals(0, workshop.machineStates.get(pos(1)).progress, 1e-9);
        stock(Material.RAW_IRON, 5);
        pass(1);
        assertEquals(1, workshop.machineStates.get(pos(1)).output, "копить время впрок нельзя");
    }

    @Test
    void workshopsNotYetScannedAreLeftAlone() {
        stock(Material.RAW_IRON, 5);
        assign(1, "furnace:IRON_INGOT");
        plugin.stations().forget(workshop);              // после перезапуска станки ещё не пересчитаны
        pass(30);
        assertNotNull(workshop.machineStates.get(pos(1)), "задание не потеряно");
        assertEquals(5, plugin.factory().stock(workshop, Material.RAW_IRON));
    }

    @Test
    void noPowerMeansNoProduction() {
        stock(Material.RAW_IRON, 10);
        assign(1, "furnace:IRON_INGOT");
        plugin.power().touch(workshop);
        workshop.account.restore(0, workshop.account.ratePerDay(), workshop.account.updatedAtSec());
        pass(100);
        assertEquals(0, workshop.machineStates.get(pos(1)).output);
        assertEquals(ProductionService.Status.NO_POWER, plugin.factory().view(workshop, pos(1)).status());
    }

    @Test
    void aFullBufferStopsTheMachine() {
        plugin.getConfig().set("production.buffer-batches", 2);
        stock(Material.RAW_IRON, 50);
        assign(1, "furnace:IRON_INGOT");
        pass(100);
        assertEquals(2, workshop.machineStates.get(pos(1)).output);
        assertEquals(48, plugin.factory().stock(workshop, Material.RAW_IRON));
        assertEquals(ProductionService.Status.OUTPUT_FULL, plugin.factory().view(workshop, pos(1)).status());
    }

    @Test
    void severalMachinesShareTheStock() {
        machine(Material.FURNACE, 4);
        stock(Material.RAW_IRON, 3);
        assign(1, "furnace:IRON_INGOT");
        assign(4, "furnace:IRON_INGOT");
        pass(10);
        pass(10);
        int made = workshop.machineStates.get(pos(1)).output + workshop.machineStates.get(pos(4)).output;
        assertEquals(3, made, "из 3 единиц сырья выходит ровно 3 продукта, не больше");
        assertEquals(0, plugin.factory().stockTotal(workshop));
    }

    @Test
    void upgradesMakeMachinesFaster() {
        stock(Material.RAW_IRON, 50);
        workshop.level = 3;                 // скорость ×2
        assign(1, "furnace:IRON_INGOT");
        pass(10);
        assertEquals(2, workshop.machineStates.get(pos(1)).output);
    }

    @Test
    void theTimeFactorScalesBatchTime() {
        plugin.getConfig().set("production.time-factor", 0.5);
        stock(Material.RAW_IRON, 50);
        assign(1, "furnace:IRON_INGOT");
        pass(10);
        assertEquals(2, workshop.machineStates.get(pos(1)).output);
    }

    @Test
    void usageModeChargesEveryBatch() {
        plugin.getConfig().set("electricity.mode", "USAGE");
        stock(Material.RAW_IRON, 10);
        assign(1, "furnace:IRON_INGOT");
        double before = plugin.power().balance(workshop);
        pass(30);
        assertEquals(3, workshop.machineStates.get(pos(1)).output);
        assertTrue(plugin.power().balance(workshop) < before, "каждая партия платная");
    }

    @Test
    void machinesWithATaskDoNotServePlayerOrders() {
        assertEquals(0, plugin.factory().assignedCount(workshop, "smelter"));
        assign(1, "furnace:IRON_INGOT");
        assertEquals(1, plugin.factory().assignedCount(workshop, "smelter"));
        assertEquals(2, plugin.stations().countByTariffKey(workshop, "smelter"), "две печи всего, одна занята производством");
    }

    // ------------------------------------------------------------ сундуки

    @Test
    void theRawChestMovesNeededItemsToTheStock() {
        Block b = chest(5, 1, 5);
        assertNull(plugin.factory().markChest(steve, workshop, b, true));
        assign(1, "furnace:IRON_INGOT");
        var inv = ((Chest) b.getState()).getBlockInventory();
        inv.addItem(new ItemStack(Material.RAW_IRON, 30), new ItemStack(Material.DIRT, 10));
        ItemStack named = new ItemStack(Material.IRON_ORE, 5);
        named.editMeta(m -> m.displayName(net.kyori.adventure.text.Component.text("Особая")));
        inv.addItem(named);
        pass(1);
        assertEquals(30, plugin.factory().stock(workshop, Material.RAW_IRON));
        assertEquals(0, count(inv, Material.RAW_IRON));
        assertEquals(10, count(inv, Material.DIRT), "ненужное остаётся");
        assertEquals(5, count(inv, Material.IRON_ORE), "предметы с названием не принимаются");
    }

    @Test
    void theRawChestIgnoresItemsNoMachineNeeds() {
        Block b = chest(5, 1, 5);
        plugin.factory().markChest(steve, workshop, b, true);
        ((Chest) b.getState()).getBlockInventory().addItem(new ItemStack(Material.RAW_IRON, 30));
        pass(1);
        assertEquals(0, plugin.factory().stockTotal(workshop), "ни у одного станка нет задания");
        assign(1, "furnace:IRON_INGOT");
        pass(1);
        assertEquals(30, plugin.factory().stockTotal(workshop));
    }

    @Test
    void theStockCapacityLimitsTheInbox() {
        plugin.getConfig().set("production.stock-items", 20);
        Block b = chest(5, 1, 5);
        plugin.factory().markChest(steve, workshop, b, true);
        assign(1, "furnace:IRON_INGOT");
        var inv = ((Chest) b.getState()).getBlockInventory();
        inv.addItem(new ItemStack(Material.RAW_IRON, 64));
        pass(1);
        assertEquals(20, plugin.factory().stock(workshop, Material.RAW_IRON), "поместилось по вместимости склада");
        assertEquals(44, count(inv, Material.RAW_IRON), "остальное осталось в сундуке");
    }

    @Test
    void theProductChestReceivesTheProduction() {
        Block out = chest(6, 1, 6);
        assertNull(plugin.factory().markChest(steve, workshop, out, false));
        stock(Material.RAW_IRON, 10);
        assign(1, "furnace:IRON_INGOT");
        pass(30);
        var inv = ((Chest) out.getState()).getBlockInventory();
        assertEquals(3, count(inv, Material.IRON_INGOT));
        assertEquals(0, workshop.machineStates.get(pos(1)).output, "буфер станка пуст: всё ушло в сундук");
    }

    @Test
    void aFullProductChestKeepsTheRestInTheMachineBuffer() {
        Block out = chest(6, 1, 6);
        plugin.factory().markChest(steve, workshop, out, false);
        var inv = ((Chest) out.getState()).getBlockInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, new ItemStack(Material.COBBLESTONE, 64));
        }
        stock(Material.RAW_IRON, 10);
        assign(1, "furnace:IRON_INGOT");
        pass(20);
        assertEquals(2, workshop.machineStates.get(pos(1)).output, "некуда выложить - ждёт у станка");
        inv.clear();
        pass(1);
        assertEquals(2, count(inv, Material.IRON_INGOT));
        assertEquals(0, workshop.machineStates.get(pos(1)).output);
    }

    @Test
    void chestMarkingIsValidated() {
        Block b = chest(5, 1, 5);
        assertNull(plugin.factory().markChest(steve, workshop, b, true));
        assertNotNull(plugin.factory().markChest(steve, workshop, b, true), "уже помечен");
        assertNotNull(plugin.factory().markChest(steve, workshop, b, false), "нельзя быть и сырьём, и продукцией");
        assertNotNull(plugin.factory().markChest(alex, workshop, b, true), "не управляющий");
        Block dirt = server.getWorld("world").getBlockAt(7, 1, 7);
        dirt.setType(Material.DIRT);
        assertNotNull(plugin.factory().markChest(steve, workshop, dirt, true), "не контейнер");
        Block far = chest(60, 1, 60);
        assertNotNull(plugin.factory().markChest(steve, workshop, far, true), "вне мастерской");
        assertNull(plugin.factory().unmarkChest(steve, workshop, b));
        assertNotNull(plugin.factory().unmarkChest(steve, workshop, b));
        assertTrue(workshop.rawChests.isEmpty());
    }

    @Test
    void theNumberOfChestsIsLimited() {
        plugin.getConfig().set("production.chests-max", 2);
        assertNull(plugin.factory().markChest(steve, workshop, chest(5, 1, 5), true));
        assertNull(plugin.factory().markChest(steve, workshop, chest(5, 1, 6), true));
        assertNotNull(plugin.factory().markChest(steve, workshop, chest(5, 1, 7), true));
        assertNull(plugin.factory().markChest(steve, workshop, chest(5, 1, 7), false), "лимит считается отдельно");
    }

    @Test
    void aBrokenChestLosesItsMark() {
        Block b = chest(5, 1, 5);
        plugin.factory().markChest(steve, workshop, b, true);
        server.getPluginManager().callEvent(new org.bukkit.event.block.BlockBreakEvent(b, steve));
        assertTrue(workshop.rawChests.isEmpty());
        Block c = chest(5, 1, 6);
        plugin.factory().markChest(steve, workshop, c, false);
        c.setType(Material.AIR);                     // взрыв: события нет
        pass(1);
        assertTrue(workshop.productChests.isEmpty(), "самоочистка");
    }

    // ------------------------------------------------------------ ручной склад

    @Test
    void loadingFromTheInventoryNeedsATaskAndTakesOnlyNeededPlainItems() {
        steve.getInventory().clear();
        steve.getInventory().addItem(new ItemStack(Material.RAW_IRON, 40));
        assertNotNull(plugin.factory().load(steve, workshop, 0), "ни у кого нет задания");
        assign(1, "furnace:IRON_INGOT");
        steve.getInventory().addItem(new ItemStack(Material.DIRT, 5));
        assertNull(plugin.factory().load(steve, workshop, 25));
        assertEquals(25, plugin.factory().stock(workshop, Material.RAW_IRON));
        assertEquals(15, count(steve.getInventory(), Material.RAW_IRON));
        assertEquals(5, count(steve.getInventory(), Material.DIRT));
        assertNull(plugin.factory().load(steve, workshop, 0));
        assertEquals(40, plugin.factory().stock(workshop, Material.RAW_IRON));
    }

    @Test
    void collectingGivesTheProductToTheManagerAndTheOrdersAreSeparate() {
        stock(Material.RAW_IRON, 10);
        assign(1, "furnace:IRON_INGOT");
        pass(20);
        steve.getInventory().clear();
        assertNull(plugin.factory().collect(steve, workshop));
        assertEquals(2, count(steve.getInventory(), Material.IRON_INGOT) + stored(steve.getUniqueId(), Material.IRON_INGOT, StorageEntry.State.AVAILABLE));
        assertNotNull(plugin.factory().collect(steve, workshop), "собирать больше нечего");
        assertEquals(0, plugin.orders().all().size(), "заказы игроков в продукцию не попадают");
    }

    // ------------------------------------------------------------ закрытие и перенос

    @Test
    void releasingReturnsTheStockAndTheProduct() {
        stock(Material.RAW_IRON, 10);
        stock(Material.STICK, 3);
        assign(1, "furnace:IRON_INGOT");
        pass(10);
        plugin.factory().closeWorkshop(workshop, steve.getUniqueId(), false, "тест");
        assertTrue(workshop.stock.isEmpty() && workshop.machineStates.isEmpty());
        assertEquals(0, workshop.level);
        int ingots = count(steve.getInventory(), Material.IRON_INGOT) + stored(steve.getUniqueId(), Material.IRON_INGOT, StorageEntry.State.AVAILABLE);
        assertEquals(1, ingots);
        int raw = count(steve.getInventory(), Material.RAW_IRON) + stored(steve.getUniqueId(), Material.RAW_IRON, StorageEntry.State.AVAILABLE);
        assertEquals(9, raw);
    }

    @Test
    void seizureArrestsTheStock() {
        stock(Material.RAW_IRON, 15);
        steve.getInventory().clear();
        plugin.factory().closeWorkshop(workshop, steve.getUniqueId(), true, "нарушение");
        assertEquals(15, stored(steve.getUniqueId(), Material.RAW_IRON, StorageEntry.State.ARRESTED));
        assertEquals(0, count(steve.getInventory(), Material.RAW_IRON));
    }

    @Test
    void legacyLinesAreMigratedToTheStock() {
        workshop.legacyLines.put("iron-ingots", new int[] {12, 4});
        workshop.legacyLines.put("glass", new int[] {7, 0});
        workshop.legacyLines.put("unknown-line", new int[] {5, 5});
        steve.getInventory().clear();
        plugin.factory().migrate();
        assertEquals(12, plugin.factory().stock(workshop, Material.RAW_IRON));
        assertEquals(7, plugin.factory().stock(workshop, Material.SAND));
        assertTrue(workshop.legacyLines.isEmpty());
        assertEquals(4, count(steve.getInventory(), Material.IRON_INGOT) + stored(steve.getUniqueId(), Material.IRON_INGOT, StorageEntry.State.AVAILABLE));
    }

    @Test
    void destroyedMachinesReturnTheirProduct() {
        stock(Material.RAW_IRON, 10);
        assign(1, "furnace:IRON_INGOT");
        pass(20);
        plugin.stations().removeMachine(workshop, 1, 1, 1);
        steve.getInventory().clear();
        pass(1);
        assertNull(workshop.machineStates.get(pos(1)));
        assertEquals(2, count(steve.getInventory(), Material.IRON_INGOT) + stored(steve.getUniqueId(), Material.IRON_INGOT, StorageEntry.State.AVAILABLE));
    }

    @Test
    void productionStateSurvivesARestart() {
        stock(Material.RAW_IRON, 10);
        stock(Material.STICK, 4);
        assign(1, "furnace:IRON_INGOT");
        pass(25);
        workshop.rawChests.add("5,1,5");
        workshop.productChests.add("6,1,6");
        plugin.plots().save();
        plugin.plots().load();
        Plot loaded = plugin.plots().get("w");
        assertEquals("furnace:IRON_INGOT", loaded.machineStates.get(pos(1)).recipe);
        assertEquals(2, loaded.machineStates.get(pos(1)).output);
        assertEquals(8, loaded.stock.get(Material.RAW_IRON));
        assertEquals(4, loaded.stock.get(Material.STICK));
        assertEquals(List.of("5,1,5"), loaded.rawChests);
        assertEquals(List.of("6,1,6"), loaded.productChests);
    }

    // ------------------------------------------------------------ голограммы станков (регрессия)

    private String lines(int x, Material type, Station st) {
        Block b = server.getWorld("world").getBlockAt(x, 1, 1);
        return String.join("\n", plugin.machineHolograms().lines(workshop, b, st)).replaceAll("<[^>]+>", "");
    }

    @Test
    void theHologramShowsTheWorkOfTheMachineNotThePhysicalFurnace() {
        stock(Material.RAW_IRON, 10);
        assign(1, "furnace:IRON_INGOT");
        pass(5);
        String t = lines(1, Material.FURNACE, Station.FURNACE);
        assertTrue(t.contains("работает"), t);
        assertFalse(t.contains("простаивает"), "печь физически пуста, но станок работает: " + t);
        assertTrue(t.contains("50%"), t);
        assertTrue(t.contains("× 10"), t);
        pass(5);
        t = lines(1, Material.FURNACE, Station.FURNACE);
        assertTrue(t.contains("× 9") && t.contains("× 1"), t);
    }

    @Test
    void theHologramExplainsWhyAMachineStands() {
        assign(1, "furnace:IRON_INGOT");
        assertTrue(lines(1, Material.FURNACE, Station.FURNACE).contains("нет сырья"));
        stock(Material.RAW_IRON, 10);
        plugin.power().touch(workshop);
        workshop.account.restore(0, workshop.account.ratePerDay(), workshop.account.updatedAtSec());
        assertTrue(lines(1, Material.FURNACE, Station.FURNACE).contains("обесточен"));
    }

    @Test
    void anUnassignedMachineSaysItServesOrders() {
        String t = lines(3, Material.ANVIL, Station.ANVIL);
        assertTrue(t.contains("не назначено") && t.contains("обслуживает заказы"), t);
    }

    @Test
    void thePanelShowsProductionTotals() {
        stock(Material.RAW_IRON, 10);
        assign(1, "furnace:IRON_INGOT");
        pass(20);
        String text = String.join("\n", ru.industryreborn.service.StructureText.lines(plugin, workshop)).replaceAll("<[^>]+>", "");
        assertTrue(text.contains("Станков с заданием: 1 из 4"), text);
        assertTrue(text.contains("Склад сырья: 8 / 1024"), text);
        assertTrue(text.contains("Продукция в буферах: 2"), text);
    }

    // ------------------------------------------------------------ меню и команды

    @Test
    @SuppressWarnings("removal")
    void theMachineMenuListsRecipesAndAssignsOnClick() {
        new ru.industryreborn.gui.MachineMenu(plugin, steve, workshop, pos(1), null, 0, "").open();
        var inv = steve.getOpenInventory().getTopInventory();
        assertEquals(Material.GOLD_INGOT, inv.getItem(9).getType(), "по алфавиту");
        assertEquals(Material.IRON_INGOT, inv.getItem(10).getType());
        steve.simulateInventoryClick(10);
        assertEquals("furnace:IRON_INGOT", workshop.machineStates.get(pos(1)).recipe);
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof ru.industryreborn.gui.FactoryMenu);
    }

    @Test
    @SuppressWarnings("removal")
    void theAnvilMenuFiltersByCategoryAndSearch() {
        new ru.industryreborn.gui.MachineMenu(plugin, steve, workshop, pos(3), "Инструменты", 0, "").open();
        var inv = steve.getOpenInventory().getTopInventory();
        assertEquals(Material.IRON_PICKAXE, inv.getItem(9).getType());
        assertTrue(inv.getItem(10) == null || inv.getItem(10).getType().name().endsWith("_PANE"), "блоков в категории нет");
        new ru.industryreborn.gui.MachineMenu(plugin, steve, workshop, pos(3), null, 0, "magma").open();
        inv = steve.getOpenInventory().getTopInventory();
        assertEquals(Material.MAGMA_CREAM, inv.getItem(9).getType(), "поиск по названию");
        new ru.industryreborn.gui.MachineMenu(plugin, steve, workshop, pos(3), null, 0, "stick").open();
        assertEquals(Material.IRON_PICKAXE, steve.getOpenInventory().getTopInventory().getItem(9).getType(), "поиск по сырью");
    }

    @Test
    void theFactoryMenuShowsMachinesAndStock() {
        stock(Material.RAW_IRON, 10);
        assign(1, "furnace:IRON_INGOT");
        var inv = new ru.industryreborn.gui.FactoryMenu(plugin, steve, workshop, null).getInventory();
        assertEquals(Material.FURNACE, inv.getItem(20).getType());
        assertEquals(Material.BLAST_FURNACE, inv.getItem(21).getType());
        assertEquals(Material.ANVIL, inv.getItem(22).getType());
        String furnace = inv.getItem(20).getItemMeta().lore().stream()
                .map(l -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(l))
                .reduce("", (a, b) -> a + b + "\n");
        assertTrue(furnace.contains("IRON_INGOT"), furnace);
        assertEquals(Material.BARREL, inv.getItem(12).getType());
    }

    @Test
    @SuppressWarnings("removal")
    void shiftRightClickOnAMachineOpensItsMenu() {
        Block b = server.getWorld("world").getBlockAt(1, 1, 1);
        steve.setSneaking(true);
        steve.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEvent(steve,
                org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, b, org.bukkit.block.BlockFace.UP,
                org.bukkit.inventory.EquipmentSlot.HAND));
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof ru.industryreborn.gui.MachineMenu);
    }

    @Test
    void aStrangerWithShiftDoesNotOpenTheMenu() {
        Block b = server.getWorld("world").getBlockAt(1, 1, 1);
        alex.setSneaking(true);
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEvent(alex,
                org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, b, org.bukkit.block.BlockFace.UP,
                org.bukkit.inventory.EquipmentSlot.HAND));
        var top = alex.getOpenInventory().getTopInventory();
        assertTrue(top == null || !(top.getHolder() instanceof ru.industryreborn.gui.MachineMenu));
    }

    @Test
    void machineAndChestCommandsWorkOnTheLookedAtBlock() {
        Block furnace = server.getWorld("world").getBlockAt(1, 1, 1);
        Targeting.set(p -> furnace);
        steve.performCommand("district machine set IRON_INGOT");
        assertEquals("furnace:IRON_INGOT", workshop.machineStates.get(pos(1)).recipe);
        drain(steve);
        steve.performCommand("district machine info");
        assertTrue(drain(steve).contains("furnace:IRON_INGOT"));
        steve.performCommand("district machine clear");
        assertNull(workshop.machineStates.get(pos(1)));
        steve.performCommand("district machine set furnace:nothing");
        assertTrue(drain(steve).contains("Неизвестный рецепт"));
        Block c = chest(5, 1, 5);
        Targeting.set(p -> c);
        steve.performCommand("district chest raw");
        assertEquals(List.of("5,1,5"), workshop.rawChests);
        steve.performCommand("district chest unmark");
        assertTrue(workshop.rawChests.isEmpty());
        steve.performCommand("district chest product");
        assertEquals(List.of("5,1,5"), workshop.productChests);
        Block dirt = server.getWorld("world").getBlockAt(8, 1, 8);
        Targeting.set(p -> dirt);
        drain(steve);
        steve.performCommand("district machine info");
        assertTrue(drain(steve).contains("не рабочий станок"));
    }

    @Test
    void factoryCommandsLoadCollectAndShowInfo() {
        steve.getInventory().clear();
        steve.getInventory().addItem(new ItemStack(Material.RAW_IRON, 10));
        assign(1, "furnace:IRON_INGOT");
        steve.performCommand("district factory load w");
        assertEquals(10, plugin.factory().stock(workshop, Material.RAW_IRON));
        drain(steve);
        steve.performCommand("district factory info w");
        String info = drain(steve);
        assertTrue(info.contains("станков с заданием") && info.contains("RAW_IRON"), info);
        pass(10);
        steve.performCommand("district factory collect w");
        assertEquals(0, workshop.machineStates.get(pos(1)).output);
    }

    @Test
    void thePlayerOrdersKeepWorkingNextToProduction() {
        // станок без задания обслуживает заказы клиентов, как и раньше
        assertEquals(1, plugin.stations().countByTariffKey(workshop, "anvil") - plugin.factory().assignedCount(workshop, "anvil"));
    }
}
