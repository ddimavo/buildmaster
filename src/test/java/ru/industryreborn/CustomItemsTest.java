package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.items.ExternalItems;
import ru.industryreborn.items.ItemRef;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Station;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.support.PluginTestBase;

/** Предметы плагина и Nexo: ссылки, метки, файлы, рецепты администратора, склад и производство. */
class CustomItemsTest extends PluginTestBase {

    /** Поддельный Nexo: предметы steel_ingot и gear, id хранится в названии предмета. */
    private static final class FakeNexo implements ExternalItems {
        boolean loaded = true;

        @Override
        public boolean available() {
            return loaded;
        }

        @Override
        public String idOf(ItemStack stack) {
            if (!loaded || stack == null || !stack.hasItemMeta() || stack.getItemMeta().displayName() == null) {
                return null;
            }
            String name = PlainTextComponentSerializer.plainText().serialize(stack.getItemMeta().displayName());
            return name.startsWith("NEXO:") ? name.substring(5) : null;
        }

        @Override
        public ItemStack build(String id) {
            if (!loaded || !exists(id)) {
                return null;
            }
            ItemStack s = new ItemStack(Material.IRON_NUGGET);
            s.editMeta(m -> m.displayName(Component.text("NEXO:" + id)));
            return s;
        }

        @Override
        public boolean exists(String id) {
            return loaded && (id.equals("steel_ingot") || id.equals("gear"));
        }
    }

    private final AtomicLong wall = new AtomicLong(1_000_000);
    private final FakeNexo nexo = new FakeNexo();
    private PlayerMock steve;
    private PlayerMock boss;
    private Plot workshop;

    private void write(String dir, String name, String text) throws Exception {
        File d = new File(plugin.getDataFolder(), dir);
        d.mkdirs();
        Files.writeString(new File(d, name).toPath(), text, StandardCharsets.UTF_8);
    }

    private void items() throws Exception {
        write("items", "metal.yml", """
                steel:
                  material: IRON_INGOT
                  name: "<gray>Стальной слиток"
                  lore: ["Прочный сплав"]
                  custom_model_data: 1001
                gear:
                  material: IRON_NUGGET
                  name: "Шестерня"
                  nexo: gear
                """);
    }

    private void recipes() throws Exception {
        write("recipes", "metal.yml", """
                steel_ingot:
                  station: furnace
                  seconds: 20
                  category: "Металлы"
                  free: true
                  inputs:
                    - {item: IRON_INGOT, amount: 3}
                    - {item: [COAL, CHARCOAL], amount: 1}
                  output: {item: ir:steel, amount: 2}
                gear_press:
                  station: furnace
                  seconds: 10
                  inputs:
                    - {item: ir:steel, amount: 2}
                  output: {item: nexo:steel_ingot, amount: 1}
                """);
    }

    @BeforeEach
    void setUp() throws Exception {
        plugin.customItems().setExternal(nexo);
        plugin.getConfig().set("electricity.max-clock-step-seconds", 10_000_000);
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.workshop", 0);
        plugin.power().setTimeSource(wall::get);
        server.getWorld("world").loadChunk(0, 0);
        steve = player("Steve", 1_000_000);
        boss = admin("Boss");
        workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        assertTrue(plugin.service().rent(steve, workshop, 10));
        Block b = server.getWorld("world").getBlockAt(1, 1, 1);
        b.setType(Material.FURNACE);
        plugin.stations().addMachine(workshop, 1, 1, 1, Station.FURNACE);
        plugin.stations().inc(workshop, Station.FURNACE);
        plugin.power().recalc(workshop);
        assertTrue(plugin.power().deposit(steve, workshop, 200_000));
        plugin.factory().tick();
    }

    private void pass(long seconds) {
        wall.addAndGet(seconds * 1000);
        plugin.power().tick();
        plugin.factory().tick();
    }

    // ------------------------------------------------------------ ссылки

