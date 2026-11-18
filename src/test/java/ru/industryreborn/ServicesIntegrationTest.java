package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.ServiceOrder;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.PlotService;
import ru.industryreborn.service.ServiceCatalog;
import ru.industryreborn.support.PluginTestBase;

/** Услуги мастерских: заказы, эскроу, выполнение, заморозка, выдача, возврат. */
class ServicesIntegrationTest extends PluginTestBase {

    private final AtomicLong wall = new AtomicLong(1_000_000);
    private PlayerMock steve;      // управляющий
    private PlayerMock alex;       // клиент
    private PlayerMock mia;        // второй клиент
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
        plugin.catalog().setSmeltLookup(in -> switch (in.getType()) {
            case IRON_ORE -> new ServiceCatalog.SmeltResult(new ItemStack(Material.IRON_INGOT), 10);
            case SAND -> new ServiceCatalog.SmeltResult(new ItemStack(Material.GLASS), 10);
            default -> null;
        });
        steve = player("Steve", 100_000);
        alex = player("Alex", 100_000);
        mia = player("Mia", 100_000);
        boss = admin("Boss");
        workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        plugin.service().rent(steve, workshop, 10);
        // две печи и наковальня: ставит управляющий
        place(Material.FURNACE, 1);
        place(Material.BLAST_FURNACE, 2);
        place(Material.ANVIL, 3);
        assertTrue(plugin.power().deposit(steve, workshop, 50_000));
        enable("smelt", 2.0);
        enable("repair", 10.0);
        plugin.orders().tick();      // запоминает начальный момент для расчёта времени
    }

    private void place(Material m, int dx) {
        Block b = server.getWorld("world").getBlockAt(dx, 1, 1);
        b.setType(m);
        server.getPluginManager().callEvent(new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0),
                new ItemStack(m), steve, true, EquipmentSlot.HAND));
    }

    private void enable(String key, double price) {
        assertNull(plugin.orders().configureOffer(steve, workshop, key, price, true));
    }

    private void pass(long seconds) {
        wall.addAndGet(seconds * 1000);
        plugin.power().tick();
        plugin.orders().tick();
    }

    private void hold(PlayerMock p, ItemStack stack) {
        p.getInventory().setItemInMainHand(stack);
    }

    private ServiceOrder order(PlayerMock client) {
        List<ServiceOrder> list = plugin.orders().ofClient(client.getUniqueId());
        assertFalse(list.isEmpty(), "у клиента нет заказа");
        return list.get(list.size() - 1);
    }

    private ServiceOrder orderSmelt(PlayerMock client, int ore) {
        hold(client, new ItemStack(Material.IRON_ORE, ore));
        assertNull(plugin.orders().create(client, workshop, "smelt", false));
        return order(client);
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

    /** Предметы клиента: в инвентаре и в хранилище (онлайн-игроку сначала выдаётся в инвентарь). */
    private int owned(PlayerMock p, Material m) {
        return count(p, m) + stored(p.getUniqueId(), m);
    }

    private int stored(UUID id, Material m) {
        int n = 0;
        for (StorageEntry e : plugin.storage().entries(id)) {
            for (byte[] b : e.stacks) {
                ItemStack s = ItemStack.deserializeBytes(b);
                if (s.getType() == m) {
                    n += s.getAmount();
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------ создание заказа

    @Test
    void orderTakesMaterialsAndMoneyIntoEscrow() {
        double wallet = economy.getBalance(alex);
        double account = plugin.power().balance(workshop);

        ServiceOrder o = orderSmelt(alex, 20);

        assertEquals(ServiceOrder.State.PENDING, o.state);
        assertEquals(20, o.units);
        assertEquals(40, o.paid, 1e-9);
        assertEquals(wallet - 40, economy.getBalance(alex), 1e-9);
        assertEquals(0, count(alex, Material.IRON_ORE), "материалы ушли в эскроу");
        assertEquals(account, plugin.power().balance(workshop), 1e-6, "деньги мастерской достанутся только при выдаче");
        assertEquals(Journal.Action.SERVICE_ORDER, plugin.journal().forPlot("w").get(0).action());
    }

    @Test
    void managerIsToldAboutANewOrder() {
        int before = plugin.warnings().forPlayer(steve.getUniqueId()).size();
        orderSmelt(alex, 5);
        var warnings = plugin.warnings().forPlayer(steve.getUniqueId());
        assertEquals(before + 1, warnings.size());
        assertTrue(warnings.get(0).text.contains("Новый заказ"));
    }

    @Test
    void serviceMustBeEnabledByTheManager() {
        plugin.orders().configureOffer(steve, workshop, "smelt", null, false);
        hold(alex, new ItemStack(Material.IRON_ORE, 5));
        assertNotNull(plugin.orders().create(alex, workshop, "smelt", false));
        assertEquals(5, count(alex, Material.IRON_ORE), "ничего не списано");
    }

    @Test
    void unknownServiceIsRefused() {
        hold(alex, new ItemStack(Material.IRON_ORE, 5));
        assertNotNull(plugin.orders().create(alex, workshop, "teleport", false));
    }

    @Test
    void noOrdersWithoutElectricity() {
        plugin.power().withdraw(steve, workshop, plugin.power().balance(workshop) - workshop.account.ratePerDay());
        pass(10 * 86_400);                                      // счёт опустел
        assertFalse(plugin.power().isPowered(workshop));
        hold(alex, new ItemStack(Material.IRON_ORE, 5));

        String error = plugin.orders().create(alex, workshop, "smelt", false);

        assertNotNull(error);
        assertTrue(error.contains("обесточена"), error);
        assertEquals(5, count(alex, Material.IRON_ORE));
    }

    @Test
    void noOrdersWhenDisconnectedFromThePlantAndTheReasonIsShown() {
        addPlot("p1", Category.POWER_PLANT, 100, -1, -1).plantCapacity = 1000;
        plugin.grid().connect(workshop, plugin.plots().get("p1"), "Boss");
        plugin.grid().disconnect(workshop, "плановые работы", "Boss");
        hold(alex, new ItemStack(Material.IRON_ORE, 5));

        String error = plugin.orders().create(alex, workshop, "smelt", false);

        assertNotNull(error);
        assertTrue(error.contains("плановые работы"), error);
    }

    @Test
    void theWorkshopNeedsTheRightStation() {
        plugin.catalog().setSmeltLookup(in -> new ServiceCatalog.SmeltResult(new ItemStack(Material.GLASS), 10));
        Block anvil = server.getWorld("world").getBlockAt(3, 1, 1);
        server.getPluginManager().callEvent(new org.bukkit.event.block.BlockBreakEvent(anvil, steve));
        ItemStack pick = new ItemStack(Material.IRON_PICKAXE);
        pick.editMeta(Damageable.class, m -> m.setDamage(100));
        hold(alex, pick);

        String error = plugin.orders().create(alex, workshop, "repair", false);

        assertNotNull(error);
        assertTrue(error.contains("нужного станка"), error);
    }

    @Test
    void youCannotOrderFromYourOwnWorkshop() {
        hold(steve, new ItemStack(Material.IRON_ORE, 5));
        assertNotNull(plugin.orders().create(steve, workshop, "smelt", false));
    }

    @Test
    void notEnoughMoneyMeansNothingIsTaken() {
        economy.set(alex, 3);
        hold(alex, new ItemStack(Material.IRON_ORE, 10));
        String error = plugin.orders().create(alex, workshop, "smelt", false);
        assertNotNull(error);
        assertEquals(10, count(alex, Material.IRON_ORE));
        assertEquals(3, economy.getBalance(alex), 1e-9);
    }

    @Test
    void emptyHandOrNonSmeltableItemIsRefused() {
        hold(alex, new ItemStack(Material.AIR));
        assertNotNull(plugin.orders().create(alex, workshop, "smelt", false));
        hold(alex, new ItemStack(Material.DIRT, 5));
        String error = plugin.orders().create(alex, workshop, "smelt", false);
        assertNotNull(error);
        assertEquals(5, count(alex, Material.DIRT));
    }

    @Test
    void orderSizeAndQueueAreLimited() {
        plugin.getConfig().set("services.max-units-per-order", 10);
        hold(alex, new ItemStack(Material.IRON_ORE, 11));
        assertNotNull(plugin.orders().create(alex, workshop, "smelt", false));
        hold(alex, new ItemStack(Material.IRON_ORE, 10));
        assertNull(plugin.orders().create(alex, workshop, "smelt", false));

        plugin.getConfig().set("services.max-orders-per-client", 1);
        hold(alex, new ItemStack(Material.IRON_ORE, 1));
        assertNotNull(plugin.orders().create(alex, workshop, "smelt", false), "лимит заказов клиента");

        plugin.getConfig().set("services.max-active-orders-per-workshop", 1);
        hold(mia, new ItemStack(Material.IRON_ORE, 1));
        assertNotNull(plugin.orders().create(mia, workshop, "smelt", false), "очередь мастерской заполнена");
    }

    @Test
    void shiftModeTakesEveryMatchingStackFromTheInventory() {
        alex.getInventory().clear();
        alex.getInventory().setItem(0, new ItemStack(Material.IRON_ORE, 30));
        alex.getInventory().setItem(5, new ItemStack(Material.IRON_ORE, 12));
        alex.getInventory().setItem(7, new ItemStack(Material.COAL, 40));
        alex.getInventory().setHeldItemSlot(0);

        assertNull(plugin.orders().create(alex, workshop, "smelt", true));

        assertEquals(42, order(alex).units);
        assertEquals(0, count(alex, Material.IRON_ORE));
        assertEquals(40, count(alex, Material.COAL), "посторонние предметы не тронуты");
    }

    // ------------------------------------------------------------ выполнение

    @Test
    void twoFurnacesCutTheTimeInHalf() {
        ServiceOrder o = orderSmelt(alex, 20);    // 10 с на предмет / 2 печи = 5 с, всего 100 с

        pass(1);
        assertEquals(ServiceOrder.State.ACTIVE, o.state);
        pass(49);
        assertEquals(10, o.unitsDone);
        assertEquals(ServiceOrder.State.ACTIVE, o.state);
        pass(50);

        assertEquals(ServiceOrder.State.READY, o.state);
        assertEquals(20, o.unitsDone);
    }

    @Test
    void timeFactorSpeedsEverythingUp() {
        plugin.getConfig().set("services.time-factor", 0.1);
        ServiceOrder o = orderSmelt(alex, 20);    // 0.5 с на предмет, всего 10 с
        pass(10);
        assertEquals(ServiceOrder.State.READY, o.state);
    }

    @Test
    void moreStationsMeanFasterOrders() {
        place(Material.SMOKER, 4);                // третья печь: 10/3 с на предмет
        ServiceOrder o = orderSmelt(alex, 30);    // 100 с
        pass(1);
        pass(99);
        assertEquals(ServiceOrder.State.READY, o.state);
    }

    @Test
    void clientIsNotifiedWhenTheOrderIsReady() {
        orderSmelt(alex, 2);
        pass(1);
        pass(20);
        assertTrue(plugin.warnings().forPlayer(alex.getUniqueId()).get(0).text.contains("готов"));
    }

    @Test
    void ordersOfTheSameStationAreProcessedOneAfterAnother() {
        ServiceOrder first = orderSmelt(alex, 10);   // 50 с
        ServiceOrder second = orderSmelt(mia, 10);   // 50 с, но после первого
        pass(1);
        pass(40);
        assertEquals(ServiceOrder.State.ACTIVE, first.state);
        assertEquals(ServiceOrder.State.PENDING, second.state, "второй ждёт своей очереди");
        pass(10);
        assertEquals(ServiceOrder.State.READY, first.state);
        pass(1);
        assertEquals(ServiceOrder.State.ACTIVE, second.state);
        pass(50);
        assertEquals(ServiceOrder.State.READY, second.state);
    }

    @Test
    void differentStationsWorkInParallel() {
        ServiceOrder smelt = orderSmelt(alex, 20);
        ItemStack pick = new ItemStack(Material.IRON_PICKAXE);
        pick.editMeta(Damageable.class, m -> m.setDamage(100));
        hold(mia, pick);
        assertNull(plugin.orders().create(mia, workshop, "repair", false));
        ServiceOrder repair = order(mia);

        pass(1);
        assertEquals(ServiceOrder.State.ACTIVE, smelt.state);
        assertEquals(ServiceOrder.State.ACTIVE, repair.state, "наковальня не ждёт печи");
        pass(5);
        assertEquals(ServiceOrder.State.READY, repair.state, "ремонт одного предмета - 5 с");
        assertEquals(ServiceOrder.State.ACTIVE, smelt.state);
    }

    @Test
    void etaTakesTheQueueIntoAccount() {
        ServiceOrder first = orderSmelt(alex, 10);
        ServiceOrder second = orderSmelt(mia, 10);
        assertEquals(50, plugin.orders().etaSeconds(first), 1e-6);
        assertEquals(100, plugin.orders().etaSeconds(second), 1e-6);
        pass(1);
        pass(19);
        assertEquals(30, plugin.orders().etaSeconds(first), 1e-6);
        assertEquals(80, plugin.orders().etaSeconds(second), 1e-6);
    }

    // ------------------------------------------------------------ заморозка без электричества

    @Test
    void orderFreezesWithoutPowerAndResumesLater() {
        ServiceOrder o = orderSmelt(alex, 20);
        pass(1);
        pass(24);                                               // 5 предметов
        assertEquals(5, o.unitsDone);

        // обесточиваем: отключаем от станции
        addPlot("p1", Category.POWER_PLANT, 100, -1, -1).plantCapacity = 1000;
        plugin.grid().connect(workshop, plugin.plots().get("p1"), "Boss");
        plugin.grid().disconnect(workshop, "авария", "Boss");
        pass(10_000);
        assertEquals(5, o.unitsDone, "пока обесточено, заказ не движется");
        assertEquals(ServiceOrder.State.ACTIVE, o.state);

        plugin.grid().restore(workshop, "Boss");
        pass(1);
        assertEquals(5, o.unitsDone, "время простоя не засчитывается");
        pass(73);
        assertEquals(ServiceOrder.State.ACTIVE, o.state);
        pass(1);
        assertEquals(ServiceOrder.State.READY, o.state);
    }

    @Test
    void newOrdersAreRefusedButOldOnesStayWhileDark() {
        ServiceOrder o = orderSmelt(alex, 20);
        addPlot("p1", Category.POWER_PLANT, 100, -1, -1).plantCapacity = 1000;
        plugin.grid().connect(workshop, plugin.plots().get("p1"), "Boss");
        plugin.grid().disconnect(workshop, "авария", "Boss");

        hold(mia, new ItemStack(Material.IRON_ORE, 1));
        assertNotNull(plugin.orders().create(mia, workshop, "smelt", false));
        assertTrue(plugin.orders().all().contains(o));
    }

    @Test
    void aReadyOrderCanAlwaysBeCollectedEvenWithoutPower() {
        ServiceOrder o = orderSmelt(alex, 2);
        pass(1);
        pass(20);
        assertEquals(ServiceOrder.State.READY, o.state);

        addPlot("p1", Category.POWER_PLANT, 100, -1, -1).plantCapacity = 1000;
        plugin.grid().connect(workshop, plugin.plots().get("p1"), "Boss");
        plugin.grid().disconnect(workshop, "авария", "Boss");

        assertNull(plugin.orders().collect(alex, o.id));
        assertEquals(2, count(alex, Material.IRON_INGOT));
    }

    @Test
    void usageModeChargesEveryUnitAndStallsWhenTheAccountIsEmpty() {
        boss.performCommand("district power admin mode USAGE");
        // на счёте только на 3 операции (цена операции плавки = 1.0)
        double keep = 3;
        plugin.power().touch(workshop);
        workshop.account.restore(keep, 0, workshop.account.updatedAtSec());
        ServiceOrder o = orderSmelt(alex, 20);

        pass(1);
        pass(100);

        assertEquals(3, o.unitsDone, "денег хватило на 3 единицы");
        assertEquals(ServiceOrder.State.ACTIVE, o.state);
        assertEquals(0, plugin.power().balance(workshop), 1e-9);

        plugin.power().deposit(steve, workshop, 1000);
        pass(1);
        pass(100);
        assertEquals(ServiceOrder.State.READY, o.state);
        assertEquals(20, o.unitsDone);
        assertTrue(plugin.power().stats().byType("w").get("smelter").operations() >= 20);
    }

    // ------------------------------------------------------------ выдача

    @Test
    void collectingGivesTheResultAndPaysTheWorkshopAccount() {
        ServiceOrder o = orderSmelt(alex, 20);
        pass(1);
        pass(100);
        double account = plugin.power().balance(workshop);

        assertNull(plugin.orders().collect(alex, o.id));

        assertEquals(20, count(alex, Material.IRON_INGOT));
        assertEquals(account + 40, plugin.power().balance(workshop), 1e-6, "оплата поступила на счёт мастерской");
        assertTrue(plugin.orders().all().isEmpty());
        assertEquals(Journal.Action.SERVICE_DONE, plugin.journal().forPlot("w").get(0).action());
        assertTrue(plugin.warnings().forPlayer(steve.getUniqueId()).get(0).text.contains("зачислено"));
    }

    @Test
    void collectingBeforeTheOrderIsReadyIsRefused() {
        ServiceOrder o = orderSmelt(alex, 20);
        pass(1);
        assertNotNull(plugin.orders().collect(alex, o.id));
        assertEquals(1, plugin.orders().all().size());
    }

    @Test
    void anOfflineClientsResultWaitsInTheStorage() {
        ServiceOrder o = orderSmelt(alex, 10);
        pass(1);
        pass(50);
        assertEquals(ServiceOrder.State.READY, o.state);
        UUID id = alex.getUniqueId();
        alex.disconnect();

        plugin.service().release(steve, workshop);

        assertEquals(10, stored(id, Material.IRON_INGOT), "оффлайн-клиенту результат ждёт в хранилище");
        assertTrue(plugin.orders().all().isEmpty());
    }

    @Test
    void anOfflineClientsRefundsAreStoredToo() {
        orderSmelt(mia, 6);
        UUID id = mia.getUniqueId();
        double wallet = economy.getBalance(mia);
        mia.disconnect();

        plugin.service().release(steve, workshop);

        assertEquals(6, stored(id, Material.IRON_ORE));
        assertEquals(wallet + 12, economy.getBalance(mia.getUniqueId() == id ? server.getOfflinePlayer(id) : mia), 1e-9);
    }

    @Test
    void nobodyElseCanCollectOrCancelAnOrder() {
        ServiceOrder o = orderSmelt(alex, 2);
        pass(1);
        pass(20);
        assertNotNull(plugin.orders().collect(mia, o.id));
        assertNotNull(plugin.orders().cancel(mia, o.id));
        assertEquals(1, plugin.orders().all().size());
    }

    // ------------------------------------------------------------ отмена

    @Test
    void cancellingAPendingOrderReturnsEverything() {
        orderSmelt(alex, 10);                           // активный заказ впереди
        ServiceOrder second = orderSmelt(mia, 7);
        double wallet = economy.getBalance(mia);
        pass(1);

        assertNull(plugin.orders().cancel(mia, second.id));

        assertEquals(wallet + 14, economy.getBalance(mia), 1e-9);
        assertEquals(7, count(mia, Material.IRON_ORE));
        assertEquals(Journal.Action.SERVICE_REFUND, plugin.journal().forPlot("w").get(0).action());
    }

    @Test
    void anOrderInProgressCannotBeCancelledByTheClient() {
        ServiceOrder o = orderSmelt(alex, 10);
        pass(1);
        assertEquals(ServiceOrder.State.ACTIVE, o.state);
        assertNotNull(plugin.orders().cancel(alex, o.id));
        assertEquals(1, plugin.orders().all().size());
    }

    @Test
    void adminCancelNeedsAReasonAndReturnsEverythingToTheClient() {
        ServiceOrder o = orderSmelt(alex, 10);
        pass(1);
        double wallet = economy.getBalance(alex);

        assertNotNull(plugin.orders().adminCancel("Boss", o, " "));
        assertEquals(1, plugin.orders().all().size());

        assertNull(plugin.orders().adminCancel("Boss", o, "мошенничество управляющего"));
        assertEquals(wallet + 20, economy.getBalance(alex), 1e-9);
        assertEquals(10, count(alex, Material.IRON_ORE));
        assertTrue(plugin.warnings().forPlayer(alex.getUniqueId()).get(0).text.contains("мошенничество управляющего"));
    }

    @Test
    void adminCancelOfAReadyOrderGivesTheResultAndRefundsTheMoney() {
        ServiceOrder o = orderSmelt(alex, 4);
        pass(1);
        pass(20);
        double wallet = economy.getBalance(alex);
        double account = plugin.power().balance(workshop);

        assertNull(plugin.orders().adminCancel("Boss", o, "спор"));

        assertEquals(4, count(alex, Material.IRON_INGOT));
        assertEquals(wallet + 8, economy.getBalance(alex), 1e-9);
        assertEquals(account, plugin.power().balance(workshop), 1e-6, "мастерская не получила оплату");
    }

    // ------------------------------------------------------------ закрытие мастерской

    @Test
    void releasingTheWorkshopRefundsPendingOrdersAndDeliversReadyOnes() {
        ServiceOrder ready = orderSmelt(alex, 4);
        pass(1);
        pass(20);
        assertEquals(ServiceOrder.State.READY, ready.state);
        ServiceOrder waiting = orderSmelt(mia, 6);
        double miaWallet = economy.getBalance(mia);

        plugin.service().release(steve, workshop);

        assertTrue(plugin.orders().all().isEmpty());
        assertEquals(4, owned(alex, Material.IRON_INGOT), "готовый результат отдан клиенту");
        assertEquals(miaWallet + 12, economy.getBalance(mia), 1e-9, "ожидающий заказ оплачен возвратом");
        assertEquals(6, count(mia, Material.IRON_ORE) + stored(mia.getUniqueId(), Material.IRON_ORE));
        assertNotNull(waiting);
    }

    @Test
    void paymentForAReadyOrderReachesTheManagerWhenTheWorkshopCloses() {
        orderSmelt(alex, 10);                               // 20
        pass(1);
        pass(50);
        plugin.power().touch(workshop);
        double balanceBefore = workshop.account.rawBalance();
        double wallet = economy.getBalance(steve);

        plugin.service().release(steve, workshop);

        assertEquals(wallet + balanceBefore + 20, economy.getBalance(steve), 1e-3,
                "оплата за готовый заказ вернулась управляющему вместе с остатком счёта");
    }

    @Test
    void seizureAlsoClosesOrdersAndTheRevenueIsSubjectToThePenalty() {
        orderSmelt(alex, 10);
        pass(1);
        pass(50);
        plugin.power().touch(workshop);
        double total = workshop.account.rawBalance() + 20;
        double wallet = economy.getBalance(steve);

        plugin.service().evict(workshop, new PlotService.Release("Boss", "нарушение", Journal.Action.SEIZED, true,
                Plot.RestoreMode.ITEMS_ONLY));

        assertEquals(wallet + total * 0.9, economy.getBalance(steve), 1e-3);
        assertEquals(10, owned(alex, Material.IRON_INGOT));
    }

    @Test
    void closingTheWorkshopClearsItsPriceList() {
        plugin.service().release(steve, workshop);
        assertTrue(workshop.offers.isEmpty());
    }

    // ------------------------------------------------------------ не забрали вовремя

    @Test
    void unclaimedReadyOrdersGoToTheClientsStorageAndTheManagerIsPaid() {
        ServiceOrder o = orderSmelt(alex, 6);
        pass(1);
        pass(30);
        assertEquals(ServiceOrder.State.READY, o.state);
        o.readyAt = System.currentTimeMillis() - 31L * plugin.service().dayMillis();
        double account = plugin.power().balance(workshop);

        pass(1);

        assertTrue(plugin.orders().all().isEmpty());
        assertEquals(6, owned(alex, Material.IRON_INGOT));
        assertEquals(account + 12, plugin.power().balance(workshop), 0.1);
    }

    // ------------------------------------------------------------ ремонт и рецепты

    @Test
    void repairRestoresDurabilityAndOnlyForDamagedItems() {
        ItemStack whole = new ItemStack(Material.IRON_PICKAXE);
        hold(alex, whole);
        assertNotNull(plugin.orders().create(alex, workshop, "repair", false));

        ItemStack worn = new ItemStack(Material.IRON_PICKAXE);
        worn.editMeta(Damageable.class, m -> m.setDamage(200));
        hold(alex, worn);
        double wallet = economy.getBalance(alex);
        assertNull(plugin.orders().create(alex, workshop, "repair", false));
        assertEquals(wallet - 10, economy.getBalance(alex), 1e-9);

        pass(1);
        pass(5);
        ServiceOrder o = order(alex);
        assertEquals(ServiceOrder.State.READY, o.state);
        plugin.orders().collect(alex, o.id);

        ItemStack fixed = java.util.Arrays.stream(alex.getInventory().getContents())
                .filter(i -> i != null && i.getType() == Material.IRON_PICKAXE).findFirst().orElseThrow();
        assertEquals(0, ((Damageable) fixed.getItemMeta()).getDamage());
    }

    @Test
    void customRecipeConsumesWholeBatchesAndLeavesTheRest() {
        enable("recipe:compress-iron", 3.0);
        hold(alex, new ItemStack(Material.IRON_NUGGET, 20));

        assertNull(plugin.orders().create(alex, workshop, "recipe:compress-iron", false));

        ServiceOrder o = order(alex);
        assertEquals(2, o.units, "20 самородков - две партии по 9");
        assertEquals(6, o.paid, 1e-9, "цена за партию");
        assertEquals(2, count(alex, Material.IRON_NUGGET), "остаток 2 шт. остался у игрока");
        pass(1);
        pass(10);                                           // 5 с на партию / 2 печи = 2.5 с, 2 партии = 5 с
        assertEquals(ServiceOrder.State.READY, o.state);
        plugin.orders().collect(alex, o.id);
        assertEquals(2, count(alex, Material.IRON_INGOT));
    }

    @Test
    void customRecipeNeedsEnoughMaterial() {
        enable("recipe:compress-iron", 3.0);
        hold(alex, new ItemStack(Material.IRON_NUGGET, 8));
        String error = plugin.orders().create(alex, workshop, "recipe:compress-iron", false);
        assertNotNull(error);
        assertEquals(8, count(alex, Material.IRON_NUGGET));
    }

    // ------------------------------------------------------------ прайс

    @Test
    void priceIsLimitedByTheAdministrator() {
        assertNotNull(plugin.orders().configureOffer(steve, workshop, "smelt", 21.0, null));
        assertNotNull(plugin.orders().configureOffer(steve, workshop, "smelt", -1.0, null));
        assertNull(plugin.orders().configureOffer(steve, workshop, "smelt", 20.0, null));
        plugin.getConfig().set("services.smelt.max-price", 5.0);
        assertNotNull(plugin.orders().configureOffer(steve, workshop, "smelt", 6.0, null));
    }

    @Test
    void onlyTheManagerSetsThePrices() {
        assertNotNull(plugin.orders().configureOffer(alex, workshop, "smelt", 1.0, null));
        assertEquals(2.0, workshop.offers.get("smelt").price, 1e-9);
    }

    @Test
    void priceChangeAppliesToNewOrdersOnly() {
        ServiceOrder old = orderSmelt(alex, 10);
        plugin.orders().configureOffer(steve, workshop, "smelt", 5.0, null);
        ServiceOrder fresh = orderSmelt(mia, 10);
        assertEquals(20, old.paid, 1e-9);
        assertEquals(50, fresh.paid, 1e-9);
    }

    @Test
    void offersArePersistedWithThePlot() {
        plugin.plots().save();
        plugin.plots().load();
        Plot loaded = plugin.plots().get("w");
        assertTrue(loaded.offers.get("smelt").enabled);
        assertEquals(2.0, loaded.offers.get("smelt").price, 1e-9);
        assertEquals(10.0, loaded.offers.get("repair").price, 1e-9);
    }

    // ------------------------------------------------------------ сохранение

    @Test
    void ordersSurviveARestartWithTheirProgress() {
        ServiceOrder o = orderSmelt(alex, 20);
        pass(1);
        pass(24);
        plugin.orders().save();

        plugin.orders().load();

        ServiceOrder loaded = plugin.orders().find(o.shortId());
        assertNotNull(loaded);
        assertEquals(20, loaded.units);
        assertEquals(5, loaded.unitsDone);
        assertEquals(ServiceOrder.State.ACTIVE, loaded.state);
        assertEquals(alex.getUniqueId(), loaded.client);
        assertEquals(40, loaded.paid, 1e-9);
        assertEquals(1, loaded.input.size());
        assertEquals(1, loaded.output.size());
        assertEquals(20, ItemStack.deserializeBytes(loaded.output.get(0)).getAmount());
    }

    @Test
    void shortIdLookupIsStrictAboutAmbiguity() {
        ServiceOrder a = orderSmelt(alex, 1);
        assertSame(plugin.orders().find(a.shortId()), plugin.orders().find(a.id.toString()));
        assertNull(plugin.orders().find("zzzzzzzz"));
    }

    // ------------------------------------------------------------ команды и меню

    @Test
    void orderCommandWorksFromTheHand() {
        hold(alex, new ItemStack(Material.IRON_ORE, 8));
        alex.performCommand("district service order w smelt");
        assertEquals(1, plugin.orders().ofClient(alex.getUniqueId()).size());
        assertEquals(8, order(alex).units);
    }

    @Test
    void collectAndCancelCommands() {
        ServiceOrder pending = orderSmelt(alex, 10);
        alex.performCommand("district service cancel " + pending.shortId());
        assertTrue(plugin.orders().ofClient(alex.getUniqueId()).isEmpty());

        ServiceOrder o = orderSmelt(alex, 2);
        pass(1);
        pass(20);
        alex.performCommand("district service collect " + o.shortId());
        assertEquals(2, count(alex, Material.IRON_INGOT));
    }

    @Test
    void managerCommandsSetPricesAndToggleServices() {
        steve.performCommand("district service price w smelt 4.5");
        assertEquals(4.5, workshop.offers.get("smelt").price, 1e-9);
        steve.performCommand("district service toggle w smelt off");
        assertFalse(workshop.offers.get("smelt").enabled);

        alex.performCommand("district service price w smelt 1");
        assertEquals(4.5, workshop.offers.get("smelt").price, 1e-9, "клиент прайс менять не может");
    }

    @Test
    void offersCommandListsThePriceList() {
        drain(alex);
        alex.performCommand("district service offers w");
        String out = drain(alex);
        assertTrue(out.contains("smelt"), out);
        assertTrue(out.contains("repair"), out);
        assertTrue(out.contains("recipe:compress-iron"), out);
    }

    @Test
    void adminCommandsNeedThePermissionAndAReason() {
        ServiceOrder o = orderSmelt(alex, 5);
        alex.performCommand("district service admin cancel " + o.shortId() + " причина");
        assertTrue(drain(alex).contains("Недостаточно прав"));
        assertEquals(1, plugin.orders().all().size());

        boss.performCommand("district service admin cancel " + o.shortId());
        assertEquals(1, plugin.orders().all().size(), "без причины не отменяется");
        boss.performCommand("district service admin cancel " + o.shortId() + " проверка администратором");
        assertTrue(plugin.orders().all().isEmpty());
    }

    @Test
    void servicesMenuOrdersFromTheHandWithAClick() {
        hold(alex, new ItemStack(Material.IRON_ORE, 4));
        new ru.industryreborn.gui.ServicesMenu(plugin, alex, workshop, null).open();
        alex.simulateInventoryClick(11);
        assertEquals(1, plugin.orders().ofClient(alex.getUniqueId()).size());
    }

    @Test
    void myOrdersMenuCollectsAReadyOrder() {
        ServiceOrder o = orderSmelt(alex, 2);
        pass(1);
        pass(20);
        new ru.industryreborn.gui.MyOrdersMenu(plugin, alex, 0).open();
        alex.simulateInventoryClick(9);
        assertEquals(2, count(alex, Material.IRON_INGOT));
        assertNull(plugin.orders().find(o.shortId()));
    }

    @Test
    void offersMenuTogglesAServiceForTheManager() {
        assertTrue(workshop.offers.get("smelt").enabled);
        new ru.industryreborn.gui.OffersMenu(plugin, steve, workshop, null).open();
        steve.simulateInventoryClick(11);
        assertFalse(workshop.offers.get("smelt").enabled);
    }

    @Test
    void workshopMenuOffersServicesToVisitorsAndPricesToTheOwner() {
        new ru.industryreborn.gui.PlotMenu(plugin, alex, workshop, null).open();
        assertEquals(Material.FURNACE, alex.getOpenInventory().getTopInventory().getItem(20).getType());
        new ru.industryreborn.gui.PlotMenu(plugin, steve, workshop, null).open();
        assertTrue(java.util.Arrays.stream(steve.getOpenInventory().getTopInventory().getContents()).anyMatch(i -> i != null && i.getType() == Material.WRITABLE_BOOK
                && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(i.getItemMeta().displayName()).contains("Прайс")));
    }
}
