package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.items.CustomItem;
import ru.industryreborn.items.ExternalItems;
import ru.industryreborn.items.ItemKind;
import ru.industryreborn.items.ItemRef;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.support.PluginTestBase;

/** Предметы Nexo с метками (инструменты и скины), магазин предметов, применение скинов и нетронутые экземпляры. */
class ToolsAndSkinsTest extends PluginTestBase {

    /** Поддельный Nexo: id хранится в названии предмета; строит заданные предметы. */
    private static final class FakeNexo implements ExternalItems {
        boolean loaded = true;

        @Override
        public boolean available() {
            return loaded;
        }

        @Override
        public String idOf(ItemStack s) {
            if (!loaded || s == null || !s.hasItemMeta() || s.getItemMeta().displayName() == null) {
                return null;
            }
            String n = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(s.getItemMeta().displayName());
            return n.startsWith("NEXO:") ? n.substring(5) : null;
        }

        @Override
        public ItemStack build(String id) {
            if (!loaded || !exists(id)) {
                return null;
            }
            ItemStack s = new ItemStack(id.contains("pick") ? Material.DIAMOND_PICKAXE : Material.PAPER);
            s.editMeta(m -> m.displayName(Component.text("NEXO:" + id)));
            return s;
        }

        @Override
        public boolean exists(String id) {
            return loaded && List.of("forge_pick", "ruby_skin", "gold_skin", "any_skin").contains(id);
        }
    }

    private final FakeNexo nexo = new FakeNexo();
    private PlayerMock steve;
    private PlayerMock alex;
    private PlayerMock boss;
    private File nexoDir;

    private void nexoFile(String name, String text) throws Exception {
        nexoDir.mkdirs();
        Files.writeString(new File(nexoDir, name).toPath(), text, StandardCharsets.UTF_8);
    }

    private static final String NEXO_ITEMS = """
            forge_pick:
              itemname: "<gold>Кирка кузнеца"
              material: DIAMOND_PICKAXE
              PersistentData:
                - {type: STRING, key: "industryreborn:item", value: forge_pick}
                - {type: STRING, key: "industryreborn:kind", value: tool}
            ruby_skin:
              itemname: "<red>Скин Рубин"
              material: PAPER
              Components:
                item_model: nexo:ruby_skin_model
              PersistentData:
                - {type: STRING, key: "industryreborn:item", value: ruby}
                - {type: STRING, key: "industryreborn:kind", value: skin}
                - {type: STRING, key: "industryreborn:skin_for", value: "pickaxe,axe"}
            gold_skin:
              itemname: "Скин Золото"
              PersistentData:
                - {type: STRING, key: "industryreborn:item", value: gold}
                - {type: STRING, key: "industryreborn:kind", value: skin}
                - {type: STRING, key: "industryreborn:skin_for", value: "sword"}
            any_skin:
              itemname: "Скин Любой"
              PersistentData:
                - {type: STRING, key: "industryreborn:item", value: anyskin}
                - {type: STRING, key: "industryreborn:kind", value: skin}
            plain_nexo:
              itemname: "Обычный предмет Nexo"
              material: STONE
            """;

    @BeforeEach
    void setUp() throws Exception {
        nexoDir = new File(plugin.getDataFolder(), "nexo-items");
        plugin.getConfig().set("custom-items.nexo-folder", nexoDir.getPath());
        nexoFile("tools.yml", NEXO_ITEMS);
        plugin.customItems().setExternal(nexo);
        assertTrue(plugin.reloadCustomContent().isEmpty());
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.shop", 0);
        server.getWorld("world").loadChunk(0, 0);
        steve = player("Steve", 100_000);
        alex = player("Alex", 100_000);
        boss = admin("Boss");
    }

    private final java.util.List<String> models = new java.util.ArrayList<>();

    private void recordModels() {
        plugin.skins().setModelSetter((meta, key) -> {
            models.add(String.valueOf(key));
            meta.setItemModel(key);
        });
    }

    private ItemStack skinItem(String id) {
        return plugin.customItems().build(ItemRef.parse("ir:" + id), 1);
    }

    private ItemStack tool(Material m) {
        return new ItemStack(m);
    }

    // ------------------------------------------------------------ сканер Nexo