    @Test
    void referencesParseLikeNexoRecipes() {
        assertEquals(new ItemRef(ItemRef.Kind.VANILLA, "IRON_INGOT"), ItemRef.parse("iron_ingot"));
        assertEquals(new ItemRef(ItemRef.Kind.VANILLA, "IRON_INGOT"), ItemRef.parse("minecraft:iron_ingot"));
        assertEquals(new ItemRef(ItemRef.Kind.PLUGIN, "steel"), ItemRef.parse("ir:Steel"));
        assertEquals(new ItemRef(ItemRef.Kind.NEXO, "Steel_Ingot"), ItemRef.parse("nexo:Steel_Ingot"));
        assertNull(ItemRef.parse("ir:"));
        assertNull(ItemRef.parse("nexo:"));
        assertNull(ItemRef.parse("NOT_A_MATERIAL"));
        assertNull(ItemRef.parse("AIR"));
        assertNull(ItemRef.parse(" "));
        assertEquals("IRON_INGOT", ItemRef.parse("iron_ingot").key());
        assertEquals("ir:steel", ItemRef.parse("ir:steel").key());
        assertEquals("nexo:gear", ItemRef.parse("nexo:gear").key());
        assertTrue(ItemRef.isCustomKey("ir:steel") && ItemRef.isCustomKey("nexo:x") && !ItemRef.isCustomKey("IRON_INGOT"));
    }

    // ------------------------------------------------------------ предметы и метки

