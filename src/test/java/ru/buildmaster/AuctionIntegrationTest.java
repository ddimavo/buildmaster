package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import fr.maxlego08.zauctionhouse.api.AuctionManager;
import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import fr.maxlego08.zauctionhouse.api.category.CategoryManager;
import fr.maxlego08.zauctionhouse.api.cluster.AuctionClusterBridge;
import fr.maxlego08.zauctionhouse.api.economy.AuctionEconomy;
import fr.maxlego08.zauctionhouse.api.economy.EconomyManager;
import fr.maxlego08.zauctionhouse.api.event.events.AuctionLoadEconomyEvent;
import fr.maxlego08.zauctionhouse.api.item.Item;
import fr.maxlego08.zauctionhouse.api.item.ItemType;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import fr.maxlego08.zauctionhouse.api.item.items.AuctionItem;
import fr.maxlego08.zauctionhouse.api.storage.StorageManager;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import ru.buildmaster.auction.AuctionPort;
import ru.buildmaster.auction.AuctionPricing;
import ru.buildmaster.auction.TreasuryAuction;
import ru.buildmaster.auction.ZahBridge;
import ru.buildmaster.model.StorageEntry;
import ru.buildmaster.service.Journal;
import ru.buildmaster.support.PluginTestBase;

/** Общий аукцион казны: очередь вещей, цены, связь с zAuctionHouse (подменён через Proxy) и зачисление выручки в казну. */
class AuctionIntegrationTest extends PluginTestBase {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** Порт-заглушка: помнит лоты и умеет отказывать. */
    private static final class FakePort implements AuctionPort {
        boolean ready = true;
        boolean failList = false;
        int already = 0;
        final List<String> listed = new ArrayList<>();
        final List<BigDecimal> prices = new ArrayList<>();
        final List<Long> expiries = new ArrayList<>();

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public int listedByTreasury() {
            return already + listed.size();
        }

        @Override
        public CompletableFuture<Boolean> list(ItemStack item, BigDecimal price, long expiredAt) {
            if (failList) {
                return CompletableFuture.failedFuture(new IllegalStateException("db down"));
            }
            listed.add(item.getType() + "x" + item.getAmount());
            prices.add(price);
            expiries.add(expiredAt);
            return CompletableFuture.completedFuture(true);
        }
    }

    private FakePort port;

    @BeforeEach
    void setUpPort() {
        port = new FakePort();
        plugin.treasuryAuction().setPort(port);
    }

    private void queue(Material m, int amount) {
        plugin.storage().add(OWNER, "p1", List.of(new ItemStack(m, amount)), StorageEntry.State.AUCTION, "тест", "b" + m + amount);
    }

    private int stored(StorageEntry.State state) {
        return plugin.storage().countItems(OWNER, state);
    }

    // ------------------------------------------------------------ цены

    @Test
    void priceUsesTheMaterialPriceTimesAmountAndRespectsTheMinimum() {
        var cfg = plugin.getConfig().getConfigurationSection("auction");
        assertEquals(0, new BigDecimal("300.00").compareTo(AuctionPricing.price(cfg, new ItemStack(Material.DIAMOND, 3))));
        assertEquals(0, new BigDecimal("10.00").compareTo(AuctionPricing.price(cfg, new ItemStack(Material.POPPY, 1))), "общая цена");
        assertEquals(0, new BigDecimal("1.00").compareTo(AuctionPricing.price(cfg, new ItemStack(Material.DIRT, 1))), "минимум");
        cfg.set("price-multiplier", 2.0);
        assertEquals(0, new BigDecimal("600.00").compareTo(AuctionPricing.price(cfg, new ItemStack(Material.DIAMOND, 3))));
    }

