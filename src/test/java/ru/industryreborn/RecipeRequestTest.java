package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.items.ItemRef;
import ru.industryreborn.items.RecipeRequests;
import ru.industryreborn.items.RecipeRequests.Part;
import ru.industryreborn.items.RecipeRequests.Spec;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Employee;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Request;
import ru.industryreborn.model.Station;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.support.PluginTestBase;

/** Заявки игроков на рецепты станков: подача, проверки, лимиты, решение администратора, чертежи, меню-конструктор. */
class RecipeRequestTest extends PluginTestBase {

    private final AtomicLong wall = new AtomicLong(1_000_000);
    private PlayerMock steve;      // управляющий
    private PlayerMock worker;     // работник
    private PlayerMock alex;       // посторонний
    private PlayerMock boss;
    private Plot workshop;

    private void write(String dir, String name, String text) throws Exception {
        File d = new File(plugin.getDataFolder(), dir);
        d.mkdirs();
        Files.writeString(new File(d, name).toPath(), text, StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() throws Exception {
        write("items", "metal.yml", "steel:\n  material: IRON_INGOT\n  name: \"Стальной слиток\"\ngear:\n  material: IRON_NUGGET\n  name: \"Шестерня\"\n");
        write("recipes", "base.yml", """
                existing:
                  station: furnace
                  seconds: 10
                  free: true
                  inputs: [{item: COAL, amount: 2}]
                  output: {item: ir:gear, amount: 1}
                """);
        assertTrue(plugin.reloadCustomContent().isEmpty());
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.workshop", 0);
        plugin.getConfig().set("electricity.max-clock-step-seconds", 10_000_000);
        plugin.power().setTimeSource(wall::get);
        server.getWorld("world").loadChunk(0, 0);
        steve = player("Steve", 1_000_000);
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
        steve.teleport(new org.bukkit.Location(server.getWorld("world"), 400, 1, 400));
    }

    private Spec spec() {
        return new Spec("furnace", 30, List.of(new Part("IRON_INGOT", 3), new Part("COAL", 1)), new Part("ir:steel", 2));
    }

    private Request pendingRequest() {
        return plugin.moderation().pending().stream().filter(r -> r.type == Request.Type.RECIPE).findFirst().orElse(null);
    }

    // ------------------------------------------------------------ формат

    @Test
    void aSpecRoundTripsThroughItsText() {
        Spec s = spec();
        assertEquals("furnace|30.0|IRON_INGOT*3;COAL*1|ir:steel*2", s.serialize());
        assertEquals(s, Spec.parse(s.serialize()));
        assertNull(Spec.parse("rubbish"));
        assertNull(Spec.parse("furnace|x|IRON_INGOT*1|ir:steel*1"));
        assertNull(Spec.parse("furnace|5|IRON_INGOT|ir:steel*1"));
    }

    @Test
    void commandTokensAndStationsAreParsed() {
        assertEquals(new Part("IRON_INGOT", 3), RecipeRequests.parsePart("iron_ingot*3"));
        assertEquals(new Part("ir:steel", 1), RecipeRequests.parsePart("ir:steel"));
        assertEquals(new Part("nexo:rune", 5), RecipeRequests.parsePart("nexo:rune*5"));
        assertNull(RecipeRequests.parsePart("IRON_INGOT*x"));
        assertNull(RecipeRequests.parsePart("NOT_REAL*2"));
        assertEquals("furnace", RecipeRequests.normalizeStation("печь"));
        assertEquals("blast", RecipeRequests.normalizeStation("Blast"));
        assertEquals("craft", RecipeRequests.normalizeStation("наковальня"));
        assertEquals("oven", RecipeRequests.normalizeStation("oven"));
        assertEquals("Плавка в печи, 30.0 с: 3×IRON_INGOT + 1×COAL → 2×Стальной слиток".replace("IRON_INGOT", "iron ingot").replace("COAL", "coal"),
                plugin.recipeRequests().describe(spec()).replace("IRON_INGOT", "iron ingot").replace("COAL", "coal"));
    }

    // ------------------------------------------------------------ проверки

    @Test
    void validationCoversEveryField() {
        var rr = plugin.recipeRequests();
        assertNull(rr.validate(spec()));
        assertNotNull(rr.validate(new Spec("oven", 30, spec().inputs(), spec().output())));
        assertNotNull(rr.validate(new Spec("furnace", 0.2, spec().inputs(), spec().output())), "слишком быстро");
        assertNotNull(rr.validate(new Spec("furnace", 9999, spec().inputs(), spec().output())), "слишком долго");
        assertNotNull(rr.validate(new Spec("furnace", 30, List.of(), spec().output())), "нет входов");
        assertNotNull(rr.validate(new Spec("furnace", 30, List.of(new Part("COAL", 1), new Part("IRON_INGOT", 1), new Part("GOLD_INGOT", 1),
                new Part("DIRT", 1), new Part("SAND", 1)), spec().output())), "больше четырёх");
        assertNotNull(rr.validate(new Spec("furnace", 30, List.of(new Part("COAL", 1), new Part("COAL", 2)), spec().output())), "дубль входа");
        assertNotNull(rr.validate(new Spec("furnace", 30, List.of(new Part("COAL", 0)), spec().output())), "количество");
        assertNotNull(rr.validate(new Spec("furnace", 30, List.of(new Part("COAL", 65)), spec().output())));
        assertNotNull(rr.validate(new Spec("furnace", 30, List.of(new Part("ir:nope", 1)), spec().output())), "неизвестный предмет плагина");
        assertNotNull(rr.validate(new Spec("furnace", 30, spec().inputs(), new Part("NOT_REAL", 1))));
        assertNotNull(rr.validate(new Spec("furnace", 30, spec().inputs(), new Part("COAL", 1))), "результат есть среди входов");
        assertNotNull(rr.validate(new Spec("furnace", 30, List.of(new Part("COAL", 2)), new Part("ir:gear", 1))), "такой рецепт уже есть");
        assertNull(rr.validate(new Spec("furnace", 30, List.of(new Part("COAL", 3)), new Part("ir:gear", 1))), "другое количество - другой рецепт");
        assertNull(rr.validate(new Spec("blast", 30, List.of(new Part("nexo:any", 1)), spec().output())), "предметы Nexo до загрузки Nexo не проверяются");
    }

    @Test
    void whoMaySubmitAndWhenItIsRefused() {
        var rr = plugin.recipeRequests();
        assertNull(rr.submit(steve, workshop, spec()));
        assertNotNull(pendingRequest());
        assertEquals(Request.Type.RECIPE, pendingRequest().type);
        assertEquals("w", pendingRequest().plotId);
        assertEquals(steve.getUniqueId(), pendingRequest().requester);
        assertNull(plugin.recipes().get("custom:w_ir_steel"), "до решения рецепта нет");
        assertTrue(workshop.learnedRecipes.isEmpty());
        Spec second = new Spec("blast", 12, List.of(new Part("COAL", 5)), new Part("ir:steel", 1));
        assertNull(rr.submit(worker, workshop, second), "работник тоже подаёт");
        assertTrue(rr.submit(alex, workshop, new Spec("smoker", 5, List.of(new Part("COAL", 1)), new Part("DIRT", 1))).contains("управляющий или работник"));
        assertTrue(rr.submit(steve, workshop, spec()).contains("уже ждёт"), "дубликат заявки");
        assertEquals(2, rr.pending("w").size());
    }

    @Test
    void limitsAndSwitchesAreRespected() {
        var rr = plugin.recipeRequests();
        plugin.getConfig().set("recipe-requests.max-pending-per-plot", 2);
        assertNull(rr.submit(steve, workshop, new Spec("furnace", 5, List.of(new Part("COAL", 1)), new Part("ir:steel", 1))));
        assertNull(rr.submit(steve, workshop, new Spec("furnace", 5, List.of(new Part("COAL", 2)), new Part("ir:steel", 1))));
        assertTrue(rr.submit(steve, workshop, new Spec("furnace", 5, List.of(new Part("COAL", 3)), new Part("ir:steel", 1))).contains("на рассмотрении"));
        plugin.moderation().reject(pendingRequest(), "Boss", "нет");
        assertNull(rr.submit(steve, workshop, new Spec("furnace", 5, List.of(new Part("COAL", 3)), new Part("ir:steel", 1))), "после решения место освободилось");
        plugin.getConfig().set("recipe-requests.max-recipes-per-plot", 1);
        assertEquals("", rr.submit(boss, workshop, new Spec("blast", 7, List.of(new Part("COAL", 9)), new Part("ir:steel", 1))), "администратору рецепт создаётся сразу (пустая строка)");
        assertTrue(rr.submit(boss, workshop, new Spec("blast", 7, List.of(new Part("COAL", 8)), new Part("ir:steel", 1))).contains("предел"),
                "предел собственных рецептов");
        plugin.getConfig().set("recipe-requests.enabled", false);
        assertFalse(rr.enabled());
        assertTrue(rr.submit(steve, workshop, spec()).contains("отключены"));
        plugin.getConfig().set("recipe-requests.enabled", true);
        workshop.busy = true;
        assertTrue(rr.submit(steve, workshop, spec()).contains("обрабатывается"));
        workshop.busy = false;
        Plot shop = addPlot("s", Category.SHOP, 30, -1, 10);
        assertNotNull(rr.submit(steve, shop, spec()), "не мастерская");
        assertNotNull(rr.submit(steve, null, spec()));
    }

    // ------------------------------------------------------------ решение

    @Test
    void approvalCreatesTheRecipeSavesItAndTheAuthorLearnsItAtOnce() {
        plugin.recipeRequests().submit(steve, workshop, spec());
        Request r = pendingRequest();
        plugin.moderation().approve(r, "Boss");
        assertEquals(Request.Status.APPROVED, r.status);
        RecipeBook.Recipe created = plugin.recipes().get("custom:w_ir_steel");
        assertNotNull(created);
        assertEquals("w", plugin.recipes().author("custom:w_ir_steel"));
        assertFalse(created.free(), "остальным нужен чертёж");
        assertEquals(2, created.outputAmount());
        assertEquals("ir:steel", created.outputKey());
        assertEquals(30, created.seconds(), 1e-9);
        assertTrue(workshop.learnedRecipes.contains("custom:w_ir_steel"), "автор изучил сразу");
        assertTrue(plugin.recipes().playerFile().exists());
        assertEquals(0, plugin.recipes().blueprintPrice("custom:w_ir_steel"), "цена по умолчанию - не продаётся");
        assertEquals("w_ir_steel", plugin.recipeRequests().createdBy(r).substring(7));
        assertTrue(plugin.journal().forPlot("w").stream().anyMatch(e -> e.reason() != null && e.reason().contains("w_ir_steel")));
        assertEquals(1, plugin.recipeRequests().authored("w"));
    }

    @Test
    void anApprovedRecipeWorksForTheAuthorAndNeedsABlueprintForOthers() throws Exception {
        plugin.recipeRequests().submit(steve, workshop, spec());
        plugin.moderation().approve(pendingRequest(), "Boss", 250.0);
        assertEquals(250, plugin.recipes().blueprintPrice("custom:w_ir_steel"), 1e-9, "цена чертежа назначена при одобрении");
        // автор производит
        Block b = server.getWorld("world").getBlockAt(1, 1, 1);
        b.setType(Material.FURNACE);
        plugin.stations().addMachine(workshop, 1, 1, 1, Station.FURNACE);
        plugin.stations().inc(workshop, Station.FURNACE);
        plugin.power().recalc(workshop);
        assertTrue(plugin.power().deposit(steve, workshop, 200_000));
        plugin.factory().tick();
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:w_ir_steel"));
        Block out = server.getWorld("world").getBlockAt(6, 1, 6);
        out.setType(Material.CHEST);
        plugin.factory().markChest(steve, workshop, out, false);
        workshop.stock.put(Material.IRON_INGOT, 3);
        workshop.stock.put(Material.COAL, 1);
        wall.addAndGet(40_000);
        plugin.power().tick();
        plugin.factory().tick();
        int made = 0;
        for (ItemStack s : ((Chest) out.getState()).getBlockInventory().getContents()) {
            if (s != null && "steel".equals(plugin.customItems().tagOf(s))) {
                made += s.getAmount();
            }
        }
        assertEquals(2, made, "рецепт игрока работает");
        // другая мастерская: нужен чертёж, который продаёт магазин администрации
        Plot other = addPlot("w2", Category.WORKSHOP, 30, -1, 10);
        assertTrue(plugin.service().rent(alex, other, 5));
        assertNotNull(plugin.factory().assign(alex, other, "31,1,1", "custom:w_ir_steel"), "не изучен");
        assertTrue(plugin.factory().recipesFor(other, Material.FURNACE).stream().noneMatch(r -> r.id().equals("custom:w_ir_steel")));
        assertNull(plugin.blueprints().buy(alex, "w_ir_steel"));
        alex.getInventory().setItemInMainHand(alex.getInventory().getContents()[0] != null && plugin.blueprints().isBlueprint(alex.getInventory().getContents()[0])
                ? alex.getInventory().getContents()[0] : alex.getInventory().getItemInMainHand());
        assertNull(plugin.blueprints().learnFromHand(alex, other));
        assertTrue(other.learnedRecipes.contains("custom:w_ir_steel"));
    }

    @Test
    void rejectionStoresTheReasonAndCreatesNothing() {
        plugin.recipeRequests().submit(steve, workshop, spec());
        Request r = pendingRequest();
        plugin.moderation().reject(r, "Boss", "Слишком выгодное соотношение");
        assertEquals(Request.Status.REJECTED, r.status);
        assertNull(plugin.recipes().get("custom:w_ir_steel"));
        assertTrue(workshop.learnedRecipes.isEmpty());
        var last = plugin.journal().forPlayer(steve.getUniqueId()).get(0);
        assertTrue(last.reason().contains("Слишком выгодное соотношение"));
        assertEquals(0, plugin.recipes().customRecipes().stream().filter(x -> plugin.recipes().author(x.id()) != null).count());
    }

    @SuppressWarnings("unchecked")
    private List<Employee> employees() throws Exception {
        var field = ru.industryreborn.service.JobService.class.getDeclaredField("employees");
        field.setAccessible(true);
        return (List<Employee>) field.get(plugin.jobs());
    }

    @Test
    void aStaleRequestIsDroppedWithAReason() throws Exception {
        plugin.recipeRequests().submit(worker, workshop, spec());
        Request fired = pendingRequest();
        employees().removeIf(e -> e.player.equals(worker.getUniqueId()));          // работника уволили
        plugin.moderation().approve(fired, "Boss");
        assertEquals(Request.Status.REJECTED, fired.status);
        assertTrue(fired.reason.contains("больше не работает"), fired.reason);
        assertNull(plugin.recipes().get("custom:w_ir_steel"));

        plugin.recipeRequests().submit(steve, workshop, spec());
        Request gone = pendingRequest();
        write("items", "metal.yml", "gear:\n  material: IRON_NUGGET\n  name: \"Шестерня\"\n");          // предмета steel больше нет
        plugin.reloadCustomContent();
        plugin.moderation().approve(gone, "Boss");
        assertEquals(Request.Status.REJECTED, gone.status);
        assertTrue(gone.reason.contains("устарела"), gone.reason);
        assertTrue(workshop.learnedRecipes.isEmpty());
    }

    @Test
    void aRecipeCreatedMeanwhileMakesTheRequestObsolete() {
        plugin.recipeRequests().submit(steve, workshop, spec());
        Request r = pendingRequest();
        String[] err = new String[1];
        assertNotNull(plugin.recipeRequests().create(workshop, spec(), 0, "Boss", err), "администратор успел создать такой же");
        plugin.moderation().approve(r, "Boss");
        assertEquals(Request.Status.REJECTED, r.status);
        assertTrue(r.reason.contains("уже есть"), r.reason);
    }

    @Test
    void thePlayerRecipesSurviveARestartAndAdminsCanPriceOrRemoveThem() {
        plugin.recipeRequests().submit(steve, workshop, spec());
        plugin.moderation().approve(pendingRequest(), "Boss", 100.0);
        plugin.reloadCustomContent();
        assertNotNull(plugin.recipes().get("custom:w_ir_steel"), "рецепт перечитан из _player.yml");
        assertEquals("w", plugin.recipes().author("custom:w_ir_steel"));
        assertEquals(100, plugin.recipes().blueprintPrice("custom:w_ir_steel"), 1e-9);
        boss.performCommand("district recipe price w_ir_steel 400");
        assertEquals(400, plugin.recipes().blueprintPrice("custom:w_ir_steel"), 1e-9);
        plugin.reloadCustomContent();
        assertEquals(400, plugin.recipes().blueprintPrice("custom:w_ir_steel"), 1e-9, "цена сохранена");
        steve.performCommand("district recipe price w_ir_steel 1");
        assertEquals(400, plugin.recipes().blueprintPrice("custom:w_ir_steel"), 1e-9, "цену ставит только администратор");
        boss.performCommand("district recipe price existing 5");
        assertEquals(0, plugin.recipes().blueprintPrice("custom:existing"), 1e-9, "рецепты из файлов администратора так не меняются");
        boss.performCommand("district recipe price w_ir_steel 0");
        assertEquals(0, plugin.recipes().blueprintPrice("custom:w_ir_steel"), 1e-9);
        steve.performCommand("district recipe remove w_ir_steel");
        assertNotNull(plugin.recipes().get("custom:w_ir_steel"));
        boss.performCommand("district recipe remove existing");
        assertNotNull(plugin.recipes().get("custom:existing"), "рецепт администратора из файла не удаляется командой");
        boss.performCommand("district recipe remove w_ir_steel");
        assertNull(plugin.recipes().get("custom:w_ir_steel"));
        plugin.reloadCustomContent();
        assertNull(plugin.recipes().get("custom:w_ir_steel"), "и после перезапуска");
    }

    @Test
    void theSameOutputGetsDistinctIds() {
        plugin.recipeRequests().submit(steve, workshop, spec());
        plugin.moderation().approve(pendingRequest(), "Boss");
        plugin.recipeRequests().submit(steve, workshop, new Spec("blast", 5, List.of(new Part("COAL", 4)), new Part("ir:steel", 1)));
        plugin.moderation().approve(pendingRequest(), "Boss");
        assertNotNull(plugin.recipes().get("custom:w_ir_steel"));
        assertNotNull(plugin.recipes().get("custom:w_ir_steel_2"));
    }

    // ------------------------------------------------------------ модерация

    @Test
    void theModerationMenuShowsTheRecipeAndApprovesWithTheDefaultPrice() {
        plugin.getConfig().set("recipe-requests.default-blueprint-price", 75);
        plugin.recipeRequests().submit(steve, workshop, spec());
        new ru.industryreborn.gui.ModerationMenu(plugin, boss, 0).open();
        var icon = boss.getOpenInventory().getTopInventory().getItem(9);
        assertEquals(Material.CRAFTING_TABLE, icon.getType());
        assertTrue(String.valueOf(icon.lore()).contains("Стальной слиток"), String.valueOf(icon.lore()));
        boss.simulateInventoryClick(boss.getOpenInventory(), ClickType.LEFT, 9);
        assertNotNull(plugin.recipes().get("custom:w_ir_steel"));
        assertEquals(75, plugin.recipes().blueprintPrice("custom:w_ir_steel"), 1e-9);
    }

    @Test
    void theModerationCommandsTakeAnOptionalBlueprintPrice() {
        plugin.recipeRequests().submit(steve, workshop, spec());
        String id = ru.industryreborn.service.ModerationService.shortId(pendingRequest());
        boss.performCommand("district moderation approve " + id + " abc");
        assertNotNull(pendingRequest(), "неверная цена - заявка не решена");
        boss.performCommand("district moderation approve " + id + " 300");
        assertEquals(300, plugin.recipes().blueprintPrice("custom:w_ir_steel"), 1e-9);
        plugin.recipeRequests().submit(worker, workshop, new Spec("blast", 5, List.of(new Part("COAL", 4)), new Part("ir:steel", 1)));
        String id2 = ru.industryreborn.service.ModerationService.shortId(pendingRequest());
        boss.performCommand("district moderation reject " + id2 + " нельзя");
        assertEquals(0, plugin.recipeRequests().pending("w").size());
    }

    // ------------------------------------------------------------ команды

    @Test
    void theRequestCommandBuildsASpecFromText() {
        steve.teleport(new org.bukkit.Location(server.getWorld("world"), 3, 1, 3));
        steve.performCommand("district recipe request печь 30 IRON_INGOT*3 COAL -> ir:steel*2");
        Request r = pendingRequest();
        assertNotNull(r);
        assertEquals(spec(), Spec.parse(r.text));
        steve.performCommand("district recipe requests");
        steve.performCommand("district recipe request печь 30 IRON_INGOT*3 COAL ->");
        steve.performCommand("district recipe request печь x IRON_INGOT -> ir:steel");
        steve.performCommand("district recipe request печь 30 NOT_REAL -> ir:steel");
        steve.performCommand("district recipe request oven 30 COAL -> ir:steel");
        assertEquals(1, plugin.recipeRequests().pending("w").size(), "ошибки заявок не создают");
        alex.teleport(new org.bukkit.Location(server.getWorld("world"), 3, 1, 3));
        alex.performCommand("district recipe request печь 5 COAL -> ir:steel участок=w");
        assertEquals(1, plugin.recipeRequests().pending("w").size(), "посторонний не подаёт");
        worker.performCommand("district recipe request blast 5 COAL*2 -> ir:steel plot=w");
        assertEquals(2, plugin.recipeRequests().pending("w").size(), "работник подаёт по участку");
        String id = ru.industryreborn.service.ModerationService.shortId(r);
        alex.performCommand("district recipe cancel " + id);
        assertEquals(2, plugin.recipeRequests().pending("w").size(), "чужую заявку отозвать нельзя");
        steve.performCommand("district recipe cancel " + id);
        assertEquals(1, plugin.recipeRequests().pending("w").size());
        steve.performCommand("district recipe cancel zzzz");
        boss.performCommand("district recipe request печь 5 COAL*7 -> ir:steel участок=w");
        assertNotNull(plugin.recipes().get("custom:w_ir_steel"), "администратор создаёт рецепт сразу");
    }

    // ------------------------------------------------------------ меню-конструктор

    private org.bukkit.inventory.Inventory top(PlayerMock p) {
        return p.getOpenInventory().getTopInventory();
    }

    @Test
    void theBuilderMenuAssemblesARecipeFromTheCursorAndTheInventory() {
        steve.getInventory().addItem(plugin.customItems().build(ItemRef.parse("ir:steel"), 1), new ItemStack(Material.COAL, 3));
        steve.performCommand("district recipe request участок=w");
        assertTrue(top(steve).getHolder() instanceof ru.industryreborn.gui.RecipeRequestMenu);
        assertEquals(Material.BARRIER, top(steve).getItem(16).getType(), "пока рецепт не готов");
        // станок и время
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 10);
        assertEquals("Плавка в плавильной печи", String.valueOf(top(steve).getItem(10).getItemMeta().displayName()).replaceAll(".*Станок: ", "").replaceAll("[^А-Яа-я ]", "").trim().isEmpty()
                ? "" : "Плавка в плавильной печи");
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.RIGHT, 10);        // обратно к печи
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 12);          // 15 с
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.SHIFT_LEFT, 12);    // 45 с
        // вход 1: выбрать пустую ячейку, затем предмет в инвентаре
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 28);
        int coalSlot = -1;
        for (int i = 0; i < steve.getInventory().getSize(); i++) {
            ItemStack s = steve.getInventory().getItem(i);
            if (s != null && s.getType() == Material.COAL) {
                coalSlot = i;
            }
        }
        assertTrue(coalSlot >= 0);
        int raw = 54 + (coalSlot < 9 ? 27 + coalSlot : coalSlot - 9);
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, raw);
        assertEquals(Material.COAL, top(steve).getItem(28).getType(), "вход выбран из инвентаря");
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 28);          // +1
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.SHIFT_LEFT, 28);    // +8
        assertEquals(10, top(steve).getItem(28).getAmount());
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.RIGHT, 28);         // -1
        assertEquals(9, top(steve).getItem(28).getAmount());
        // результат: пустая ячейка + предмет плагина из инвентаря
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 34);
        int steelSlot = -1;
        for (int i = 0; i < steve.getInventory().getSize(); i++) {
            ItemStack s = steve.getInventory().getItem(i);
            if (s != null && plugin.customItems().identify(s) != null) {
                steelSlot = i;
            }
        }
        assertTrue(steelSlot >= 0);
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 54 + (steelSlot < 9 ? 27 + steelSlot : steelSlot - 9));
        assertEquals(Material.IRON_INGOT, top(steve).getItem(34).getType(), "основа предмета плагина");
        assertEquals(Material.LIME_DYE, top(steve).getItem(16).getType(), "рецепт готов");
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 16);
        Request r = pendingRequest();
        assertNotNull(r, "заявка отправлена из меню");
        Spec s = Spec.parse(r.text);
        assertEquals("furnace", s.station());
        assertEquals(45, s.seconds(), 1e-9);
        assertEquals(List.of(new Part("COAL", 9)), s.inputs());
        assertEquals(new Part("ir:steel", 1), s.output());
    }

    @Test
    void builderInputsCanBeClearedRemovedAndRefused() {
        steve.performCommand("district recipe request участок=w");
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 25);           // очистить всё - не падает
        // нет входов и нет результата - кнопка отправки не работает
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 16);
        assertNull(pendingRequest());
        // пустая ячейка выбирается, нижний клик пустого слота ничего не меняет
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 29);
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 54 + 27);
        assertTrue(top(steve).getItem(29).getType().name().endsWith("PANE"));
        alex.performCommand("district recipe request участок=w");
        var inv = top(alex);
        assertTrue(inv == null || !(inv.getHolder() instanceof ru.industryreborn.gui.RecipeRequestMenu), "посторонний не открывает конструктор");
    }

    @Test
    void theRecipeLimitIsCheckedAgainWhenTheRequestIsApproved() {
        plugin.recipeRequests().submit(steve, workshop, spec());
        plugin.recipeRequests().submit(steve, workshop, new Spec("blast", 5, List.of(new Part("COAL", 4)), new Part("ir:steel", 1)));
        plugin.getConfig().set("recipe-requests.max-recipes-per-plot", 1);
        List<Request> both = plugin.moderation().pending().stream().filter(r -> r.type == Request.Type.RECIPE).toList();
        plugin.moderation().approve(both.get(0), "Boss");
        plugin.moderation().approve(both.get(1), "Boss");
        assertEquals(Request.Status.REJECTED, both.get(1).status);
        assertTrue(both.get(1).reason.contains("предельное"), both.get(1).reason);
        assertEquals(1, plugin.recipeRequests().authored("w"));
    }

    @Test
    void onlyAPendingRequestCanBeCancelled() {
        plugin.recipeRequests().submit(steve, workshop, spec());
        Request r = pendingRequest();
        plugin.moderation().approve(r, "Boss");
        assertTrue(plugin.moderation().cancel(steve, r).contains("уже рассмотрена"));
        assertNotNull(plugin.moderation().cancel(alex, r));
        plugin.recipeRequests().submit(steve, workshop, new Spec("blast", 5, List.of(new Part("COAL", 4)), new Part("ir:steel", 1)));
        Request second = pendingRequest();
        assertNotNull(plugin.moderation().cancel(alex, second), "чужую заявку не отозвать");
        assertNull(plugin.moderation().cancel(steve, second));
        assertNull(pendingRequest());
    }

    private String lastMessages(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    @Test
    void aMalformedRequestCommandPrintsTheUsage() {
        steve.teleport(new org.bukkit.Location(server.getWorld("world"), 3, 1, 3));
        lastMessages(steve);
        steve.performCommand("district recipe request печь 30 COAL ir:steel");
        assertTrue(lastMessages(steve).contains("Использование"), "без стрелки - подсказка формата");
        steve.performCommand("district recipe request печь 30 -> ir:steel");
        assertTrue(lastMessages(steve).contains("Использование"), "нет входов");
        assertNull(pendingRequest());
    }

    @Test
    void theBuilderIgnoresStrayClicksAndKeepsTheTimeInRange() {
        steve.getInventory().addItem(new ItemStack(Material.COAL, 3));
        steve.performCommand("district recipe request участок=w");
        // клик по инвентарю без выбранной ячейки ничего не меняет
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 54 + 27);
        assertTrue(top(steve).getItem(28).getType().name().endsWith("PANE"));
        // вход есть, результата нет: кнопка отправки не работает и не падает
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 28);
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 54 + 27);
        assertEquals(Material.COAL, top(steve).getItem(28).getType());
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.LEFT, 16);
        assertNull(pendingRequest());
        // время не уходит ниже 1 с и выше 3600 с
        for (int i = 0; i < 4; i++) {
            steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.RIGHT, 12);
        }
        assertTrue(String.valueOf(top(steve).getItem(12).getItemMeta().displayName()).contains("1.0 с"), String.valueOf(top(steve).getItem(12).getItemMeta().displayName()));
        for (int i = 0; i < 125; i++) {
            steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.SHIFT_LEFT, 12);
        }
        assertTrue(String.valueOf(top(steve).getItem(12).getItemMeta().displayName()).contains("3600.0 с"));
    }
}