    @Test
    void markedNexoItemsAreRegisteredWithoutADefinitionFile() {
        assertEquals(4, plugin.customItems().all().size(), "обычный предмет Nexo без метки не берётся");
        CustomItem pick = plugin.customItems().get("forge_pick");
        assertEquals(ItemKind.TOOL, pick.kind());
        assertTrue(pick.nexoSource());
        assertEquals("forge_pick", pick.nexoId());
        assertEquals(Material.DIAMOND_PICKAXE, pick.material());
        CustomItem ruby = plugin.customItems().get("ruby");
        assertEquals(ItemKind.SKIN, ruby.kind());
        assertEquals(java.util.Set.of("pickaxe", "axe"), ruby.skinFor());
        assertEquals("nexo:ruby_skin_model", ruby.itemModel(), "модель из Components.item_model");
        assertEquals("ruby_skin", ruby.nexoId());
        assertEquals("nexo:gold_skin", plugin.customItems().get("gold").itemModel(), "по умолчанию nexo:<id предмета Nexo>");
        assertEquals(java.util.Set.of("any"), plugin.customItems().get("anyskin").skinFor(), "skin_for по умолчанию - any");
        assertNull(plugin.customItems().get("plain_nexo"));
        assertEquals(ItemKind.ITEM, ItemKind.parse("предмет"));
        assertEquals(ItemKind.SKIN, ItemKind.parse(" Skin "));
        assertNull(ItemKind.parse("weapon"));
        assertNull(ItemKind.parse(null));
    }

    @Test
    void builtNexoItemsCarryOurTagAndAreRecognizedByIt() {
        ItemStack pick = plugin.customItems().build(ItemRef.parse("ir:forge_pick"), 1);
        assertEquals(Material.DIAMOND_PICKAXE, pick.getType());
        assertEquals("forge_pick", plugin.customItems().tagOf(pick));
        assertEquals(new ItemRef(ItemRef.Kind.PLUGIN, "forge_pick"), plugin.customItems().identify(pick));
        nexo.loaded = false;
        assertNull(plugin.customItems().build(ItemRef.parse("ir:ruby"), 1), "предмет из Nexo без Nexo не подделывается из материала");
        assertNull(plugin.customItems().build(ItemRef.parse("ir:forge_pick"), 1));
    }

    @Test
    void badMarksAndConflictsAreReportedAndSkipped() throws Exception {
        nexoFile("bad.yml", """
                bad_kind:
                  PersistentData:
                    - {type: STRING, key: "industryreborn:item", value: weird}
                    - {type: STRING, key: "industryreborn:kind", value: weapon}
                bad_id:
                  PersistentData:
                    - {type: STRING, key: "industryreborn:item", value: "Bad Id"}
                bad_target:
                  PersistentData:
                    - {type: STRING, key: "industryreborn:item", value: tgt}
                    - {type: STRING, key: "industryreborn:kind", value: skin}
                    - {type: STRING, key: "industryreborn:skin_for", value: "spoon"}
                dup:
                  PersistentData:
                    - {type: STRING, key: "industryreborn:item", value: ruby}
                """);
        File items = new File(plugin.getDataFolder(), "items");
        items.mkdirs();
        Files.writeString(new File(items, "own.yml").toPath(), "forge_pick:\n  material: IRON_PICKAXE\n  kind: tool\n", StandardCharsets.UTF_8);
        List<String> errors = plugin.reloadCustomContent();
        assertEquals(5, errors.size(), errors.toString());
        assertEquals(Material.IRON_PICKAXE, plugin.customItems().get("forge_pick").material(), "items/*.yml читается первым, Nexo-запись с тем же id отклонена");
        assertFalse(plugin.customItems().get("forge_pick").nexoSource());
        assertEquals("dup", plugin.customItems().get("ruby").nexoId(), "первый по порядку файлов (bad.yml) остаётся");
        assertNull(plugin.customItems().get("weird"));
        assertNull(plugin.customItems().get("tgt"));
    }