    @Test
    void clampKeepsThePriceWithinTheEconomyLimits() {
        BigDecimal min = new BigDecimal("5");
        BigDecimal max = new BigDecimal("100");
        assertEquals(min, AuctionPricing.clamp(new BigDecimal("1"), min, max));
        assertEquals(max, AuctionPricing.clamp(new BigDecimal("500"), min, max));
        assertEquals(new BigDecimal("50"), AuctionPricing.clamp(new BigDecimal("50"), min, max));
        assertEquals(new BigDecimal("500"), AuctionPricing.clamp(new BigDecimal("500"), null, null));
        assertEquals(new BigDecimal("500"), AuctionPricing.clamp(new BigDecimal("500"), null, BigDecimal.ZERO), "0 - без предела");
    }

    // ------------------------------------------------------------ очередь

    @Test
    void queuedItemsAreListedAndLeaveTheStorage() {
        queue(Material.DIAMOND, 3);
        queue(Material.IRON_INGOT, 10);
        assertEquals(13, plugin.treasuryAuction().waiting());
        assertEquals(2, plugin.treasuryAuction().tick());
        server.getScheduler().performTicks(2);
        assertEquals(2, port.listed.size());
        assertTrue(port.listed.containsAll(List.of("DIAMONDx3", "IRON_INGOTx10")));
        assertEquals(0, stored(StorageEntry.State.AUCTION));
        assertEquals(0, plugin.treasuryAuction().waiting());
        assertTrue(plugin.journal().forPlot("p1").stream().anyMatch(e -> e.action() == Journal.Action.AUCTION));
    }

    @Test
    void listingDaysSetTheExpiryAndZeroMeansNone() {
        queue(Material.DIAMOND, 1);
        plugin.treasuryAuction().tick();
        assertEquals(0L, port.expiries.get(0));
        plugin.getConfig().set("auction.listing-days", 2);
        queue(Material.EMERALD, 1);
        long before = System.currentTimeMillis();
        plugin.treasuryAuction().tick();
        assertTrue(port.expiries.get(1) >= before + 2 * 86_400_000L - 1000);
    }

    @Test
    void perRunAndTotalLimitsAreRespected() {
        for (int i = 1; i <= 5; i++) {
            queue(Material.DIAMOND, i);
        }
        plugin.getConfig().set("auction.per-run", 2);
        assertEquals(2, plugin.treasuryAuction().tick());
        assertEquals(3, plugin.storage().entries(OWNER).stream().mapToInt(e -> e.stacks.size()).sum());
        plugin.getConfig().set("auction.per-run", 50);
        plugin.getConfig().set("auction.max-listings", 4);
        assertEquals(2, plugin.treasuryAuction().tick(), "в сумме не больше 4 лотов казны");
        assertEquals(4, port.listedByTreasury());
        assertEquals(0, plugin.treasuryAuction().tick());
        assertEquals(1, plugin.storage().entries(OWNER).stream().mapToInt(e -> e.stacks.size()).sum());
    }

    @Test
    void aFailedListingReturnsTheItemToTheQueue() {
        queue(Material.DIAMOND, 4);
        port.failList = true;
        assertEquals(1, plugin.treasuryAuction().tick());
        server.getScheduler().performTicks(2);
        assertEquals(4, stored(StorageEntry.State.AUCTION), "предмет не потерян");
        assertTrue(port.listed.isEmpty());
        port.failList = false;
        plugin.treasuryAuction().tick();
        server.getScheduler().performTicks(2);
        assertEquals(List.of("DIAMONDx4"), port.listed);
        assertEquals(0, stored(StorageEntry.State.AUCTION));
    }

    @Test
    void nothingHappensWhenTheAuctionIsNotReadyOrDisabled() {
        queue(Material.DIAMOND, 1);
        port.ready = false;
        assertEquals(0, plugin.treasuryAuction().tick());
        port.ready = true;
        plugin.getConfig().set("auction.enabled", false);
        assertEquals(0, plugin.treasuryAuction().tick());
        plugin.getConfig().set("auction.enabled", true);
        plugin.treasuryAuction().setPort(null);
        assertEquals(0, plugin.treasuryAuction().tick());
        assertEquals(1, stored(StorageEntry.State.AUCTION));
    }