    @Test
    void filesDefineItemsAndBadEntriesAreReported() throws Exception {
        items();
        write("items", "bad.yml", """
                Bad Id:
                  material: IRON_INGOT
                nomat:
                  material: NOT_A_MATERIAL
                steel:
                  material: GOLD_INGOT
                """);
        List<String> errors = plugin.customItems().load();
        assertEquals(2, plugin.customItems().all().size(), "steel и gear");
        assertEquals(3, errors.size(), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("уже определён")), "дубликат id");
        assertEquals(Material.GOLD_INGOT, plugin.customItems().get("steel").material(), "файлы читаются по алфавиту: первое определение (bad.yml) остаётся");
    }

    @Test
    void aBuiltItemCarriesTheTagNameAndLore() throws Exception {
        items();
        plugin.customItems().load();
        ItemStack s = plugin.customItems().build(ItemRef.parse("ir:steel"), 5);
        assertEquals(Material.IRON_INGOT, s.getType());
        assertEquals(5, s.getAmount());
        assertEquals("steel", plugin.customItems().tagOf(s));
        assertEquals("Стальной слиток", PlainTextComponentSerializer.plainText().serialize(s.getItemMeta().displayName()));
        assertEquals(1, s.getItemMeta().lore().size());
        assertEquals(new ItemRef(ItemRef.Kind.PLUGIN, "steel"), plugin.customItems().identify(s));
        assertNull(plugin.customItems().build(ItemRef.parse("ir:nope"), 1));
    }

    @Test
    void renamingAVanillaItemNeverMakesItACustomOne() throws Exception {
        items();
        plugin.customItems().load();
        ItemStack fake = new ItemStack(Material.IRON_INGOT);
        fake.editMeta(m -> m.displayName(Component.text("Стальной слиток")));
        assertNull(plugin.customItems().identify(fake), "название ничего не значит");
        ItemStack wrongTag = new ItemStack(Material.IRON_INGOT);
        plugin.customItems().tag(wrongTag, "unknown_item");
        assertNull(plugin.customItems().identify(wrongTag), "метка с неизвестным id не считается");
        assertNull(plugin.customItems().identify(new ItemStack(Material.IRON_INGOT)));
        assertNull(plugin.customItems().identify(null));
    }

    @Test
    void nexoItemsAreRecognizedThroughTheBridgeAndTaggedWhenWeOwnThem() throws Exception {
        items();
        plugin.customItems().load();
        ItemStack gear = plugin.customItems().build(ItemRef.parse("ir:gear"), 2);
        assertEquals(Material.IRON_NUGGET, gear.getType());
        assertEquals("gear", plugin.customItems().tagOf(gear), "предмет Nexo получил нашу метку");
        assertEquals("NEXO:gear", PlainTextComponentSerializer.plainText().serialize(gear.getItemMeta().displayName()), "внешний вид взят из Nexo, а не из определения");
        assertEquals(new ItemRef(ItemRef.Kind.PLUGIN, "gear"), plugin.customItems().identify(gear), "метка важнее Nexo");
        ItemStack raw = plugin.customItems().build(ItemRef.parse("nexo:steel_ingot"), 3);
        assertEquals(3, raw.getAmount());
        assertEquals(new ItemRef(ItemRef.Kind.NEXO, "steel_ingot"), plugin.customItems().identify(raw));
        assertNull(plugin.customItems().build(ItemRef.parse("nexo:missing"), 1));
        nexo.loaded = false;
        assertNull(plugin.customItems().build(ItemRef.parse("nexo:steel_ingot"), 1), "Nexo ещё не загрузился");
        ItemStack fallback = plugin.customItems().build(ItemRef.parse("ir:gear"), 1);
        assertEquals(Material.IRON_NUGGET, fallback.getType(), "без Nexo предмет строится из определения");
        assertEquals(new ItemRef(ItemRef.Kind.PLUGIN, "gear"), plugin.customItems().identify(fallback));
    }

    @Test
    void theNexoExportMarksEveryItemWithPersistentData() throws Exception {
        items();
        plugin.customItems().load();
        File f = plugin.customItems().exportNexo();
        String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains("steel:") && text.contains("industryreborn:item"), text);
        assertTrue(text.contains("gear:") && text.contains("value: gear"), "предмет с nexo: id идёт под id Nexo");
        assertTrue(text.contains("Стальной слиток"));
    }

    // ------------------------------------------------------------ рецепты

    @Test
    void recipesLoadFromFilesWithAllKindsOfReferences() throws Exception {
        items();
        recipes();
        plugin.customItems().load();
        assertTrue(plugin.recipes().loadCustom().isEmpty());
        RecipeBook.Recipe steel = plugin.recipes().get("custom:steel_ingot");
        assertNotNull(steel);
        assertTrue(steel.custom() && steel.free());
        assertEquals("furnace", steel.family());
        assertEquals(2, steel.outputAmount());
        assertEquals("ir:steel", steel.outputKey());
        assertEquals(Material.IRON_INGOT, steel.output(), "значок - основа предмета");
        assertEquals(List.of(Material.COAL, Material.CHARCOAL), steel.inputs().get(1).options());
        RecipeBook.Recipe press = plugin.recipes().get("custom:gear_press");
        assertEquals(List.of("ir:steel"), press.inputs().get(0).customs());
        assertEquals("nexo:steel_ingot", press.outputKey());
        assertFalse(press.free(), "по умолчанию нужен чертёж");
        assertEquals("Стальной слиток", plugin.recipes().outputName(steel));
        assertEquals(2, plugin.recipes().customRecipes().size());
    }

    @Test
    void badRecipesAreSkippedWithAMessage() throws Exception {
        items();
        plugin.customItems().load();
        write("recipes", "bad.yml", """
                ok_one:
                  station: furnace
                  seconds: 5
                  inputs: [{item: COAL, amount: 1}]
                  output: {item: ir:steel}
                bad_station:
                  station: oven
                  seconds: 5
                  inputs: [{item: COAL}]
                  output: {item: ir:steel}
                bad_time:
                  station: furnace
                  seconds: 0
                  inputs: [{item: COAL}]
                  output: {item: ir:steel}
                no_inputs:
                  station: furnace
                  seconds: 5
                  output: {item: ir:steel}
                unknown_input:
                  station: furnace
                  seconds: 5
                  inputs: [{item: ir:nope}]
                  output: {item: ir:steel}
                unknown_output:
                  station: furnace
                  seconds: 5
                  inputs: [{item: COAL}]
                  output: {item: NOT_REAL}
                too_many:
                  station: furnace
                  seconds: 5
                  inputs: [{item: COAL, amount: 99}]
                  output: {item: ir:steel}
                missing_nexo:
                  station: furnace
                  seconds: 5
                  inputs: [{item: COAL}]
                  output: {item: nexo:no_such_item}
                """);
        List<String> errors = plugin.recipes().loadCustom();
        assertEquals(1, plugin.recipes().customRecipes().size(), "остался только ok_one");
        assertEquals(7, errors.size(), errors.toString());
        nexo.loaded = false;
        assertNull(plugin.recipes().addCustom("late_nexo", yaml("station: furnace\nseconds: 5\ninputs: [{item: COAL}]\noutput: {item: nexo:no_such_item}")),
                "пока Nexo не загрузился, его предметы не проверяются");
    }

    private org.bukkit.configuration.ConfigurationSection yaml(String text) {
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.StringReader(text));
    }

    @Test
    void learningDecidesWhichRecipesAWorkshopSees() throws Exception {
        items();
        recipes();
        plugin.customItems().load();
        plugin.recipes().loadCustom();
        var ids = plugin.factory().recipesFor(workshop, Material.FURNACE).stream().map(RecipeBook.Recipe::id).toList();
        assertTrue(ids.contains("custom:steel_ingot"), "свободный рецепт виден сразу");
        assertFalse(ids.contains("custom:gear_press"), "рецепт с чертежом скрыт");
        assertNotNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:gear_press"), "не изучен");
        workshop.learnedRecipes.add("custom:gear_press");
        assertTrue(plugin.factory().recipesFor(workshop, Material.FURNACE).stream().anyMatch(r -> r.id().equals("custom:gear_press")));
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:gear_press"));
        assertTrue(plugin.factory().recipesFor(workshop, Material.ANVIL).stream().noneMatch(RecipeBook.Recipe::custom), "рецепты другого станка не показываются");
    }

    @Test
    void learnedRecipesSurviveARestartAndAClosedWorkshop() throws Exception {
        workshop.learnedRecipes.add("custom:gear_press");
        workshop.customStock.put("ir:steel", 7);
        plugin.plots().save();
        var fresh = new ru.industryreborn.service.PlotManager(plugin);
        fresh.load();
        assertTrue(fresh.get("w").learnedRecipes.contains("custom:gear_press"));
        assertEquals(7, fresh.get("w").customStock.get("ir:steel"));
        plugin.factory().closeWorkshop(workshop, steve.getUniqueId(), false, "тест");
        assertTrue(workshop.learnedRecipes.contains("custom:gear_press"), "чертежи остаются за предприятием");
        assertTrue(workshop.customStock.isEmpty(), "склад возвращён");
    }

    // ------------------------------------------------------------ производство

    @Test
    void aWorkshopProducesACustomItemFromVanillaIngredients() throws Exception {
        items();
        recipes();
        plugin.customItems().load();
        plugin.recipes().loadCustom();
        Block out = server.getWorld("world").getBlockAt(6, 1, 6);
        out.setType(Material.CHEST);
        assertNull(plugin.factory().markChest(steve, workshop, out, false));
        workshop.stock.put(Material.IRON_INGOT, 6);
        workshop.stock.put(Material.COAL, 2);
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:steel_ingot"));
        pass(45);
        var inv = ((Chest) out.getState()).getBlockInventory();
        int steel = 0;
        for (ItemStack s : inv.getContents()) {
            if (s != null && "steel".equals(plugin.customItems().tagOf(s))) {
                steel += s.getAmount();
                assertEquals(Material.IRON_INGOT, s.getType());
            }
        }
        assertEquals(4, steel, "две партии по 2 слитка, метка стоит на каждом");
        assertEquals(0, workshop.stock.getOrDefault(Material.IRON_INGOT, 0), "сырьё израсходовано");
        assertEquals(0, plugin.factory().stock(workshop, Material.COAL));
        assertEquals(0, count(inv, Material.COAL));
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

    @Test
    void customIngredientsComeFromTheRawChestOnlyWhenTagged() throws Exception {
        items();
        recipes();
        plugin.customItems().load();
        plugin.recipes().loadCustom();
        workshop.learnedRecipes.add("custom:gear_press");
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:gear_press"));
        Block raw = server.getWorld("world").getBlockAt(5, 1, 5);
        raw.setType(Material.CHEST);
        assertNull(plugin.factory().markChest(steve, workshop, raw, true));
        var inv = ((Chest) raw.getState()).getBlockInventory();
        inv.addItem(plugin.customItems().build(ItemRef.parse("ir:steel"), 10));
        ItemStack fake = new ItemStack(Material.IRON_INGOT, 5);
        fake.editMeta(m -> m.displayName(Component.text("Стальной слиток")));
        inv.addItem(fake, new ItemStack(Material.IRON_INGOT, 3));
        pass(1);
        assertEquals(10, plugin.factory().stockOf(workshop, "ir:steel"), "принят только предмет с меткой");
        assertEquals(0, plugin.factory().stock(workshop, Material.IRON_INGOT), "обычное железо рецепту не нужно");
        assertEquals(8, count(inv, Material.IRON_INGOT), "подделка и обычное железо остались в сундуке");
        assertEquals(10, plugin.factory().stockTotal(workshop), "склад считает и предметы плагина");
    }

    @Test
    void aTaggedItemNobodyNeedsStaysInTheChestAndNeverCountsAsPlainIron() throws Exception {
        items();
        recipes();
        plugin.customItems().load();
        plugin.recipes().loadCustom();
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:steel_ingot"));       // нужен обычный IRON_INGOT и уголь
        Block raw = server.getWorld("world").getBlockAt(5, 1, 5);
        raw.setType(Material.CHEST);
        assertNull(plugin.factory().markChest(steve, workshop, raw, true));
        var inv = ((Chest) raw.getState()).getBlockInventory();
        inv.addItem(plugin.customItems().build(ItemRef.parse("ir:steel"), 6), new ItemStack(Material.IRON_INGOT, 2));
        pass(1);
        assertEquals(0, plugin.factory().stockOf(workshop, "ir:steel"), "рецепту стальной слиток не нужен");
        assertEquals(2, plugin.factory().stock(workshop, Material.IRON_INGOT), "приняты только 2 обычных слитка, а помеченные - нет");
        assertEquals(6, count(inv, Material.IRON_INGOT), "помеченные слитки остались в сундуке");
    }

    @Test
    void aCustomRecipeCanProduceAndConsumeNexoItems() throws Exception {
        items();
        recipes();
        plugin.customItems().load();
        plugin.recipes().loadCustom();
        workshop.learnedRecipes.add("custom:gear_press");
        Block out = server.getWorld("world").getBlockAt(6, 1, 6);
        out.setType(Material.CHEST);
        plugin.factory().markChest(steve, workshop, out, false);
        workshop.customStock.put("ir:steel", 4);
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:gear_press"));
        pass(25);
        var inv = ((Chest) out.getState()).getBlockInventory();
        int made = 0;
        for (ItemStack s : inv.getContents()) {
            if (s != null && plugin.customItems().identify(s) != null && plugin.customItems().identify(s).key().equals("nexo:steel_ingot")) {
                made += s.getAmount();
            }
        }
        assertEquals(2, made, "две партии: 4 слитка → 2 предмета Nexo");
        assertEquals(0, plugin.factory().stockOf(workshop, "ir:steel"));
    }

    @Test
    void anUnavailableNexoItemPausesProductionAndKeepsTheOutput() throws Exception {
        items();
        recipes();
        plugin.customItems().load();
        plugin.recipes().loadCustom();
        workshop.learnedRecipes.add("custom:gear_press");
        workshop.customStock.put("ir:steel", 4);
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:gear_press"));
        nexo.loaded = false;
        assertEquals(ru.industryreborn.service.ProductionService.Status.UNAVAILABLE, plugin.factory().view(workshop, "1,1,1").status());
        pass(40);
        assertEquals(4, plugin.factory().stockOf(workshop, "ir:steel"), "ничего не израсходовано, пока предмет недоступен");
        nexo.loaded = true;
        pass(15);
        assertTrue(plugin.factory().stockOf(workshop, "ir:steel") < 4);
    }

    @Test
    void machineMenuAndCommandsShowCustomRecipes() throws Exception {
        items();
        recipes();
        plugin.customItems().load();
        plugin.recipes().loadCustom();
        boss.performCommand("district recipe list");
        boss.performCommand("district recipe info steel_ingot");
        boss.performCommand("district recipe learn w gear_press");
        assertTrue(workshop.learnedRecipes.contains("custom:gear_press"));
        boss.performCommand("district recipe forget w gear_press");
        assertFalse(workshop.learnedRecipes.contains("custom:gear_press"));
        steve.performCommand("district recipe learn w gear_press");
        assertFalse(workshop.learnedRecipes.contains("custom:gear_press"), "изучает только администратор");
        boss.performCommand("district item give Steve ir:steel 3");
        int got = 0;
        for (ItemStack s : steve.getInventory().getContents()) {
            if (s != null && "steel".equals(plugin.customItems().tagOf(s))) {
                got += s.getAmount();
            }
        }
        assertEquals(3, got);
        boss.performCommand("district item give Steve steel 2");
        steve.performCommand("district item give Steve steel 2");
        boss.performCommand("district item export-nexo");
        assertTrue(new File(plugin.getDataFolder(), "nexo-export/industryreborn_items.yml").exists());
        new ru.industryreborn.gui.MachineMenu(plugin, steve, workshop, "1,1,1", null, 0, null).open();
        assertNotNull(steve.getOpenInventory().getTopInventory().getItem(9));
    }
}
