package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.JobRole;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Request;
import ru.buildmaster.model.Station;
import ru.buildmaster.model.StorageEntry;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.PlotService;
import ru.buildmaster.service.ServiceBoardText;
import ru.buildmaster.support.PluginTestBase;
import ru.buildmaster.util.ChunkAccess;
import ru.buildmaster.util.ChunkJob;

/** Товары и витрина, связи между бизнесами, отдельные голограммы услуг и панели управляющего. */
@SuppressWarnings("removal")
class BusinessTest extends PluginTestBase {

    private PlayerMock steve;     // управляющий мастерской и магазина
    private PlayerMock alex;      // владелец чужого магазина, покупатель
    private PlayerMock mia;
    private PlayerMock boss;
    private Plot workshop;
    private Plot shop;
    private Plot otherShop;
    private Plot warehouse;

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
        ChunkJob.setLoader((w, cx, cz, cb) -> cb.accept(w.getChunkAt(cx, cz), null));
        ChunkAccess.setSource(new ChunkAccess.Source() {
            @Override
            public List<org.bukkit.block.BlockState> tileEntities(org.bukkit.Chunk chunk) {
                return List.of();
            }

            @Override
            public java.util.Collection<org.bukkit.entity.Entity> entities(org.bukkit.Chunk chunk) {
                return List.of();
            }
        });
        plugin.getConfig().set("limits.total", 0);
        for (String c : new String[] {"workshop", "shop", "warehouse"}) {
            plugin.getConfig().set("limits.categories." + c, 0);
        }
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                server.getWorld("world").loadChunk(cx, cz);
            }
        }
        steve = player("Steve", 100_000);
        alex = player("Alex", 100_000);
        mia = player("Mia", 100_000);
        boss = admin("Boss");
        workshop = addPlot("w", Category.WORKSHOP, 0, 1000, -1);
        shop = addPlot("s", Category.SHOP, 20, 1000, -1);
        otherShop = addPlot("o", Category.SHOP, 40, 1000, -1);
        warehouse = addPlot("wh", Category.WAREHOUSE, 60, 1000, -1);
        assertTrue(plugin.service().buy(steve, workshop));
        assertTrue(plugin.service().buy(steve, shop));
        assertTrue(plugin.service().buy(alex, otherShop));
        assertTrue(plugin.service().buy(steve, warehouse));
        for (PlayerMock p : new PlayerMock[] {steve, alex, mia, boss}) {
            p.teleport(new Location(server.getWorld("world"), 5000, 1, 5000));
        }
    }

    @AfterEach
    void tearDown() {
        ChunkJob.setLoader(null);
        ChunkAccess.setSource(null);
        plugin.servicesHolograms().removeAll();
        plugin.managerPanels().removeAll();
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

    private void give(PlayerMock p, Material m, int n) {
        p.getInventory().addItem(new ItemStack(m, n));
    }

    private void stock(Plot plot, Material m, int n, double price) {
        plot.goods.put(m, n);
        if (price >= 0) {
            plot.prices.put(m, price);
        }
    }

    // ------------------------------------------------------------ товары

    @Test
    void capacityAndSellingDependOnTheBuildingType() {
        assertEquals(2048, plugin.goods().capacity(shop));
        assertEquals(1024, plugin.goods().capacity(workshop));
        assertEquals(8192, plugin.goods().capacity(warehouse));
        assertEquals(0, plugin.goods().capacity(addPlot("h", Category.HOUSE, 80, 100, 5)));
        assertTrue(plugin.goods().sells(shop) && plugin.goods().sells(workshop));
        assertFalse(plugin.goods().sells(warehouse), "склад только хранит");
        assertFalse(plugin.goods().sells(addPlot("f", Category.SHOP, 100, 100, 5)), "свободный участок");
        plugin.getConfig().set("goods.capacity.shop", 10);
        assertEquals(10, plugin.goods().capacity(shop));
    }

    @Test
    void depositTakesOnlyPlainItemsWithinCapacity() {
        steve.getInventory().clear();
        give(steve, Material.IRON_INGOT, 30);
        give(steve, Material.DIRT, 10);
        ItemStack named = new ItemStack(Material.GOLD_INGOT, 5);
        named.editMeta(m -> m.displayName(net.kyori.adventure.text.Component.text("Особый")));
        steve.getInventory().addItem(named);
        assertNull(plugin.goods().deposit(steve, shop, null, 0));
        assertEquals(30, plugin.goods().stock(shop, Material.IRON_INGOT));
        assertEquals(10, plugin.goods().stock(shop, Material.DIRT));
        assertEquals(0, plugin.goods().stock(shop, Material.GOLD_INGOT), "предметы с названием не принимаются");
        assertEquals(5, count(steve, Material.GOLD_INGOT));
    }

    @Test
    void depositCanBeLimitedToOneItemOrCapacity() {
        plugin.getConfig().set("goods.capacity.shop", 12);
        steve.getInventory().clear();
        give(steve, Material.IRON_INGOT, 30);
        give(steve, Material.DIRT, 10);
        assertNull(plugin.goods().deposit(steve, shop, Material.DIRT, 0));
        assertEquals(10, plugin.goods().stock(shop, Material.DIRT));
        assertNull(plugin.goods().deposit(steve, shop, Material.IRON_INGOT, 0));
        assertEquals(2, plugin.goods().stock(shop, Material.IRON_INGOT), "по вместимости");
        assertNotNull(plugin.goods().deposit(steve, shop, null, 0), "склад полон");
        assertEquals(28, count(steve, Material.IRON_INGOT));
    }

    @Test
    void onlyTheManagerManagesGoods() {
        give(alex, Material.IRON_INGOT, 5);
        assertNotNull(plugin.goods().deposit(alex, shop, null, 0));
        assertNotNull(plugin.goods().setPrice(alex, shop, Material.IRON_INGOT, 5.0));
        stock(shop, Material.IRON_INGOT, 5, 3);
        assertNotNull(plugin.goods().withdraw(alex, shop, Material.IRON_INGOT, 1));
        assertNull(plugin.goods().setPrice(boss, shop, Material.IRON_INGOT, 4.0), "администратор может");
        assertNotNull(plugin.goods().deposit(steve, addPlot("h", Category.HOUSE, 80, 100, 5), null, 0), "у дома нет товаров");
    }

    @Test
    void withdrawGivesItemsBack() {
        stock(shop, Material.IRON_INGOT, 100, 3);
        steve.getInventory().clear();
        assertNull(plugin.goods().withdraw(steve, shop, Material.IRON_INGOT, 40));
        assertEquals(60, plugin.goods().stock(shop, Material.IRON_INGOT));
        assertEquals(40, count(steve, Material.IRON_INGOT));
        assertNull(plugin.goods().withdraw(steve, shop, Material.IRON_INGOT, 0));
        assertEquals(0, plugin.goods().stock(shop, Material.IRON_INGOT));
        assertFalse(shop.goods.containsKey(Material.IRON_INGOT), "пустая позиция удалена");
        assertNotNull(plugin.goods().withdraw(steve, shop, Material.IRON_INGOT, 1));
    }

    @Test
    void pricesAreSetClampedAndRemoved() {
        assertNull(plugin.goods().setPrice(steve, shop, Material.IRON_INGOT, 2.456));
        assertEquals(2.46, plugin.goods().price(shop, Material.IRON_INGOT), 1e-9);
        assertNotNull(plugin.goods().setPrice(steve, shop, Material.IRON_INGOT, Double.NaN));
        assertNotNull(plugin.goods().setPrice(steve, shop, Material.IRON_INGOT, 5_000_000.0));
        assertNull(plugin.goods().setPrice(steve, shop, Material.IRON_INGOT, null));
        assertEquals(0, plugin.goods().price(shop, Material.IRON_INGOT), 1e-9);
        plugin.getConfig().set("goods.default-price", 7);
        assertEquals(7, plugin.goods().price(shop, Material.IRON_INGOT), 1e-9, "цена по умолчанию");
    }

    @Test
    void onlyPricedItemsAreListed() {
        stock(shop, Material.IRON_INGOT, 5, 3);
        stock(shop, Material.DIRT, 5, -1);
        stock(shop, Material.GOLD_INGOT, 0, 9);
        assertEquals(List.of(Material.GOLD_INGOT, Material.IRON_INGOT), plugin.goods().listed(shop));
    }

    // ------------------------------------------------------------ покупка

    @Test
    void buyingMovesMoneyAndGoods() {
        stock(shop, Material.IRON_INGOT, 20, 5);
        double account = shop.account.rawBalance();
        double alexMoney = economy.getBalance(alex);
        alex.getInventory().clear();
        assertNull(plugin.goods().buy(alex, shop, Material.IRON_INGOT, 4));
        assertEquals(alexMoney - 20, economy.getBalance(alex), 1e-9);
        assertEquals(account + 20, shop.account.rawBalance(), 1e-6);
        assertEquals(20, shop.revenue, 1e-9);
        assertEquals(16, plugin.goods().stock(shop, Material.IRON_INGOT));
        assertEquals(4, count(alex, Material.IRON_INGOT));
        assertTrue(drain(steve).contains("Alex купил"), "управляющий узнал о продаже");
    }

    @Test
    void theSalesTaxGoesToTheTreasury() {
        plugin.getConfig().set("goods.sales-tax-percent", 10);
        stock(shop, Material.IRON_INGOT, 20, 10);
        double treasury = plugin.treasury().balance();
        double account = shop.account.rawBalance();
        assertNull(plugin.goods().buy(alex, shop, Material.IRON_INGOT, 5));
        assertEquals(treasury + 5, plugin.treasury().balance(), 1e-9);
        assertEquals(account + 45, shop.account.rawBalance(), 1e-6);
        assertEquals(45, shop.revenue, 1e-9);
    }

    @Test
    void buyingIsValidated() {
        stock(shop, Material.IRON_INGOT, 5, 10);
        stock(shop, Material.DIRT, 5, -1);
        stock(warehouse, Material.IRON_INGOT, 5, 10);
        assertNotNull(plugin.goods().buy(alex, shop, Material.DIRT, 1), "цена не задана");
        assertNotNull(plugin.goods().buy(alex, shop, Material.GOLD_INGOT, 1), "нет в наличии");
        assertNotNull(plugin.goods().buy(alex, shop, Material.IRON_INGOT, 6), "больше, чем есть");
        assertNotNull(plugin.goods().buy(alex, shop, Material.IRON_INGOT, 0));
        assertNotNull(plugin.goods().buy(alex, warehouse, Material.IRON_INGOT, 1), "склад не продаёт");
        assertNotNull(plugin.goods().buy(steve, shop, Material.IRON_INGOT, 1), "у самого себя");
        economy.set(mia, 5);
        String broke = plugin.goods().buy(mia, shop, Material.IRON_INGOT, 1);
        assertNotNull(broke);
        assertTrue(broke.contains("Недостаточно"), broke);
        assertEquals(5, plugin.goods().stock(shop, Material.IRON_INGOT), "ничего не продано");
        assertEquals(5, economy.getBalance(mia), 1e-9);
    }

    @Test
    void aShopSaleFeedsTheEmployeesPercent() {
        assertNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 1, 0, 10, null));
        plugin.jobs().apply(mia, plugin.jobs().vacanciesOf("s").get(0));
        plugin.jobs().accept(steve, plugin.jobs().applicationsBy(mia.getUniqueId()).get(0));
        stock(shop, Material.IRON_INGOT, 20, 10);
        assertNull(plugin.goods().buy(alex, shop, Material.IRON_INGOT, 5));
        assertEquals(5, plugin.jobs().employee("s", mia.getUniqueId()).accrued, 1e-9, "10% от 50");
    }

    @Test
    void aWorkshopSellsFromItsOwnGoodsToo() {
        stock(workshop, Material.IRON_INGOT, 10, 8);
        assertNull(plugin.goods().buy(alex, workshop, Material.IRON_INGOT, 2));
        assertEquals(16, workshop.revenue, 1e-9);
    }

    // ------------------------------------------------------------ связи

    @Test
    void theSameManagerLinksImmediately() {
        assertNull(plugin.links().request(steve, workshop, shop));
        assertEquals("s", workshop.linkedTo);
        assertSame(shop, plugin.links().target(workshop));
        assertEquals(List.of(workshop), plugin.links().sourcesOf(shop));
        assertNotNull(plugin.links().request(steve, workshop, shop), "связь уже есть");
    }

    @Test
    void anotherManagersBusinessMustConfirm() {
        assertNull(plugin.links().request(steve, workshop, otherShop));
        assertNull(workshop.linkedTo, "до подтверждения связи нет");
        assertTrue(otherShop.linkRequests.contains("w"));
        assertTrue(drain(alex).contains("просит связать"));
        assertNotNull(plugin.links().request(steve, workshop, otherShop), "запрос уже отправлен");
        assertNotNull(plugin.links().accept(mia, otherShop, "w"), "не владелец");
        assertNull(plugin.links().accept(alex, otherShop, "w"));
        assertEquals("o", workshop.linkedTo);
        assertTrue(otherShop.linkRequests.isEmpty());
        assertTrue(drain(steve).contains("подтверждена"));
    }

    @Test
    void aDeclinedRequestIsDropped() {
        plugin.links().request(steve, workshop, otherShop);
        assertNull(plugin.links().decline(alex, otherShop, "w"));
        assertNull(workshop.linkedTo);
        assertTrue(otherShop.linkRequests.isEmpty());
        assertNotNull(plugin.links().decline(alex, otherShop, "w"), "запроса уже нет");
        assertTrue(drain(steve).contains("отклонена"));
    }

    @Test
    void linkRulesAreChecked() {
        assertNotNull(plugin.links().request(steve, shop, warehouse), "привязывается только мастерская");
        assertNotNull(plugin.links().request(steve, workshop, workshop), "к себе нельзя");
        Plot house = addPlot("h", Category.HOUSE, 80, 100, 5);
        plugin.service().buy(steve, house);
        assertNotNull(plugin.links().request(steve, workshop, house), "цель - магазин или склад");
        assertNotNull(plugin.links().request(steve, workshop, addPlot("f", Category.SHOP, 100, 100, 5)), "цель свободна");
        assertNotNull(plugin.links().request(alex, workshop, otherShop), "не управляющий мастерской");
        assertNull(plugin.links().request(boss, workshop, otherShop), "администратор связывает сразу");
    }

    @Test
    void eitherSideCanBreakTheLink() {
        plugin.links().request(steve, workshop, otherShop);
        plugin.links().accept(alex, otherShop, "w");
        assertNotNull(plugin.links().unlink(mia, workshop));
        assertNull(plugin.links().unlink(alex, workshop), "владелец цели");
        assertNull(workshop.linkedTo);
        assertNotNull(plugin.links().unlink(steve, workshop), "связи уже нет");
        plugin.links().request(steve, workshop, shop);
        assertNull(plugin.links().unlink(steve, workshop));
    }

    @Test
    void aFreedTargetBreaksTheLink() {
        plugin.links().request(steve, workshop, shop);
        plugin.service().evict(shop, new PlotService.Release("Boss", "тест", Journal.Action.EVICTED, false, Plot.RestoreMode.ITEMS_ONLY));
        server.getScheduler().performTicks(20);
        assertNull(workshop.linkedTo);
        assertNull(plugin.links().target(workshop));
    }

    @Test
    void aFreedWorkshopLosesItsLinkRequestsAndPrices() {
        plugin.links().request(steve, workshop, otherShop);
        stock(workshop, Material.IRON_INGOT, 5, 3);
        workshop.storefront = true;
        workshop.servicesPos = new double[] {1, 2, 3};
        plugin.service().evict(workshop, new PlotService.Release("Boss", "тест", Journal.Action.EVICTED, false, Plot.RestoreMode.ITEMS_ONLY));
        server.getScheduler().performTicks(20);
        assertTrue(otherShop.linkRequests.isEmpty());
        assertTrue(workshop.goods.isEmpty() && workshop.prices.isEmpty());
        assertFalse(workshop.storefront);
        assertNull(workshop.servicesPos);
    }

    @Test
    void releasingReturnsTheGoodsToTheManagerAndSeizureArrestsThem() {
        stock(shop, Material.IRON_INGOT, 25, 3);
        steve.getInventory().clear();
        plugin.service().evict(shop, new PlotService.Release("Boss", "нарушение", Journal.Action.SEIZED, true, Plot.RestoreMode.ITEMS_ONLY));
        server.getScheduler().performTicks(20);
        int arrested = 0;
        for (StorageEntry e : plugin.storage().entries(steve.getUniqueId())) {
            if (e.state == StorageEntry.State.ARRESTED) {
                for (byte[] b : e.stacks) {
                    ItemStack s = ItemStack.deserializeBytes(b);
                    if (s.getType() == Material.IRON_INGOT) {
                        arrested += s.getAmount();
                    }
                }
            }
        }
        assertEquals(25, arrested);
        assertTrue(shop.goods.isEmpty());
    }

    // ------------------------------------------------------------ продукция мастерской

    private void machineWithProduct(int output) {
        Block b = server.getWorld("world").getBlockAt(1, 1, 1);
        b.setType(Material.FURNACE);
        plugin.stations().addMachine(workshop, 1, 1, 1, Station.FURNACE);
        Plot.MachineState st = new Plot.MachineState();
        st.recipe = "furnace:IRON_INGOT";
        st.output = output;
        workshop.machineStates.put("1,1,1", st);
        plugin.recipes().setSource(() -> List.<org.bukkit.inventory.Recipe>of(new org.bukkit.inventory.FurnaceRecipe(
                new org.bukkit.NamespacedKey(plugin, "x"), new ItemStack(Material.IRON_INGOT), Material.RAW_IRON, 0.7f, 200)).iterator());
    }

    @Test
    void linkedProductionGoesToTheTargetsGoods() {
        plugin.links().request(steve, workshop, shop);
        machineWithProduct(12);
        plugin.factory().tick();
        assertEquals(12, plugin.goods().stock(shop, Material.IRON_INGOT));
        assertEquals(0, workshop.machineStates.get("1,1,1").output);
    }

    @Test
    void whenTheTargetIsFullTheRestWaitsInTheMachine() {
        plugin.getConfig().set("goods.capacity.shop", 5);
        plugin.links().request(steve, workshop, shop);
        machineWithProduct(12);
        plugin.factory().tick();
        assertEquals(5, plugin.goods().stock(shop, Material.IRON_INGOT));
        assertEquals(7, workshop.machineStates.get("1,1,1").output);
    }

    @Test
    void aWarehouseTargetStoresWithoutSelling() {
        plugin.links().request(steve, workshop, warehouse);
        machineWithProduct(10);
        plugin.factory().tick();
        assertEquals(10, plugin.goods().stock(warehouse, Material.IRON_INGOT));
        assertTrue(plugin.goods().listed(warehouse).isEmpty() || !plugin.goods().sells(warehouse));
    }

    @Test
    void ownStorefrontKeepsTheProductOnTheWorkshopGoods() {
        workshop.storefront = true;
        machineWithProduct(6);
        plugin.factory().tick();
        assertEquals(6, plugin.goods().stock(workshop, Material.IRON_INGOT));
    }

    @Test
    void aLinkBeatsTheOwnStorefrontAndWithoutBothTheProductWaits() {
        workshop.storefront = true;
        plugin.links().request(steve, workshop, shop);
        machineWithProduct(4);
        plugin.factory().tick();
        assertEquals(4, plugin.goods().stock(shop, Material.IRON_INGOT));
        assertEquals(0, plugin.goods().stock(workshop, Material.IRON_INGOT));
        plugin.links().unlink(steve, workshop);
        workshop.storefront = false;
        workshop.machineStates.get("1,1,1").output = 3;
        plugin.factory().tick();
        assertEquals(3, workshop.machineStates.get("1,1,1").output, "без связи, витрины и сундука продукция ждёт в буфере");
    }

    // ------------------------------------------------------------ сохранение

    @Test
    void businessStateSurvivesARestart() {
        stock(shop, Material.IRON_INGOT, 9, 4.5);
        plugin.links().request(steve, workshop, otherShop);
        workshop.storefront = true;
        workshop.servicesPos = new double[] {1.5, 2.5, 3.5};
        workshop.managerPos = new double[] {4, 5, 6};
        plugin.links().request(steve, workshop, shop);
        otherShop.linkRequests.add("zzz");
        plugin.plots().save();
        plugin.plots().load();
        Plot s = plugin.plots().get("s");
        Plot w = plugin.plots().get("w");
        assertEquals(9, s.goods.get(Material.IRON_INGOT));
        assertEquals(4.5, s.prices.get(Material.IRON_INGOT), 1e-9);
        assertEquals("s", w.linkedTo);
        assertTrue(w.storefront);
        assertArrayEquals(new double[] {1.5, 2.5, 3.5}, w.servicesPos, 1e-9);
        assertArrayEquals(new double[] {4, 5, 6}, w.managerPos, 1e-9);
        assertTrue(plugin.plots().get("o").linkRequests.contains("zzz"));
    }

    // ------------------------------------------------------------ положение голограмм

    @Test
    void hologramKindsAreParsed() {
        assertEquals(Plot.HoloKind.MAIN, Plot.HoloKind.parse("main"));
        assertEquals(Plot.HoloKind.SERVICES, Plot.HoloKind.parse("services"));
        assertEquals(Plot.HoloKind.SERVICES, Plot.HoloKind.parse("УСЛУГИ"));
        assertEquals(Plot.HoloKind.MANAGER, Plot.HoloKind.parse("manager"));
        assertNull(Plot.HoloKind.parse("w"));
        assertNull(Plot.HoloKind.parse(null));
    }

    @Test
    void thePanelPositionMovesFreelyWithoutApproval() {
        assertNull(plugin.moderation().submitHolo(steve, workshop, new Location(server.getWorld("world"), 4.5, 3, 4.5), Plot.HoloKind.MANAGER));
        assertArrayEquals(new double[] {4.5, 3, 4.5}, workshop.managerPos, 1e-9);
        assertTrue(plugin.moderation().pending().isEmpty());
        assertNotNull(plugin.moderation().submitHolo(steve, workshop, new Location(server.getWorld("world"), 400, 3, 4), Plot.HoloKind.MANAGER),
                "вне участка нельзя");
        assertNotNull(plugin.moderation().submitHolo(alex, workshop, new Location(server.getWorld("world"), 4, 3, 4), Plot.HoloKind.MANAGER));
    }

    @Test
    void theServicesHologramPositionNeedsApproval() {
        assertNull(plugin.moderation().submitHolo(steve, workshop, new Location(server.getWorld("world"), 6.5, 3, 6.5), Plot.HoloKind.SERVICES));
        assertNull(workshop.servicesPos, "до одобрения положения нет");
        Request r = plugin.moderation().pending().get(0);
        assertTrue(r.text.startsWith("SERVICES "), r.text);
        plugin.moderation().approve(r, "Boss");
        assertArrayEquals(new double[] {6.5, 3, 6.5}, workshop.servicesPos, 1e-9);
        assertFalse(workshop.hasHolo, "общая голограмма не затронута");
    }

    @Test
    void differentKindsOfRequestsDoNotReplaceEachOther() {
        plugin.moderation().submitHolo(steve, workshop, new Location(server.getWorld("world"), 6, 3, 6), Plot.HoloKind.SERVICES);
        plugin.moderation().submitHolo(steve, workshop, new Location(server.getWorld("world"), 5, 3, 5), Plot.HoloKind.MAIN);
        assertEquals(2, plugin.moderation().pending().size());
        plugin.moderation().submitHolo(steve, workshop, new Location(server.getWorld("world"), 7, 3, 7), Plot.HoloKind.SERVICES);
        assertEquals(2, plugin.moderation().pending().size(), "новая заявка того же вида вытесняет прежнюю");
    }

    @Test
    void oldThreeNumberRequestsStillMeanTheMainHologram() {
        assertEquals(Plot.HoloKind.MAIN, ru.buildmaster.service.ModerationService.holoKind("5.00 2.00 5.00"));
        assertEquals(Plot.HoloKind.SERVICES, ru.buildmaster.service.ModerationService.holoKind("SERVICES 5.00 2.00 5.00"));
        assertNull(ru.buildmaster.service.ModerationService.holoKind("WHAT 5 2 5"));
    }

    @Test
    void adminsMoveAnyHologramAtOnce() {
        assertNull(plugin.moderation().submitHolo(boss, workshop, new Location(server.getWorld("world"), 3, 3, 3), Plot.HoloKind.SERVICES));
        assertArrayEquals(new double[] {3, 3, 3}, workshop.servicesPos, 1e-9);
    }

    @Test
    void theCommandAcceptsAKindAndAPlot() {
        steve.teleport(new Location(server.getWorld("world"), 5, 1, 5));
        steve.performCommand("district hologram manager w");
        assertNotNull(workshop.managerPos);
        steve.performCommand("district hologram services w");
        assertEquals(1, plugin.moderation().pending().size());
        steve.performCommand("district hologram w");
        assertEquals(2, plugin.moderation().pending().size(), "без вида - общая голограмма");
    }

    // ------------------------------------------------------------ голограммы

    private static final class Fake implements java.lang.reflect.InvocationHandler {
        boolean valid = true;
        net.kyori.adventure.text.Component text;
        Location at;

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) {
            return switch (m.getName()) {
                case "isValid" -> valid;
                case "remove" -> {
                    valid = false;
                    yield null;
                }
                case "text" -> {
                    if (a != null && a.length == 1) {
                        text = (net.kyori.adventure.text.Component) a[0];
                        yield null;
                    }
                    yield text;
                }
                case "teleport" -> {
                    at = (Location) a[0];
                    yield true;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == a[0];
                case "toString" -> "Fake";
                default -> m.getReturnType() == boolean.class ? (Object) false : null;
            };
        }
    }

    private final List<Fake> fakes = new ArrayList<>();

    private TextDisplay spawn(Location at, java.util.function.Consumer<TextDisplay> init) {
        Fake f = new Fake();
        f.at = at;
        fakes.add(f);
        TextDisplay d = (TextDisplay) java.lang.reflect.Proxy.newProxyInstance(TextDisplay.class.getClassLoader(),
                new Class<?>[] {TextDisplay.class}, f);
        init.accept(d);
        return d;
    }

    private String shown(int i) {
        return PlainTextComponentSerializer.plainText().serialize(fakes.get(i).text);
    }

    private void near(PlayerMock p, double x, double z) {
        p.teleport(new Location(server.getWorld("world"), x, 3, z));
    }

    @Test
    void theBoardListsServicesAndGoodsWithPriceAndStock() {
        plugin.getConfig().set("goods.default-price", 0);
        stock(workshop, Material.IRON_INGOT, 24, 10);
        stock(workshop, Material.GOLD_INGOT, 0, 30);
        stock(workshop, Material.DIRT, 5, -1);
        var offer = plugin.catalog().offers().get(0);
        workshop.offers.put(offer.key(), new Plot.Offer(true, 3));
        String text = String.join("\n", ServiceBoardText.lines(plugin, workshop)).replaceAll("<[^>]+>", "");
        assertTrue(text.contains("Услуги") && text.contains("Товары"), text);
        assertTrue(text.contains("3$"), "цена услуги");
        assertTrue(text.contains("10$") && text.contains("в наличии: 24"), text);
        assertTrue(text.contains("нет в наличии"), "товар закончился, цена видна");
        assertFalse(text.toLowerCase().contains("dirt"), "товар без цены не показывается");
        assertTrue(text.contains("/district shop w"), "как купить");
    }

    @Test
    void anEmptyBoardIsNotShown() {
        assertTrue(ServiceBoardText.lines(plugin, workshop).isEmpty());
        assertTrue(ServiceBoardText.lines(plugin, shop).isEmpty());
    }

    @Test
    void theBoardIsCutAfterSevenGoods() {
        for (Material m : new Material[] {Material.IRON_INGOT, Material.GOLD_INGOT, Material.COPPER_INGOT, Material.DIAMOND,
                Material.EMERALD, Material.COAL, Material.STICK, Material.APPLE, Material.BREAD}) {
            stock(shop, m, 5, 2);
        }
        String text = String.join("\n", ServiceBoardText.lines(plugin, shop)).replaceAll("<[^>]+>", "");
        assertTrue(text.contains("и ещё 2"), text);
    }

    @Test
    void theServicesHologramAppearsAtItsOwnPositionForEveryoneNear() {
        plugin.servicesHolograms().setSpawner(this::spawn);
        stock(shop, Material.IRON_INGOT, 5, 3);
        shop.servicesPos = new double[] {25.5, 3, 25.5};
        near(mia, 25, 25);
        plugin.servicesHolograms().tick();
        assertEquals(1, plugin.servicesHolograms().spawnedCount());
        assertEquals(25.5, fakes.get(0).at.getX(), 1e-9);
        assertTrue(shown(0).contains("Товары"), shown(0));
        near(mia, 5000, 5000);
        plugin.servicesHolograms().tick();
        assertEquals(0, plugin.servicesHolograms().spawnedCount(), "рядом никого - убрана");
    }

    @Test
    void withoutItsOwnPositionItSitsBelowTheMainHologram() {
        plugin.servicesHolograms().setSpawner(this::spawn);
        stock(shop, Material.IRON_INGOT, 5, 3);
        shop.hasHolo = true;
        shop.holoX = 25;
        shop.holoY = 5;
        shop.holoZ = 25;
        near(mia, 25, 24);
        plugin.servicesHolograms().tick();
        assertEquals(1, plugin.servicesHolograms().spawnedCount());
        assertTrue(fakes.get(0).at.getY() < 5, "ниже общей голограммы: " + fakes.get(0).at.getY());
    }

    @Test
    void noPositionAtAllMeansNoHologram() {
        plugin.servicesHolograms().setSpawner(this::spawn);
        stock(shop, Material.IRON_INGOT, 5, 3);
        near(mia, 25, 25);
        plugin.servicesHolograms().tick();
        assertEquals(0, plugin.servicesHolograms().spawnedCount());
    }

    @Test
    void theHologramFollowsStockChangesAndDisappearsWhenEmptyOrFreed() {
        plugin.servicesHolograms().setSpawner(this::spawn);
        stock(shop, Material.IRON_INGOT, 5, 3);
        shop.servicesPos = new double[] {25.5, 3, 25.5};
        near(mia, 25, 25);
        plugin.servicesHolograms().tick();
        assertTrue(shown(0).contains("в наличии: 5"));
        assertNull(plugin.goods().buy(alex, shop, Material.IRON_INGOT, 2));
        plugin.servicesHolograms().tick();
        assertEquals(1, fakes.size(), "обновлена та же голограмма");
        assertTrue(shown(0).contains("в наличии: 3"), shown(0));
        shop.prices.clear();
        plugin.servicesHolograms().tick();
        assertEquals(0, plugin.servicesHolograms().spawnedCount(), "показывать нечего");
        plugin.getConfig().set("holograms.services", false);
        plugin.servicesHolograms().tick();
        assertEquals(0, plugin.servicesHolograms().spawnedCount());
    }

    @Test
    void theManagerPanelUsesItsOwnPosition() {
        List<Fake> panelFakes = new ArrayList<>();
        plugin.managerPanels().setSpawner((at, init) -> {
            TextDisplay d = spawn(at, init);
            panelFakes.add(fakes.get(fakes.size() - 1));
            return d;
        });
        workshop.managerPos = new double[] {2.5, 4, 2.5};
        near(steve, 2, 2);
        plugin.managerPanels().tick();
        assertEquals(1, panelFakes.size(), "панель есть и без общей голограммы");
        assertEquals(4, panelFakes.get(0).at.getY(), 1e-9);
        assertEquals(2.5, panelFakes.get(0).at.getX(), 1e-9);
        near(alex, 2, 2);
        near(steve, 5000, 5000);
        plugin.managerPanels().tick();
        assertEquals(0, plugin.managerPanels().spawnedCount(), "чужому игроку панель не создаётся");
    }

    // ------------------------------------------------------------ команды

    @Test
    void shopGoodsAndLinkCommandsWork() {
        give(steve, Material.IRON_INGOT, 50);
        steve.teleport(new Location(server.getWorld("world"), 25, 1, 5));
        steve.performCommand("district goods deposit IRON_INGOT s");
        assertEquals(50, plugin.goods().stock(shop, Material.IRON_INGOT));
        steve.performCommand("district goods price IRON_INGOT 2.5 s");
        assertEquals(2.5, plugin.goods().price(shop, Material.IRON_INGOT), 1e-9);
        drain(steve);
        steve.performCommand("district goods list s");
        assertTrue(drain(steve).contains("IRON_INGOT"));
        alex.performCommand("district shop s");
        assertTrue(alex.getOpenInventory().getTopInventory().getHolder() instanceof ru.buildmaster.gui.StorefrontMenu);
        alex.performCommand("district shop wh");
        assertTrue(drain(alex).contains("ничего не продаёт"));
        steve.performCommand("district link w s");
        assertEquals("s", workshop.linkedTo);
        steve.performCommand("district unlink w");
        assertNull(workshop.linkedTo);
        steve.performCommand("district link w o");
        alex.performCommand("district link accept w o");
        assertEquals("o", workshop.linkedTo);
        steve.performCommand("district storefront on w");
        assertTrue(workshop.storefront);
        steve.performCommand("district goods take IRON_INGOT 10 s");
        assertEquals(40, plugin.goods().stock(shop, Material.IRON_INGOT));
        steve.performCommand("district goods price IRON_INGOT off s");
        assertEquals(0, plugin.goods().price(shop, Material.IRON_INGOT), 1e-9);
        mia.performCommand("district goods deposit IRON_INGOT s");
        assertEquals(40, plugin.goods().stock(shop, Material.IRON_INGOT), "чужой участок");
    }

    @Test
    void storefrontCommandIsForWorkshopsOnly() {
        drain(steve);
        steve.performCommand("district storefront on s");
        assertTrue(drain(steve).contains("у мастерских"));
        assertFalse(shop.storefront);
    }

    // ------------------------------------------------------------ меню

    @Test
    void theStorefrontMenuBuysOneSixteenOrSixtyFour() {
        stock(shop, Material.IRON_INGOT, 100, 1);
        alex.getInventory().clear();
        new ru.buildmaster.gui.StorefrontMenu(plugin, alex, shop, null, 0).open();
        alex.simulateInventoryClick(0);
        assertEquals(1, count(alex, Material.IRON_INGOT));
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryClickEvent(alex.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0, org.bukkit.event.inventory.ClickType.SHIFT_LEFT,
                org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY));
        assertEquals(17, count(alex, Material.IRON_INGOT));
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryClickEvent(alex.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0, org.bukkit.event.inventory.ClickType.RIGHT,
                org.bukkit.event.inventory.InventoryAction.PICKUP_HALF));
        assertEquals(81, count(alex, Material.IRON_INGOT));
        assertEquals(19, plugin.goods().stock(shop, Material.IRON_INGOT));
    }

    @Test
    void theStorefrontMenuShowsPricesAndStock() {
        stock(shop, Material.IRON_INGOT, 7, 3);
        var inv = new ru.buildmaster.gui.StorefrontMenu(plugin, alex, shop, null, 0).getInventory();
        assertEquals(Material.IRON_INGOT, inv.getItem(0).getType());
        String lore = inv.getItem(0).getItemMeta().lore().stream().map(l -> PlainTextComponentSerializer.plainText().serialize(l))
                .reduce("", (a, b) -> a + b + "\n");
        assertTrue(lore.contains("3$") && lore.contains("В наличии: 7") && lore.contains("купить"), lore);
    }

    @Test
    void theGoodsMenuSetsPricesAndWithdraws() {
        stock(shop, Material.IRON_INGOT, 100, -1);
        steve.getInventory().clear();
        new ru.buildmaster.gui.GoodsMenu(plugin, steve, shop, null, 0).open();
        steve.simulateInventoryClick(0);                 // ЛКМ - цена через чат
        assertTrue(plugin.chatPrompt().isWaiting(steve));
        plugin.chatPrompt().deliver(steve, "4,5");
        server.getScheduler().performTicks(2);
        assertEquals(4.5, plugin.goods().price(shop, Material.IRON_INGOT), 1e-9);
        new ru.buildmaster.gui.GoodsMenu(plugin, steve, shop, null, 0).open();
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryClickEvent(steve.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0, org.bukkit.event.inventory.ClickType.RIGHT,
                org.bukkit.event.inventory.InventoryAction.PICKUP_HALF));
        assertEquals(36, plugin.goods().stock(shop, Material.IRON_INGOT));
        assertEquals(64, count(steve, Material.IRON_INGOT));
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryClickEvent(steve.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0, org.bukkit.event.inventory.ClickType.SHIFT_LEFT,
                org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY));
        assertEquals(0, plugin.goods().price(shop, Material.IRON_INGOT), 1e-9, "Shift+ЛКМ снимает с продажи");
    }

    @Test
    void theGoodsMenuDepositsTheInventory() {
        steve.getInventory().clear();
        give(steve, Material.IRON_INGOT, 20);
        new ru.buildmaster.gui.GoodsMenu(plugin, steve, shop, null, 0).open();
        steve.simulateInventoryClick(47);
        assertEquals(20, plugin.goods().stock(shop, Material.IRON_INGOT));
    }

    @Test
    void plotMenuLinksToGoodsForManagersAndToTheStorefrontForVisitors() {
        stock(shop, Material.IRON_INGOT, 5, 2);
        new ru.buildmaster.gui.PlotMenu(plugin, steve, shop, null).open();
        assertEquals(Material.BARREL, steve.getOpenInventory().getTopInventory().getItem(26).getType());
        new ru.buildmaster.gui.PlotMenu(plugin, alex, shop, null).open();
        assertEquals(Material.EMERALD, alex.getOpenInventory().getTopInventory().getItem(24).getType());
        new ru.buildmaster.gui.PlotMenu(plugin, alex, warehouse, null).open();
        assertNotEquals(Material.EMERALD, alex.getOpenInventory().getTopInventory().getItem(24).getType(), "у склада витрины нет");
    }
}