    @Test
    void onlyAuctionEntriesAreListed() {
        plugin.storage().add(OWNER, "p1", List.of(new ItemStack(Material.GOLD_INGOT, 2)), StorageEntry.State.AVAILABLE, null, "a");
        plugin.storage().add(OWNER, "p1", List.of(new ItemStack(Material.EMERALD, 2)), StorageEntry.State.ARRESTED, null, "b");
        assertEquals(0, plugin.treasuryAuction().tick());
        assertEquals(2, stored(StorageEntry.State.AVAILABLE));
        assertEquals(2, stored(StorageEntry.State.ARRESTED));
    }

    @Test
    void anArrestedBatchGoesToTheAuctionByAdminDecision() {
        plugin.storage().add(OWNER, "p1", List.of(new ItemStack(Material.EMERALD, 2)), StorageEntry.State.ARRESTED, "нарушение", "b");
        assertEquals(1, plugin.storage().decideArrested(OWNER, StorageEntry.State.AUCTION));
        assertEquals(1, plugin.treasuryAuction().tick());
        assertEquals(List.of("EMERALDx2"), port.listed);
    }

    // ------------------------------------------------------------ просрочка в хранилище

    @Test
    void expiredStorageEntriesMoveToTheAuctionInsteadOfBeingDeleted() {
        long now = System.currentTimeMillis();
        plugin.storage().add(OWNER, "p1", List.of(new ItemStack(Material.DIAMOND, 1)), StorageEntry.State.AVAILABLE, null, "old");
        plugin.storage().entries(OWNER).get(0).state = StorageEntry.State.AVAILABLE;
        long later = now + 61L * 86_400_000L;
        assertEquals(1, plugin.storage().sweepExpired(later, 60, true));
        assertEquals(0, stored(StorageEntry.State.AVAILABLE));
        assertEquals(1, stored(StorageEntry.State.AUCTION));
    }

    @Test
    void withoutTheAuctionExpiredEntriesAreStillDeleted() {
        long now = System.currentTimeMillis();
        plugin.storage().add(OWNER, "p1", List.of(new ItemStack(Material.DIAMOND, 1)), StorageEntry.State.AVAILABLE, null, "old");
        assertEquals(1, plugin.storage().sweepExpired(now + 61L * 86_400_000L, 60));
        assertTrue(plugin.storage().entries(OWNER).isEmpty());
    }

    @Test
    void freshEntriesAndArrestedOnesAreNotMoved() {
        long now = System.currentTimeMillis();
        plugin.storage().add(OWNER, "p1", List.of(new ItemStack(Material.DIAMOND, 1)), StorageEntry.State.AVAILABLE, null, "new");
        plugin.storage().add(OWNER, "p1", List.of(new ItemStack(Material.EMERALD, 1)), StorageEntry.State.ARRESTED, null, "arr");
        assertEquals(0, plugin.storage().sweepExpired(now + 10L * 86_400_000L, 60, true));
        assertEquals(1, plugin.storage().sweepExpired(now + 400L * 86_400_000L, 60, true), "арестованное не трогается");
        assertEquals(1, stored(StorageEntry.State.ARRESTED));
    }

    // ------------------------------------------------------------ подмена zAuctionHouse