    @Test
    void scanningCanBeSwitchedOffAndNestedFoldersAreRead() throws Exception {
        File nested = new File(nexoDir, "sub/deeper");
        nested.mkdirs();
        Files.writeString(new File(nested, "n.yaml").toPath(), "deep_item:\n  PersistentData:\n    - {type: STRING, key: \"industryreborn:item\", value: deep}\n", StandardCharsets.UTF_8);
        plugin.reloadCustomContent();
        assertNotNull(plugin.customItems().get("deep"), "вложенные папки и .yaml");
        plugin.getConfig().set("custom-items.nexo-scan", false);
        plugin.reloadCustomContent();
        assertTrue(plugin.customItems().all().isEmpty());
        plugin.getConfig().set("custom-items.nexo-scan", true);
        plugin.getConfig().set("custom-items.nexo-folder", new File(plugin.getDataFolder(), "missing").getPath());
        assertTrue(plugin.reloadCustomContent().isEmpty(), "нет папки - не ошибка");
        assertTrue(plugin.customItems().all().isEmpty());
    }

    // ------------------------------------------------------------ магазин предметов

    @Test
    void theAdminSetsAndChangesPricesAndPlayersBuyIntoTheTreasury() {
        assertNotNull(plugin.itemShop().setPrice("nope", 5));
        assertNotNull(plugin.itemShop().setPrice("ruby", -1));
        assertNull(plugin.itemShop().setPrice("ruby", 300));
        assertNull(plugin.itemShop().setPrice("forge_pick", 1200));
        assertEquals(List.of("forge_pick", "ruby"), plugin.itemShop().listed().stream().map(CustomItem::id).toList(), "инструменты раньше скинов");
        double before = plugin.treasury().balance();
        double money = economy.getBalance(alex);
        assertNull(plugin.itemShop().buy(alex, "ruby", 2));
        assertEquals(money - 600, economy.getBalance(alex), 1e-9);
        assertEquals(before + 600, plugin.treasury().balance(), 1e-9);
        int got = 0;
        for (ItemStack s : alex.getInventory().getContents()) {
            if (s != null && "ruby".equals(plugin.customItems().tagOf(s))) {
                got += s.getAmount();
            }
        }
        assertEquals(2, got);
        assertNull(plugin.itemShop().setPrice("ruby", 450), "цена меняется");
        assertEquals(450, plugin.itemShop().price("ruby"), 1e-9);
        assertNull(plugin.itemShop().setPrice("ruby", 0));
        assertEquals(List.of("forge_pick"), plugin.itemShop().listed().stream().map(CustomItem::id).toList(), "ноль - снят с продажи");
    }

    @Test
    void theShopRefusesWhatItCannotSell() {
        plugin.itemShop().setPrice("forge_pick", 1000);
        economy.set(alex, 10);
        assertTrue(plugin.itemShop().buy(alex, "forge_pick", 1).contains("Не хватает"));
        assertEquals(10, economy.getBalance(alex), 1e-9);
        assertNotNull(plugin.itemShop().buy(alex, "ruby", 1), "без цены не продаётся");
        assertNotNull(plugin.itemShop().buy(alex, "nope", 1));
        assertNotNull(plugin.itemShop().buy(alex, "forge_pick", 0));
        economy.set(alex, 10_000_000);
        assertTrue(plugin.itemShop().buy(alex, "forge_pick", 65).contains("от 1 до 64"), "предел количества");
        assertEquals(10_000_000, economy.getBalance(alex), 1e-9);
        economy.set(alex, 100_000);
        nexo.loaded = false;
        double before = economy.getBalance(alex);
        plugin.itemShop().setPrice("forge_pick", 1000);
        assertNotNull(plugin.itemShop().buy(alex, "forge_pick", 1), "Nexo не загрузился - деньги не списываются");
        assertEquals(before, economy.getBalance(alex), 1e-9);
        plugin.getConfig().set("custom-items.enabled", false);
        assertNotNull(plugin.itemShop().buy(alex, "forge_pick", 1));
    }

