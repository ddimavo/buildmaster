package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
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
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.support.PluginTestBase;

/** Предметы плагина и Nexo как товары: склад товаров, цены, витрина, покупка, связь с мастерской, закрытие предприятия. */
class CustomGoodsTest extends PluginTestBase {

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
            ItemStack s = new ItemStack(Material.IRON_NUGGET);
            s.editMeta(m -> m.displayName(Component.text("NEXO:" + id)));
            return s;
        }

        @Override
        public boolean exists(String id) {
            return loaded && id.equals("rune");
        }
    }

    private final AtomicLong wall = new AtomicLong(1_000_000);
    private final FakeNexo nexo = new FakeNexo();
    private PlayerMock steve;
    private PlayerMock alex;
    private Plot shop;
    private Plot workshop;

    private void write(String dir, String name, String text) throws Exception {
        File d = new File(plugin.getDataFolder(), dir);
        d.mkdirs();
        Files.writeString(new File(d, name).toPath(), text, StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() throws Exception {
        write("items", "metal.yml", "steel:\n  material: IRON_INGOT\n  name: \"Стальной слиток\"\n");
        write("recipes", "metal.yml", """
                steel_ingot:
                  station: furnace
                  seconds: 5
                  free: true
                  inputs: [{item: COAL, amount: 1}]
                  output: {item: ir:steel, amount: 1}
                """);
        plugin.customItems().setExternal(nexo);
        assertTrue(plugin.reloadCustomContent().isEmpty());
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("goods.sales-tax-percent", 0);
        plugin.getConfig().set("electricity.max-clock-step-seconds", 10_000_000);
        plugin.power().setTimeSource(wall::get);
        server.getWorld("world").loadChunk(0, 0);
        server.getWorld("world").loadChunk(1, 0);
        steve = player("Steve", 1_000_000);
        alex = player("Alex", 100_000);
        shop = addPlot("s", Category.SHOP, 20, 1000, -1);
        workshop = addPlot("w", Category.WORKSHOP, 0, 1000, -1);
        assertTrue(plugin.service().buy(steve, shop));
        assertTrue(plugin.service().buy(steve, workshop));
        steve.teleport(new org.bukkit.Location(server.getWorld("world"), 400, 1, 400));
    }

    private ItemStack steel(int n) {
        return plugin.customItems().build(ItemRef.parse("ir:steel"), n);
    }

    private int stored(PlayerMock p, String key) {
        int n = 0;
        for (ItemStack s : p.getInventory().getContents()) {
            var ref = s == null ? null : plugin.customItems().identify(s);
            if (ref != null && ref.key().equals(key)) {
                n += s.getAmount();
            }
        }
        return n;
    }

    // ------------------------------------------------------------ склад товаров

    @Test
    void depositTakesTaggedItemsAndSkipsFakes() {
        steve.getInventory().addItem(steel(7), new ItemStack(Material.DIRT, 5));
        ItemStack fake = new ItemStack(Material.IRON_INGOT, 4);
        fake.editMeta(m -> m.displayName(Component.text("Стальной слиток")));
        steve.getInventory().addItem(fake);
        assertNull(plugin.goods().deposit(steve, shop, null, 0));
        assertEquals(7, shop.customGoods.get("ir:steel"));
        assertEquals(5, shop.goods.get(Material.DIRT));
        assertEquals(12, plugin.goods().total(shop), "склад считает и предметы плагина");
        assertEquals(0, stored(steve, "ir:steel"));
        assertEquals(4, steve.getInventory().all(Material.IRON_INGOT).values().stream().mapToInt(ItemStack::getAmount).sum(), "подделка осталась");
        // конкретный материал: предметы плагина не берутся
        steve.getInventory().addItem(steel(3));
        assertNotNull(plugin.goods().deposit(steve, shop, Material.DIRT, 0));
        assertEquals(3, stored(steve, "ir:steel"));
    }

    @Test
    void pricesStockAndListingWorkByKey() {
        shop.customGoods.put("ir:steel", 9);
        shop.goods.put(Material.DIRT, 4);
        shop.prices.put(Material.DIRT, 2.0);
        assertEquals(0, plugin.goods().priceKey(shop, "ir:steel"), "цена по умолчанию - не продаётся");
        assertNull(plugin.goods().setPriceKey(steve, shop, "ir:steel", 30.0));
        assertEquals(30, plugin.goods().priceKey(shop, "ir:steel"), 1e-9);
        assertEquals(List.of("DIRT", "ir:steel"), plugin.goods().listedKeys(shop), "по названию: «Стальной слиток» после DIRT");
        assertEquals(9, plugin.goods().stockKey(shop, "ir:steel"));
        assertEquals("Стальной слиток", plugin.goods().nameOf("ir:steel"));
        assertEquals("DIRT", plugin.goods().nameOf("DIRT"));
        assertNull(plugin.goods().setPriceKey(steve, shop, "ir:steel", null));
        assertEquals(List.of("DIRT"), plugin.goods().listedKeys(shop));
        assertNotNull(plugin.goods().setPriceKey(steve, shop, "ir:nope", 5.0), "неизвестного предмета нет");
        assertNotNull(plugin.goods().setPriceKey(steve, shop, "ir:steel", 5_000_000.0));
        assertNotNull(plugin.goods().setPriceKey(alex, shop, "ir:steel", 5.0), "чужому нельзя");
    }

    @Test
    void withdrawDeliversTheRealItem() {
        shop.customGoods.put("ir:steel", 10);
        assertNull(plugin.goods().withdrawKey(steve, shop, "ir:steel", 4));
        assertEquals(6, shop.customGoods.get("ir:steel"));
        assertEquals(4, stored(steve, "ir:steel"));
        assertNull(plugin.goods().withdrawKey(steve, shop, "ir:steel", 0));
        assertFalse(shop.customGoods.containsKey("ir:steel"));
        assertEquals(10, stored(steve, "ir:steel"));
        assertNotNull(plugin.goods().withdrawKey(steve, shop, "ir:steel", 1), "склад пуст");
    }

    @Test
    void goodsSurviveARestart() {
        shop.customGoods.put("ir:steel", 9);
        shop.customPrices.put("ir:steel", 12.5);
        shop.customGoods.put("nexo:rune", 2);
        plugin.plots().save();
        var fresh = new ru.industryreborn.service.PlotManager(plugin);
        fresh.load();
        assertEquals(9, fresh.get("s").customGoods.get("ir:steel"));
        assertEquals(12.5, fresh.get("s").customPrices.get("ir:steel"), 1e-9);
        assertEquals(2, fresh.get("s").customGoods.get("nexo:rune"));
    }

    // ------------------------------------------------------------ покупка

    @Test
    void aBuyerGetsTheRealItemAndTheShopGetsTheMoney() {
        shop.customGoods.put("ir:steel", 10);
        shop.customPrices.put("ir:steel", 30.0);
        double before = economy.getBalance(alex);
        assertNull(plugin.goods().buyKey(alex, shop, "ir:steel", 3));
        assertEquals(before - 90, economy.getBalance(alex), 1e-9);
        assertEquals(7, shop.customGoods.get("ir:steel"));
        assertEquals(3, stored(alex, "ir:steel"));
        assertEquals(90, shop.revenue, 1e-9);
        assertNotNull(plugin.goods().buyKey(alex, shop, "ir:steel", 50), "столько нет");
        assertNotNull(plugin.goods().buyKey(steve, shop, "ir:steel", 1), "у самого себя нельзя");
        shop.customPrices.remove("ir:steel");
        assertNotNull(plugin.goods().buyKey(alex, shop, "ir:steel", 1), "без цены не продаётся");
    }

    @Test
    void anUnavailableNexoItemIsNeitherSoldNorChargedFor() {
        shop.customGoods.put("nexo:rune", 5);
        shop.customPrices.put("nexo:rune", 100.0);
        assertNull(plugin.goods().buyKey(alex, shop, "nexo:rune", 1));
        assertEquals(1, stored(alex, "nexo:rune"));
        nexo.loaded = false;
        double before = economy.getBalance(alex);
        assertTrue(plugin.goods().buyKey(alex, shop, "nexo:rune", 1).contains("недоступен"));
        assertEquals(before, economy.getBalance(alex), 1e-9, "деньги не списаны");
        assertEquals(4, shop.customGoods.get("nexo:rune"));
        assertNotNull(plugin.goods().withdrawKey(steve, shop, "nexo:rune", 1));
    }

    @Test
    void theStorefrontMenuShowsAndSellsPluginItems() {
        shop.customGoods.put("ir:steel", 10);
        shop.customPrices.put("ir:steel", 30.0);
        shop.goods.put(Material.DIRT, 5);
        shop.prices.put(Material.DIRT, 1.0);
        new ru.industryreborn.gui.StorefrontMenu(plugin, alex, shop, null, 0).open();
        var inv = alex.getOpenInventory().getTopInventory();
        assertEquals(Material.DIRT, inv.getItem(9).getType());
        assertEquals(Material.IRON_INGOT, inv.getItem(10).getType(), "основа предмета плагина");
        assertTrue(String.valueOf(inv.getItem(10).getItemMeta().displayName()).contains("Стальной слиток"));
        alex.simulateInventoryClick(alex.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, 10);
        assertEquals(1, stored(alex, "ir:steel"));
        assertEquals(9, shop.customGoods.get("ir:steel"));
    }

    @Test
    void theGoodsMenuAndCommandsManagePluginItems() {
        shop.customGoods.put("ir:steel", 10);
        steve.teleport(new org.bukkit.Location(server.getWorld("world"), 22, 1, 3));
        steve.performCommand("district goods price ir:steel 25 s");
        assertEquals(25, shop.customPrices.get("ir:steel"), 1e-9);
        steve.performCommand("district goods list s");
        steve.performCommand("district goods take ir:steel 2 s");
        assertEquals(2, stored(steve, "ir:steel"));
        steve.performCommand("district goods take ir:bad 1 s");
        new ru.industryreborn.gui.GoodsMenu(plugin, steve, shop, null, 0).open();
        var inv = steve.getOpenInventory().getTopInventory();
        assertEquals(Material.IRON_INGOT, inv.getItem(9).getType());
        steve.simulateInventoryClick(steve.getOpenInventory(), org.bukkit.event.inventory.ClickType.RIGHT, 9);   // забрать 64
        assertFalse(shop.customGoods.containsKey("ir:steel"));
        assertEquals(10, stored(steve, "ir:steel"));
    }

    @Test
    void closingTheShopReturnsPluginItemsOrArrestsThem() {
        shop.customGoods.put("ir:steel", 6);
        shop.customPrices.put("ir:steel", 5.0);
        plugin.goods().closePlot(shop, steve.getUniqueId(), false, "тест");
        assertTrue(shop.customGoods.isEmpty() && shop.customPrices.isEmpty());
        assertEquals(6, stored(steve, "ir:steel"));
        shop.customGoods.put("ir:steel", 4);
        plugin.goods().closePlot(shop, steve.getUniqueId(), true, "арест");
        int arrested = 0;
        for (StorageEntry e : plugin.storage().entries(steve.getUniqueId())) {
            if (e.state == StorageEntry.State.ARRESTED) {
                for (byte[] b : e.stacks) {
                    ItemStack s = ItemStack.deserializeBytes(b);
                    if ("steel".equals(plugin.customItems().tagOf(s))) {
                        arrested += s.getAmount();
                    }
                }
            }
        }
        assertEquals(4, arrested);
    }

    // ------------------------------------------------------------ производство и витрина-голограмма

    private void machineAndPower() {
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

    @Test
    void aLinkedWorkshopFeedsPluginItemsIntoTheShop() {
        machineAndPower();
        assertNull(plugin.links().request(steve, workshop, shop));
        workshop.stock.put(Material.COAL, 4);
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:steel_ingot"));
        pass(30);
        assertEquals(4, shop.customGoods.get("ir:steel"), "продукция идёт в магазин по ключу");
        assertEquals(0, workshop.machineStates.get("1,1,1").output);
    }

    @Test
    void aWorkshopStorefrontKeepsItsOwnPluginItems() {
        machineAndPower();
        workshop.storefront = true;
        workshop.stock.put(Material.COAL, 2);
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:steel_ingot"));
        pass(20);
        assertEquals(2, workshop.customGoods.get("ir:steel"));
    }

    @Test
    void aProductChestStillGetsTheRealItemsWithoutALink() {
        machineAndPower();
        Block out = server.getWorld("world").getBlockAt(6, 1, 6);
        out.setType(Material.CHEST);
        assertNull(plugin.factory().markChest(steve, workshop, out, false));
        workshop.stock.put(Material.COAL, 2);
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", "custom:steel_ingot"));
        pass(20);
        int n = 0;
        for (ItemStack s : ((Chest) out.getState()).getBlockInventory().getContents()) {
            if (s != null && "steel".equals(plugin.customItems().tagOf(s))) {
                n += s.getAmount();
            }
        }
        assertEquals(2, n);
    }

    @Test
    void theHologramShopListsAndSellsPluginItems() {
        var texts = new java.util.ArrayList<String>();
        var icons = new java.util.ArrayList<ItemStack>();
        plugin.holoFactory().setSpawner((at, init) -> {
            var f = new Object() { String text = ""; };
            org.bukkit.entity.TextDisplay d = (org.bukkit.entity.TextDisplay) java.lang.reflect.Proxy.newProxyInstance(
                    org.bukkit.entity.TextDisplay.class.getClassLoader(), new Class<?>[] {org.bukkit.entity.TextDisplay.class}, (proxy, m, a) -> {
                        if (m.getName().equals("text") && a != null && a.length == 1) {
                            texts.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize((Component) a[0]));
                        }
                        return switch (m.getName()) {
                            case "isValid" -> true;
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == a[0];
                            default -> m.getReturnType() == boolean.class ? (Object) false : null;
                        };
                    });
            init.accept(d);
            return d;
        });
        plugin.holoFactory().setItemSpawner((at, init) -> {
            org.bukkit.entity.ItemDisplay d = (org.bukkit.entity.ItemDisplay) java.lang.reflect.Proxy.newProxyInstance(
                    org.bukkit.entity.ItemDisplay.class.getClassLoader(), new Class<?>[] {org.bukkit.entity.ItemDisplay.class}, (proxy, m, a) -> {
                        if (m.getName().equals("setItemStack")) {
                            icons.add((ItemStack) a[0]);
                        }
                        return switch (m.getName()) {
                            case "isValid" -> true;
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == a[0];
                            default -> m.getReturnType() == boolean.class ? (Object) false : null;
                        };
                    });
            init.accept(d);
            return d;
        });
        plugin.clickZones().setSpawner((at, size) -> (org.bukkit.entity.Entity) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.entity.Entity.class.getClassLoader(), new Class<?>[] {org.bukkit.entity.Entity.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "isValid" -> true;
                    case "getUniqueId" -> java.util.UUID.randomUUID();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == a[0];
                    default -> m.getReturnType() == boolean.class ? (Object) false : null;
                }));
        plugin.getConfig().set("holo-shop.two-sided", false);
        shop.customGoods.put("ir:steel", 10);
        shop.customPrices.put("ir:steel", 30.0);
        steve.teleport(new org.bukkit.Location(server.getWorld("world"), 23, 1, 3, 0f, 0f));
        assertNull(plugin.holoShops().create(steve, shop));
        plugin.holoShops().tick();
        assertTrue(texts.stream().anyMatch(t -> t.contains("Стальной слиток")), texts.toString());
        assertTrue(icons.stream().anyMatch(s -> "steel".equals(plugin.customItems().tagOf(s))), "иконка - настоящий предмет плагина");
        double before = economy.getBalance(alex);
        assertTrue(plugin.holoShops().press(plugin.holoShops().all().get(0).id, 0, alex, false));
        assertEquals(before - 30, economy.getBalance(alex), 1e-9);
        assertEquals(1, stored(alex, "ir:steel"));
    }
}