    /** Состояние поддельного аукциона. */
    private static final class Zah {
        final List<AuctionEconomy> economies = new ArrayList<>();
        final List<Item> listed = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final List<Object[]> created = new ArrayList<>();
        final List<Object[]> baseCalls = new ArrayList<>();
        boolean distributed = false;
        boolean createFails = false;
        BigDecimal min = new BigDecimal("5");
        BigDecimal max = new BigDecimal("1000");
        int nextId = 1;
        AuctionPlugin plugin;
        AuctionEconomy base;
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, java.util.function.BiFunction<java.lang.reflect.Method, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> type.getSimpleName();
                };
            }
            Object result = handler.apply(method, args);
            if (result == null && method.getReturnType() == boolean.class) {
                return false;
            }
            return result;
        });
    }

    private Zah installZah() {
        Zah z = new Zah();
        z.base = fake(AuctionEconomy.class, (m, a) -> {
            z.baseCalls.add(new Object[] {m.getName(), a});
            return switch (m.getName()) {
                case "getName" -> "vault";
                case "getDisplayName" -> "Монеты";
                case "getMinPrice" -> z.min;
                case "getMaxPrice" -> z.max;
                case "isAutoClaim" -> false;
                case "mustBeOnline" -> true;
                case "supportsOfflineDeposit" -> false;
                case "depositChecked", "withdrawChecked" -> true;
                case "get" -> CompletableFuture.completedFuture(new BigDecimal("42"));
                default -> null;
            };
        });
        EconomyManager manager = fake(EconomyManager.class, (m, a) -> switch (m.getName()) {
            case "registerEconomy" -> {
                AuctionEconomy e = (AuctionEconomy) a[0];
                if (z.economies.stream().anyMatch(x -> x.getName().equalsIgnoreCase(e.getName()))) {
                    yield false;
                }
                yield z.economies.add(e);
            }
            case "getEconomy" -> {
                String n = (String) a[0];
                if (n.equals("vault")) {
                    yield Optional.of(z.base);
                }
                yield z.economies.stream().filter(x -> x.getName().equalsIgnoreCase(n)).findFirst();
            }
            case "getDefaultEconomy" -> z.base;
            default -> null;
        });
        StorageManager storage = fake(StorageManager.class, (m, a) -> {
            switch (m.getName()) {
                case "upsertPlayer" -> {
                    z.calls.add("upsert:" + a[0] + ":" + a[1]);
                    return null;
                }
                case "createAuctionItem" -> {
                    if (z.createFails) {
                        return CompletableFuture.failedFuture(new IllegalStateException("db"));
                    }
                    z.created.add(a);
                    int id = z.nextId++;
                    AuctionItem item = fake(AuctionItem.class, (im, ia) -> switch (im.getName()) {
                        case "getId" -> id;
                        case "getSellerUniqueId" -> a[0];
                        default -> null;
                    });
                    return CompletableFuture.completedFuture(item);
                }
                default -> {
                    return null;
                }
            }
        });
        AuctionManager manager2 = fake(AuctionManager.class, (m, a) -> switch (m.getName()) {
            case "addItem" -> {
                z.listed.add((Item) a[1]);
                z.calls.add("addItem:" + a[0]);
                yield null;
            }
            case "clearPlayersCache" -> {
                z.calls.add("clearCache");
                yield null;
            }
            case "updateListedItems" -> {
                z.calls.add("updateListed:" + a[1] + ":" + a[2]);
                yield null;
            }
            case "getItems" -> z.listed.stream().filter((Predicate<Item>) a[1]).toList();
            default -> null;
        });
        CategoryManager categories = fake(CategoryManager.class, (m, a) -> {
            z.calls.add("categories");
            return null;
        });
        AuctionClusterBridge cluster = fake(AuctionClusterBridge.class, (m, a) -> switch (m.getName()) {
            case "isDistributed" -> z.distributed;
            case "notifyItemListed" -> {
                z.calls.add("notify");
                yield CompletableFuture.completedFuture(null);
            }
            default -> null;
        });
        z.plugin = fake(AuctionPlugin.class, (m, a) -> switch (m.getName()) {
            case "getEconomyManager" -> manager;
            case "getStorageManager" -> storage;
            case "getAuctionManager" -> manager2;
            case "getCategoryManager" -> categories;
            case "getAuctionClusterBridge" -> cluster;
            case "isEnabled" -> true;
            default -> null;
        });
        server.getServicesManager().register(AuctionPlugin.class, z.plugin, MockBukkit.createMockPlugin(), ServicePriority.Highest);
        return z;
    }

    private ZahBridge bridge() {
        ZahBridge bridge = new ZahBridge(plugin);
        bridge.register();
        plugin.treasuryAuction().setPort(bridge);
        return bridge;
    }

    @Test
    void theTreasuryEconomyIsRegisteredAtStartAndOnEveryEconomyReload() {
        Zah z = installZah();
        bridge();
        assertEquals(1, z.economies.size());
        assertEquals(TreasuryAuction.ECONOMY_NAME, z.economies.get(0).getName());
        z.economies.clear();                                  // /zah reload очищает экономики и снова шлёт событие
        server.getPluginManager().callEvent(new AuctionLoadEconomyEvent(z.plugin.getEconomyManager()));
        assertEquals(1, z.economies.size());
        server.getPluginManager().callEvent(new AuctionLoadEconomyEvent(z.plugin.getEconomyManager()));
        assertEquals(1, z.economies.size(), "повторная регистрация не создаёт дубликат");
    }

    @Test
    void theAuctionIsReadyOnlyOnASingleServerWithTheTreasuryEconomy() {
        Zah z = installZah();
        ZahBridge bridge = bridge();
        assertTrue(bridge.ready());
        z.distributed = true;
        assertFalse(bridge.ready(), "в кластере выплата казне не гарантирована");
        z.distributed = false;
        z.economies.clear();
        assertFalse(bridge.ready(), "экономики казны нет");
    }

    @Test
    void withoutZAuctionHouseNothingIsReady() {
        ZahBridge bridge = new ZahBridge(plugin);
        bridge.register();
        assertFalse(bridge.ready());
        assertEquals(0, bridge.listedByTreasury());
        assertFalse(bridge.list(new ItemStack(Material.DIAMOND), BigDecimal.TEN, 0).join());
    }

    @Test
    void aListingIsCreatedForTheSystemSellerAndPublishedOnTheMainThread() {
        Zah z = installZah();
        ZahBridge bridge = bridge();
        queue(Material.DIAMOND, 3);
        assertEquals(1, plugin.treasuryAuction().tick());
        assertEquals(1, z.created.size());
        Object[] args = z.created.get(0);
        assertEquals(TreasuryAuction.SYSTEM_ID, args[0]);
        assertEquals("Казна", args[1]);
        assertEquals(0, new BigDecimal("300.00").compareTo((BigDecimal) args[2]));
        assertSame(z.economies.get(0), args[5], "лот привязан к экономике казны");
        assertTrue(z.calls.contains("upsert:" + TreasuryAuction.SYSTEM_ID + ":Казна"));
        server.getScheduler().performTicks(2);
        assertEquals(List.of("categories", "addItem:LISTED", "clearCache", "updateListed:true:null", "notify"),
                z.calls.subList(1, z.calls.size()));
        assertEquals(1, bridge.listedByTreasury());
        assertEquals(0, stored(StorageEntry.State.AUCTION));
        assertTrue(plugin.journal().forPlot("p1").stream().anyMatch(e -> e.action() == Journal.Action.AUCTION));
    }

    @Test
    void thePriceIsClampedToTheEconomyLimits() {
        Zah z = installZah();
        bridge();
        z.min = new BigDecimal("50");
        queue(Material.DIRT, 1);                              // по прайсу 1
        plugin.treasuryAuction().tick();
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) z.created.get(0)[2]));
        z.max = new BigDecimal("120");
        queue(Material.NETHERITE_INGOT, 2);                   // по прайсу 1600
        plugin.treasuryAuction().tick();
        assertEquals(0, new BigDecimal("120").compareTo((BigDecimal) z.created.get(1)[2]));
    }

    @Test
    void aDatabaseFailureKeepsTheItemInTheQueue() {
        Zah z = installZah();
        bridge();
        z.createFails = true;
        queue(Material.DIAMOND, 2);
        plugin.treasuryAuction().tick();
        server.getScheduler().performTicks(2);
        assertEquals(2, stored(StorageEntry.State.AUCTION));
        assertTrue(z.listed.isEmpty());
    }

    @Test
    void theTreasuryLimitCountsOnlyItsOwnListings() {
        Zah z = installZah();
        ZahBridge bridge = bridge();
        z.listed.add(fake(Item.class, (m, a) -> m.getName().equals("getSellerUniqueId") ? UUID.randomUUID() : null));
        assertEquals(0, bridge.listedByTreasury());
        z.listed.add(fake(Item.class, (m, a) -> m.getName().equals("getSellerUniqueId") ? TreasuryAuction.SYSTEM_ID : null));
        assertEquals(1, bridge.listedByTreasury());
    }

    // ------------------------------------------------------------ выручка

    @Test
    void thePurchaseMoneyForTheSystemSellerGoesToTheTreasury() {
        Zah z = installZah();
        bridge();
        AuctionEconomy eco = z.economies.get(0);
        double before = plugin.treasury().balance();
        assertTrue(eco.depositChecked(TreasuryAuction.SYSTEM_ID, new BigDecimal("80.5"), "продажа"));
        assertEquals(before + 80.5, plugin.treasury().balance(), 1e-9);
        eco.deposit(TreasuryAuction.SYSTEM_ID, new BigDecimal("10"), "ещё");
        assertEquals(before + 90.5, plugin.treasury().balance(), 1e-9);
        assertTrue(z.baseCalls.stream().noneMatch(c -> c[0].equals("depositChecked") || c[0].equals("deposit")),
                "кошелёк несуществующего игрока не затрагивается");
        assertTrue(plugin.treasury().log().stream().anyMatch(t -> t.amount() == 80.5));
    }

    @Test
    void theSystemSellerBalanceIsTheTreasuryBalanceAndItCannotBeWithdrawn() {
        Zah z = installZah();
        bridge();
        AuctionEconomy eco = z.economies.get(0);
        plugin.treasury().deposit(300, "тест");
        assertEquals(0, new BigDecimal("300").compareTo(eco.get(TreasuryAuction.SYSTEM_ID).join()));
        assertTrue(eco.has(TreasuryAuction.SYSTEM_ID, new BigDecimal("300")).join());
        assertFalse(eco.has(TreasuryAuction.SYSTEM_ID, new BigDecimal("301")).join());
        assertFalse(eco.withdrawChecked(TreasuryAuction.SYSTEM_ID, BigDecimal.ONE, "x"));
        assertThrows(IllegalStateException.class, () -> eco.withdraw(TreasuryAuction.SYSTEM_ID, BigDecimal.ONE, "x"));
        assertEquals(300, plugin.treasury().balance(), 1e-9);
    }

    @Test
    void everythingElseIsDelegatedToTheRealEconomy() {
        Zah z = installZah();
        bridge();
        AuctionEconomy eco = z.economies.get(0);
        UUID buyer = UUID.randomUUID();
        assertEquals("Монеты", eco.getDisplayName());
        assertEquals(new BigDecimal("5"), eco.getMinPrice(ItemType.AUCTION));
        assertTrue(eco.withdrawChecked(buyer, BigDecimal.TEN, "покупка"));
        assertEquals(0, new BigDecimal("42").compareTo(eco.get(buyer).join()), "кошельки покупателей - настоящие");
        assertTrue(z.baseCalls.stream().anyMatch(c -> c[0].equals("withdrawChecked")));
        assertEquals(0, plugin.treasury().balance(), 1e-9, "казна не тронута");
    }

    @Test
    void thePaymentIsNeverDeferred() {
        Zah z = installZah();
        bridge();
        AuctionEconomy eco = z.economies.get(0);
        assertTrue(eco.isAutoClaim(), "базовая экономика откладывала бы выплату, казна - нет");
        assertFalse(eco.mustBeOnline());
        assertTrue(eco.supportsOfflineDeposit());
        assertEquals(TreasuryAuction.ECONOMY_NAME, eco.getName());
    }

    @Test
    void theSystemSellerIdIsStable() {
        assertEquals(TreasuryAuction.SYSTEM_ID, UUID.nameUUIDFromBytes("buildmaster:treasury".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(StorageType.LISTED, StorageType.valueOf("LISTED"));
    }

    @Test
    void theAuctionCommandShowsStatusAndRunsTheQueue() {
        var boss = admin("Boss");
        queue(Material.DIAMOND, 1);
        boss.performCommand("district auction status");
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = boss.nextMessage()) != null) {
            sb.append(m);
        }
        assertTrue(sb.toString().contains("в очереди"));
        boss.performCommand("district auction run");
        assertEquals(1, port.listed.size());
        var user = player("User", 0);
        user.performCommand("district auction status");
        assertEquals(1, port.listed.size());
    }
}