    @Test
    void pricesSurviveARestartAndTheMenuAndCommandsWork() {
        plugin.itemShop().setPrice("ruby", 300);
        plugin.itemShop().setPrice("forge_pick", 900);
        var fresh = new ru.industryreborn.items.ItemShop(plugin);
        assertEquals(300, fresh.price("ruby"), 1e-9);
        boss.performCommand("district item price ruby 350");
        assertEquals(350, plugin.itemShop().price("ruby"), 1e-9);
        steve.performCommand("district item price ruby 1");
        assertEquals(350, plugin.itemShop().price("ruby"), 1e-9, "цену задаёт только администратор");
        boss.performCommand("district item price ruby abc");
        boss.performCommand("district item list");
        new ru.industryreborn.gui.ItemShopMenu(plugin, alex, null, 0).open();
        var inv = alex.getOpenInventory().getTopInventory();
        assertEquals(Material.DIAMOND_PICKAXE, inv.getItem(9).getType(), "инструмент первым, значок - настоящий предмет");
        assertEquals(Material.PAPER, inv.getItem(10).getType());
        assertTrue(String.valueOf(inv.getItem(10).lore()).contains("pickaxe"), "у скина видно, на что он ставится");
        double money = economy.getBalance(alex);
        alex.simulateInventoryClick(alex.getOpenInventory(), ClickType.LEFT, 10);
        assertEquals(money - 350, economy.getBalance(alex), 1e-9);
        alex.performCommand("district itemshop buy ruby 3");
        assertEquals(money - 350 - 1050, economy.getBalance(alex), 1e-9);
        alex.performCommand("district itemshop list");
        alex.performCommand("district itemshop wat");
        alex.performCommand("district itemshop buy");
    }

    // ------------------------------------------------------------ скины

    @Test
    void aSkinChangesTheModelOfOneToolAndIsConsumed() {
        recordModels();
        alex.getInventory().setItemInMainHand(skinItem("ruby"));
        ItemStack pick = tool(Material.IRON_PICKAXE);
        pick.editMeta(m -> {
            m.displayName(Component.text("Моя кирка"));
            m.addEnchant(org.bukkit.enchantments.Enchantment.EFFICIENCY, 3, true);
            ((Damageable) m).setDamage(40);
        });
        alex.getInventory().setItem(3, pick);
        assertNull(plugin.skins().apply(alex, 3));
        ItemStack skinned = alex.getInventory().getItem(3);
        assertEquals("ruby", plugin.skins().appliedSkin(skinned));
        assertEquals(List.of("nexo:ruby_skin_model"), models, "инструменту поставлена модель скина");
        assertEquals(3, skinned.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.EFFICIENCY), "зачарования остались");
        assertEquals(40, ((Damageable) skinned.getItemMeta()).getDamage(), "износ остался");
        assertEquals("Моя кирка", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(skinned.getItemMeta().displayName()));
        assertTrue(alex.getInventory().getItemInMainHand().getType().isAir(), "скин расходован");
        assertTrue(plugin.journal().forPlayer(alex.getUniqueId()).stream().anyMatch(e -> e.reason() != null && e.reason().contains("ruby")));
    }

    @Test
    void onlyOneSkinIsSpentEvenFromAStack() {
        ItemStack skins = skinItem("ruby");
        skins.setAmount(3);
        alex.getInventory().setItemInMainHand(skins);
        alex.getInventory().setItem(2, tool(Material.GOLDEN_AXE));
        assertNull(plugin.skins().apply(alex, 2));
        assertEquals(2, alex.getInventory().getItemInMainHand().getAmount());
    }

    @Test
    void refusalsNeverSpendTheSkin() {
        alex.getInventory().setItemInMainHand(skinItem("ruby"));                        // для pickaxe и axe
        alex.getInventory().setItem(1, tool(Material.IRON_SWORD));
        alex.getInventory().setItem(2, new ItemStack(Material.DIRT));
        alex.getInventory().setItem(3, tool(Material.IRON_PICKAXE));
        assertTrue(plugin.skins().apply(alex, 1).contains("ставится на"), "меч не подходит");
        assertTrue(plugin.skins().apply(alex, 2).contains("ставится на"));
        assertNotNull(plugin.skins().apply(alex, 9), "пустой слот");
        assertNotNull(plugin.skins().apply(alex, 40 + 5), "слот вне инвентаря");
        assertNotNull(plugin.skins().apply(alex, alex.getInventory().getHeldItemSlot()), "не сам скин в руке");
        assertEquals(1, alex.getInventory().getItemInMainHand().getAmount());
        assertNull(plugin.skins().apply(alex, 3));
        // тот же скин второй раз на тот же предмет
        alex.getInventory().setItemInMainHand(skinItem("ruby"));
        assertTrue(plugin.skins().apply(alex, 3).contains("уже стоит"));
        assertFalse(alex.getInventory().getItemInMainHand().getType().isAir(), "скин сохранён");
        // скин на скин
        alex.getInventory().setItem(4, skinItem("gold"));
        assertTrue(plugin.skins().apply(alex, 4).contains("другому скину"));
        // не скин в руке
        alex.getInventory().setItemInMainHand(new ItemStack(Material.DIRT));
        assertEquals("Возьмите скин в основную руку.", plugin.skins().apply(alex, 3));
        // отключено
        alex.getInventory().setItemInMainHand(skinItem("ruby"));
        plugin.getConfig().set("custom-items.enabled", false);
        assertTrue(plugin.skins().apply(alex, 3).contains("отключены"));
    }

    @Test
    void anotherSkinReplacesThePreviousOneAndASkinCanBeRemoved() {
        recordModels();
        alex.getInventory().setItem(2, tool(Material.DIAMOND_PICKAXE));
        alex.getInventory().setItemInMainHand(skinItem("ruby"));
        assertNull(plugin.skins().apply(alex, 2));
        alex.getInventory().setItemInMainHand(skinItem("anyskin"));
        assertNull(plugin.skins().apply(alex, 2));
        assertEquals("anyskin", plugin.skins().appliedSkin(alex.getInventory().getItem(2)), "новый скин заменил прежний");
        assertEquals(List.of("nexo:ruby_skin_model", "nexo:any_skin"), models);
        // снять
        alex.getInventory().setItemInMainHand(alex.getInventory().getItem(2));
        assertNull(plugin.skins().remove(alex));
        assertNull(plugin.skins().appliedSkin(alex.getInventory().getItemInMainHand()));
        assertEquals("null", models.get(models.size() - 1), "модель сброшена к стандартной");
        assertNotNull(plugin.skins().remove(alex), "снимать нечего");
    }

    @Test
    void theTargetTypesFollowTheSkinsMarks() {
        CustomItem ruby = plugin.customItems().get("ruby");
        CustomItem gold = plugin.customItems().get("gold");
        CustomItem any = plugin.customItems().get("anyskin");
        assertTrue(plugin.skins().eligible(ruby, tool(Material.NETHERITE_AXE)));
        assertFalse(plugin.skins().eligible(ruby, tool(Material.IRON_SHOVEL)));
        assertTrue(plugin.skins().eligible(gold, tool(Material.WOODEN_SWORD)));
        assertFalse(plugin.skins().eligible(gold, tool(Material.WOODEN_PICKAXE)));
        assertTrue(plugin.skins().eligible(any, tool(Material.IRON_HOE)));
        assertTrue(plugin.skins().eligible(any, tool(Material.IRON_CHESTPLATE)), "any - любой предмет с прочностью");
        assertFalse(plugin.skins().eligible(any, new ItemStack(Material.DIRT)), "обычные блоки не подходят");
        assertFalse(plugin.skins().eligible(ruby, null));
        assertEquals("pickaxe", ru.industryreborn.items.SkinService.category(Material.GOLDEN_PICKAXE));
        assertEquals("axe", ru.industryreborn.items.SkinService.category(Material.IRON_AXE));
        assertEquals("shovel", ru.industryreborn.items.SkinService.category(Material.IRON_SHOVEL));
        assertEquals("hoe", ru.industryreborn.items.SkinService.category(Material.IRON_HOE));
        assertEquals("sword", ru.industryreborn.items.SkinService.category(Material.IRON_SWORD));
        assertNull(ru.industryreborn.items.SkinService.category(Material.SHEARS));
    }

    @Test
    void theSkinMenuListsOnlyFittingToolsAndAppliesOnClick() {
        alex.getInventory().setItemInMainHand(skinItem("ruby"));
        alex.getInventory().setItem(1, tool(Material.IRON_SWORD));
        alex.getInventory().setItem(2, tool(Material.IRON_PICKAXE));
        alex.getInventory().setItem(3, tool(Material.STONE_AXE));
        alex.performCommand("district skin");
        var inv = alex.getOpenInventory().getTopInventory();
        assertTrue(inv.getHolder() instanceof ru.industryreborn.gui.SkinApplyMenu);
        assertEquals(Material.IRON_PICKAXE, inv.getItem(9).getType());
        assertEquals(Material.STONE_AXE, inv.getItem(10).getType());
        assertTrue(inv.getItem(11).getType().name().endsWith("PANE"), "меч не показан");
        alex.simulateInventoryClick(alex.getOpenInventory(), ClickType.LEFT, 10);
        assertEquals("ruby", plugin.skins().appliedSkin(alex.getInventory().getItem(3)));
        assertNull(plugin.skins().appliedSkin(alex.getInventory().getItem(2)));
        // без скина в руке команда подсказывает
        alex.performCommand("district skin");
        alex.performCommand("district skin info");
        alex.performCommand("district skin wat");
        new ru.industryreborn.gui.SkinApplyMenu(plugin, alex).open();
        assertEquals(Material.BARRIER, alex.getOpenInventory().getTopInventory().getItem(22).getType());
    }

    @Test
    void theSkinCommandsCoverInfoAndRemove() {
        alex.getInventory().setItemInMainHand(skinItem("gold"));
        alex.performCommand("district skin info");
        alex.getInventory().setItemInMainHand(skinItem("ruby"));
        alex.getInventory().setItem(5, tool(Material.IRON_AXE));
        assertNull(plugin.skins().apply(alex, 5));
        alex.getInventory().setItemInMainHand(alex.getInventory().getItem(5));
        alex.performCommand("district skin info");
        alex.performCommand("district skin remove");
        assertNull(plugin.skins().appliedSkin(alex.getInventory().getItemInMainHand()));
    }

    // ------------------------------------------------------------ нетронутые экземпляры

    @Test
    void usedOrSkinnedToolsAreNeverAcceptedAsGoods() {
        Plot shop = addPlot("s", Category.SHOP, 20, 1000, -1);
        assertTrue(plugin.service().buy(steve, shop));
        ItemStack fresh = plugin.customItems().build(ItemRef.parse("ir:forge_pick"), 1);
        ItemStack damaged = plugin.customItems().build(ItemRef.parse("ir:forge_pick"), 1);
        damaged.editMeta(m -> ((Damageable) m).setDamage(100));
        ItemStack enchanted = plugin.customItems().build(ItemRef.parse("ir:forge_pick"), 1);
        enchanted.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 3);
        ItemStack skinned = plugin.customItems().build(ItemRef.parse("ir:forge_pick"), 1);
        skinned.editMeta(m -> m.getPersistentDataContainer().set(plugin.customItems().skinKey(), org.bukkit.persistence.PersistentDataType.STRING, "ruby"));
        for (ItemStack s : List.of(fresh, damaged, enchanted, skinned)) {
            assertEquals(s == fresh, plugin.customItems().pristine(s), String.valueOf(s));
        }
        steve.getInventory().addItem(damaged, enchanted, skinned);
        assertNotNull(plugin.goods().deposit(steve, shop, null, 0), "битые, зачарованные и со скином не принимаются");
        assertTrue(shop.customGoods.isEmpty());
        steve.getInventory().addItem(fresh);
        assertNull(plugin.goods().deposit(steve, shop, null, 0));
        assertEquals(1, shop.customGoods.get("forge_pick".equals("x") ? "x" : "ir:forge_pick"));
        assertTrue(plugin.customItems().pristine(null));
        assertTrue(plugin.customItems().pristine(new ItemStack(Material.DIRT)));
    }

    @Test
    void theWorkshopRawChestIgnoresUsedToolsToo() throws Exception {
        File recipes = new File(plugin.getDataFolder(), "recipes");
        recipes.mkdirs();
        Files.writeString(new File(recipes, "r.yml").toPath(), """
                recycle:
                  station: furnace
                  seconds: 5
                  free: true
                  inputs: [{item: ir:forge_pick, amount: 1}]
                  output: {item: IRON_INGOT, amount: 3}
                """, StandardCharsets.UTF_8);
        plugin.reloadCustomContent();
        plugin.getConfig().set("limits.categories.workshop", 0);
        Plot workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        assertTrue(plugin.service().rent(steve, workshop, 10));
        org.bukkit.block.Block b = server.getWorld("world").getBlockAt(1, 1, 1);
        b.setType(Material.FURNACE);
        plugin.stations().addMachine(workshop, 1, 1, 1, ru.industryreborn.model.Station.FURNACE);
        plugin.stations().inc(workshop, ru.industryreborn.model.Station.FURNACE);
        plugin.power().recalc(workshop);
        assertTrue(plugin.power().deposit(steve, workshop, 5_000));
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:recycle"));
        steve.getInventory().setItemInMainHand(null);
        ItemStack damaged = plugin.customItems().build(ItemRef.parse("ir:forge_pick"), 1);
        damaged.editMeta(m -> ((Damageable) m).setDamage(50));
        steve.getInventory().addItem(damaged, plugin.customItems().build(ItemRef.parse("ir:forge_pick"), 1));
        assertNull(plugin.factory().load(steve, workshop, 0));
        assertEquals(1, plugin.factory().stockOf(workshop, "ir:forge_pick"), "принят только нетронутый экземпляр");
        int left = 0;
        for (ItemStack s : steve.getInventory().getContents()) {
            if (s != null && "forge_pick".equals(plugin.customItems().tagOf(s))) {
                left++;
            }
        }
        assertEquals(1, left, "битый остался у игрока");
    }

    // ------------------------------------------------------------ статистика

    @Test
    void statsCountToolsSkinsAndItemsForSale() {
        plugin.itemShop().setPrice("ruby", 10);
        var stats = plugin.stats().collect();
        assertEquals(1, stats.metric("production", "items-tools").value(), 0);
        assertEquals(3, stats.metric("production", "items-skins").value(), 0);
        assertEquals(1, stats.metric("production", "items-for-sale").value(), 0);
    }

    @Test
    void itemFilesCanDeclareKindsAndSkinTargetsToo() throws Exception {
        File items = new File(plugin.getDataFolder(), "items");
        items.mkdirs();
        Files.writeString(new File(items, "own.yml").toPath(), """
                own_tool:
                  material: IRON_AXE
                  kind: tool
                own_skin:
                  material: GOLDEN_SWORD
                  kind: skin
                  skin_for: [sword, AXE]
                own_any:
                  material: GOLDEN_HOE
                  kind: Skin
                bad_kind:
                  material: STONE
                  kind: weapon
                bad_target:
                  material: STONE
                  kind: skin
                  skin_for: [spoon]
                """, StandardCharsets.UTF_8);
        List<String> errors = plugin.reloadCustomContent();
        assertEquals(2, errors.size(), errors.toString());
        assertEquals(ItemKind.TOOL, plugin.customItems().get("own_tool").kind());
        assertFalse(plugin.customItems().get("own_tool").nexoSource());
        assertEquals(java.util.Set.of("sword", "axe"), plugin.customItems().get("own_skin").skinFor());
        assertEquals(java.util.Set.of("any"), plugin.customItems().get("own_any").skinFor());
        assertEquals(ItemKind.SKIN, plugin.customItems().get("own_any").kind());
        assertNull(plugin.customItems().get("bad_kind"));
        assertNull(plugin.customItems().get("bad_target"));
        // скин нельзя использовать как цель другого скина, даже если у него есть прочность
        ItemStack skinWithDurability = plugin.customItems().build(ItemRef.parse("ir:own_any"), 1);
        assertEquals(Material.GOLDEN_HOE, skinWithDurability.getType());
        assertFalse(plugin.skins().eligible(plugin.customItems().get("own_any"), skinWithDurability), "скин - не цель скина");
        assertTrue(plugin.skins().eligible(plugin.customItems().get("own_any"), tool(Material.GOLDEN_HOE)));
    }

    @Test
    void theSkinCommandWithoutASkinOpensNothing() {
        alex.closeInventory();
        alex.getInventory().setItemInMainHand(new ItemStack(Material.DIRT));
        alex.performCommand("district skin");
        var inv = alex.getOpenInventory().getTopInventory();
        assertTrue(inv == null || !(inv.getHolder() instanceof ru.industryreborn.gui.SkinApplyMenu), "без скина в руке меню не открывается");
    }
}
