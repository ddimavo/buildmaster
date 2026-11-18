package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.html.GuiBackend;
import ru.industryreborn.html.HtmlMenus;
import ru.industryreborn.lot.Lot;
import ru.industryreborn.lot.LotService;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.Module;
import ru.industryreborn.service.SnapshotStore;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.util.ChunkAccess;
import ru.industryreborn.util.ChunkJob;

/** Аукцион участков: выставление, ставки, возврат, продление, выкуп, итоги, продажа игроком, голограмма и клики, HTML-страница. */
class PlotLotTest extends PluginTestBase {

    private static final class Fake implements java.lang.reflect.InvocationHandler {
        final UUID id = UUID.randomUUID();
        boolean valid = true;
        String text = "";
        Location at;

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) {
            switch (m.getName()) {
                case "isValid":
                    return valid;
                case "remove":
                    valid = false;
                    return null;
                case "getUniqueId":
                    return id;
                case "getPersistentDataContainer":
                    return java.lang.reflect.Proxy.newProxyInstance(org.bukkit.persistence.PersistentDataContainer.class.getClassLoader(),
                            new Class<?>[] {org.bukkit.persistence.PersistentDataContainer.class}, (pr, mm, aa) -> mm.getReturnType() == boolean.class ? (Object) false : null);
                case "text":
                    if (a != null && a.length == 1) {
                        text = PlainTextComponentSerializer.plainText().serialize((net.kyori.adventure.text.Component) a[0]);
                    }
                    return null;
                case "teleport":
                    at = (Location) a[0];
                    return true;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == a[0];
                default:
                    return m.getReturnType() == boolean.class ? (Object) false : null;
            }
        }
    }

    private static final class Snapshots extends SnapshotStore {
        @Override
        public boolean exists(File dir) {
            return true;
        }

        @Override
        public Set<UUID> loadOriginalEntities(File dir) {
            return Set.of();
        }

        @Override
        public boolean place(File dir, World world, int cx, int cz, Box clip) {
            return true;
        }

        @Override
        public void capture(File dir, World world, int cx, int cz, Box clip) { }

        @Override
        public void saveMeta(File dir, Box box, Set<UUID> originalEntities) { }

        @Override
        public Set<UUID> originalEntities(World world, Box box) {
            return Set.of();
        }
    }

    private static final class FakeGui implements GuiBackend {
        File dir;
        final Map<UUID, String> current = new HashMap<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public File pagesDir() {
            return dir;
        }

        @Override
        public boolean exists(String page) {
            return new File(dir, page + ".html").exists();
        }

        @Override
        public boolean open(Player player, String page) {
            current.put(player.getUniqueId(), page);
            return exists(page);
        }

        @Override
        public void close(Player player) {
            current.remove(player.getUniqueId());
        }

        @Override
        public String pageOf(Player player) {
            return current.get(player.getUniqueId());
        }
    }

    @TempDir
    File tmp;
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final List<Fake> texts = new ArrayList<>();
    private final List<Fake> zones = new ArrayList<>();
    private PlayerMock steve;
    private PlayerMock alex;
    private PlayerMock mia;
    private PlayerMock boss;
    private Plot plot;
    private LotService lots;
    private World world;

    private <T> T proxy(Class<T> type, Fake f) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, f));
    }

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
        plugin.setSnapshots(new Snapshots());
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.shop", 0);
        lots = plugin.lots();
        lots.setClock(clock::get);
        world = server.getWorld("world");
        world.loadChunk(0, 0);
        plugin.holoFactory().setSpawner((at, init) -> {
            Fake f = new Fake();
            f.at = at;
            TextDisplay d = proxy(TextDisplay.class, f);
            init.accept(d);
            texts.add(f);
            return d;
        });
        plugin.clickZones().setSpawner((at, size) -> {
            Fake f = new Fake();
            f.at = at;
            zones.add(f);
            return proxy(Entity.class, f);
        });
        steve = player("Steve", 100_000);
        alex = player("Alex", 100_000);
        mia = player("Mia", 100_000);
        boss = admin("Boss");
        for (Player p : List.of(steve, alex, mia, boss)) {
            p.teleport(new Location(world, 5, 2, 5));
        }
        plot = addPlot("p1", Category.SHOP, 0, 1000, -1);
        plot.hasHolo = true;
        plot.holoX = 5;
        plot.holoY = 4;
        plot.holoZ = 5;
    }

    @AfterEach
    void tearDown() {
        ChunkJob.setLoader(null);
        ChunkAccess.setSource(null);
    }

    private void run(Player p, String line) {
        p.performCommand("district " + line);
        server.getScheduler().performTicks(10);
    }

    private Lot start(double price, int hours, double step, double buyout) {
        assertNull(lots.start(plot, null, null, price, hours, step, buyout));
        return lots.get(plot);
    }

    private void minutes(long m) {
        clock.addAndGet(m * 60_000L);
    }

    private List<Fake> alive(List<Fake> list) {
        return list.stream().filter(f -> f.valid && (list != texts || f.text.contains("АУКЦИОН УЧАСТКА"))).toList();
    }

    // ------------------------------------------------------------ выставление

    @Test
    void adminStartsAnAuction() {
        drain(steve);
        run(boss, "lot start p1 1000 24 100 5000");
        Lot lot = lots.get("p1");
        assertNotNull(lot);
        assertEquals(1000, lot.start, 1e-9);
        assertEquals(100, lot.step, 1e-9);
        assertEquals(5000, lot.buyout, 1e-9);
        assertEquals(clock.get() + 24 * 3_600_000L, lot.endAt);
        assertEquals(1000, lot.minNext(), 1e-9);
        assertNull(lot.bidder);
        assertTrue(plugin.journal().forPlot("p1").stream().anyMatch(e -> e.action() == Journal.Action.LOT));
        assertTrue(drain(steve).contains("Новый аукцион"), "всем сообщили");
        assertTrue(lots.isLot(plot));
    }

    @Test
    void defaultStepIsPercentOfStartButNotBelowTheMinimum() {
        assertEquals(50, start(1000, 24, 0, 0).step, 1e-9);
        lots.cancel(plot, "Boss", "тест");
        assertEquals(10, start(100, 24, 0, 0).step, 1e-9, "не меньше min-step");
    }

    @Test
    void startRefusals() {
        assertNotNull(lots.start(plot, null, null, 0, 24, 0, 0));
        assertNotNull(lots.start(plot, null, null, 100, 0, 0, 0), "срок меньше минимального");
        assertNotNull(lots.start(plot, null, null, 100, 1000, 0, 0), "срок больше максимального");
        assertNotNull(lots.start(plot, null, null, 100, 24, 0, 100), "выкуп не больше старта");
        assertNotNull(lots.start(plot, null, null, 100, 24, 5, 0), "шаг меньше минимального");
        assertFalse(lots.isLot(plot));
        start(100, 24, 0, 0);
        assertNotNull(lots.start(plot, null, null, 100, 24, 0, 0), "уже выставлен");
        Plot bank = addPlot("b1", Category.BANK, 40, -1, -1);
        assertNotNull(lots.start(bank, null, null, 100, 24, 0, 0), "административные здания не продаются");
        Plot taken = addPlot("t1", Category.SHOP, 60, 100, -1);
        plugin.service().buy(steve, taken);
        assertNotNull(lots.start(taken, null, null, 100, 24, 0, 0), "занятый участок выставляет только его управляющий");
        Plot reg = addPlot("r1", Category.SHOP, 80, 100, -1);
        reg.kind = Plot.Kind.PLAYER;
        assertNotNull(lots.start(reg, null, null, 100, 24, 0, 0));
        plugin.getConfig().set("plot-auction.max-lots", 1);
        Plot another = addPlot("a1", Category.SHOP, 100, 100, -1);
        assertNotNull(lots.start(another, null, null, 100, 24, 0, 0), "лимит торгов");
        run(steve, "lot start a1 100");
        assertFalse(lots.isLot(another), "игрок не выставляет чужие участки");
    }

    // ------------------------------------------------------------ ставки

    @Test
    void bidsRefundPreviousBidderAndRespectTheStep() {
        Lot lot = start(1000, 24, 100, 5000);
        assertNull(lots.bid(steve, plot, 0));
        assertEquals(1000, lot.bid, 1e-9);
        assertEquals(99_000, economy.getBalance(steve), 1e-9, "ставка списана сразу");
        assertEquals(1100, lot.minNext(), 1e-9);
        assertNotNull(lots.bid(alex, plot, 1050), "меньше шага");
        assertEquals(100_000, economy.getBalance(alex), 1e-9);
        assertNull(lots.bid(alex, plot, 1100));
        assertEquals(100_000, economy.getBalance(steve), 1e-9, "ставку Стива вернули");
        assertEquals(98_900, economy.getBalance(alex), 1e-9);
        assertEquals(alex.getUniqueId(), lot.bidder);
        assertEquals(2, lot.bids);
        assertTrue(plugin.warnings().unread(steve.getUniqueId()) > 0, "перебитому записано уведомление");
        assertNotNull(lots.bid(alex, plot, 1500), "своя ставка уже последняя");
        // можно поставить больше минимума
        assertNull(lots.bid(mia, plot, 1337.5));
        assertEquals(1337.5, lot.bid, 1e-9);
        assertEquals(98_900 + 1100, economy.getBalance(alex), 1e-9 + 1000);
    }

    @Test
    void bidRefusals() {
        Lot lot = start(1000, 24, 100, 5000);
        economy.set(steve, 500);
        assertTrue(lots.bid(steve, plot, 0).contains("Недостаточно средств"));
        assertEquals(500, economy.getBalance(steve), 1e-9);
        assertNull(lot.bidder);
        assertTrue(lots.bid(alex, plot, 5000).contains("выкуп"), "ставка не может дойти до выкупа");
        assertTrue(lots.bid(alex, plot, 99999).contains("выкуп"));
        Plot other = addPlot("o1", Category.SHOP, 40, 100, -1);
        assertNotNull(lots.bid(alex, other, 0), "на участок без торгов");
        minutes(25 * 60);
        assertTrue(lots.bid(alex, plot, 0).contains("закончились"));
        plugin.modules().set(Module.PLOT_AUCTION, false, "test");
        assertNotNull(lots.bid(alex, plot, 0));
    }

    @Test
    void sellerCannotBidOnTheOwnLotAndLimitsApply() {
        plugin.getConfig().set("limits.total", 1);
        plugin.service().buy(mia, addPlot("m1", Category.SHOP, 40, 100, -1));
        start(1000, 24, 100, 0);
        assertTrue(lots.bid(mia, plot, 0).toLowerCase().contains("лимит") || lots.bid(mia, plot, 0).toLowerCase().contains("не больше"), "лимит участков действует и для торгов");
        assertNull(lots.bid(alex, plot, 0));
        plugin.getConfig().set("limits.total", 0);
    }

    @Test
    void lateBidExtendsTheAuction() {
        Lot lot = start(1000, 24, 100, 0);
        long end = lot.endAt;
        minutes(10);
        assertNull(lots.bid(steve, plot, 0));
        assertEquals(end, lot.endAt, "ставка не в последние минуты срок не меняет");
        clock.set(end - 60_000L);                       // за минуту до конца
        assertNull(lots.bid(alex, plot, 0));
        assertEquals(clock.get() + 120_000L, lot.endAt, "продлено на 2 минуты");
        clock.set(lot.endAt - 3 * 60_000L);
        long before = lot.endAt;
        assertNull(lots.bid(mia, plot, 0));
        assertEquals(before, lot.endAt, "не в окне - без продления");
    }

    // ------------------------------------------------------------ итоги

    @Test
    void winnerGetsThePlotAndTheTreasuryTheMoney() {
        Lot lot = start(1000, 24, 100, 0);
        lots.bid(steve, plot, 0);
        lots.bid(alex, plot, 1100);
        double treasury = plugin.treasury().balance();
        minutes(24 * 60 + 1);
        lots.tick();
        assertFalse(lots.isLot(plot));
        assertTrue(plot.isOwner(alex.getUniqueId()));
        assertEquals(Plot.Status.OWNED, plot.status);
        assertEquals("Alex", plot.ownerName);
        assertEquals(treasury + 1100, plugin.treasury().balance(), 1e-9);
        assertEquals(98_900, economy.getBalance(alex), 1e-9);
        assertEquals(100_000, economy.getBalance(steve), 1e-9);
        assertTrue(plugin.journal().forPlot("p1").stream().anyMatch(e -> e.action() == Journal.Action.BOUGHT && e.reason().contains("Аукцион")));
        assertTrue(plugin.warnings().unread(alex.getUniqueId()) > 0, "победителю записано уведомление");
        var h = lots.history().get(0);
        assertEquals("p1", h.plotId());
        assertEquals("Alex", h.winner());
        assertEquals(1100, h.price(), 1e-9);
        assertNotNull(lot);
    }

    @Test
    void noBidsLeaveThePlotFree() {
        start(1000, 1, 100, 0);
        minutes(61);
        lots.tick();
        assertFalse(lots.isLot(plot));
        assertTrue(plot.isFree());
        assertEquals("без ставок", lots.history().get(0).outcome());
    }

    @Test
    void buyoutEndsTheAuctionAtOnce() {
        Lot lot = start(1000, 24, 100, 5000);
        lots.bid(steve, plot, 0);
        double treasury = plugin.treasury().balance();
        assertNull(lots.buyout(alex, plot));
        assertFalse(lots.isLot(plot));
        assertTrue(plot.isOwner(alex.getUniqueId()));
        assertEquals(95_000, economy.getBalance(alex), 1e-9);
        assertEquals(100_000, economy.getBalance(steve), 1e-9, "ставку вернули");
        assertEquals(treasury + 5000, plugin.treasury().balance(), 1e-9);
        assertEquals("выкуп", lots.history().get(0).outcome());
        assertNotNull(lot);
        Lot noBuyout = lots.get(plot);
        assertNull(noBuyout);
    }

    @Test
    void buyoutNeedsMoneyAndAnExistingPrice() {
        start(1000, 24, 100, 0);
        assertTrue(lots.buyout(alex, plot).contains("нет цены выкупа"));
        lots.cancel(plot, "Boss", "тест");
        start(1000, 24, 100, 5000);
        economy.set(alex, 100);
        assertTrue(lots.buyout(alex, plot).contains("Недостаточно"));
        assertTrue(plot.isFree());
        assertEquals(100, economy.getBalance(alex), 1e-9);
    }

    @Test
    void cancelRefundsAndKeepsThePlotFree() {
        start(1000, 24, 100, 0);
        lots.bid(steve, plot, 0);
        run(boss, "lot cancel p1 ошибка");
        assertFalse(lots.isLot(plot));
        assertTrue(plot.isFree());
        assertEquals(100_000, economy.getBalance(steve), 1e-9);
        assertEquals("отменён", lots.history().get(0).outcome());
        start(1000, 24, 100, 0);
        run(steve, "lot cancel p1");
        assertTrue(lots.isLot(plot), "игрок отменить не может");
        run(boss, "lot end p1");
        assertFalse(lots.isLot(plot), "администратор завершает торги досрочно");
    }

    @Test
    void winnerOverTheLimitGetsTheMoneyBack() {
        start(1000, 24, 100, 0);
        lots.bid(steve, plot, 0);
        plugin.getConfig().set("limits.total", 1);
        plugin.service().buy(steve, addPlot("s2", Category.SHOP, 40, 100, -1));       // теперь у Стива уже есть участок
        double money = economy.getBalance(steve);
        minutes(24 * 60 + 1);
        lots.tick();
        assertTrue(plot.isFree(), "участок не передан");
        assertEquals(money + 1000, economy.getBalance(steve), 1e-9, "ставка возвращена");
        assertFalse(lots.isLot(plot));
        plugin.getConfig().set("limits.total", 0);
    }

    @Test
    void auctionedPlotCannotBeBoughtOrRentedAtAFixedPrice() {
        start(1000, 24, 100, 0);
        assertFalse(plugin.service().buy(mia, plot));
        assertTrue(drain(mia).contains("аукцион"));
        plot.rentPerDay = 10;
        assertFalse(plugin.service().rent(mia, plot, 1));
        assertTrue(plot.isFree());
    }

    // ------------------------------------------------------------ продажа игроком

    @Test
    void playerSellsHisPlotAtAuction() {
        Plot mine = addPlot("m1", Category.SHOP, 40, 500, -1);
        assertTrue(plugin.service().buy(steve, mine));
        mine.resetOnRelease = true;
        run(steve, "lot sell m1 800 2");
        assertTrue(drain(steve).contains("Подтвердить"));
        assertTrue(mine.isOwner(steve.getUniqueId()), "без подтверждения ничего не меняется");
        run(steve, "lot sell m1 800 2 confirm");
        assertTrue(mine.isFree(), "участок освобождён");
        Lot lot = lots.get(mine);
        assertNotNull(lot);
        assertEquals(steve.getUniqueId(), lot.seller);
        assertEquals(800, lot.start, 1e-9);
        assertTrue(mine.hasBuilding, "здание осталось и показывается в лоте");
        assertTrue(lots.bid(steve, mine, 0).contains("свой лот"));
        assertNull(lots.bid(alex, mine, 0));
        double treasury = plugin.treasury().balance();
        double before = economy.getBalance(steve);
        minutes(121);
        lots.tick();
        assertTrue(mine.isOwner(alex.getUniqueId()));
        assertEquals(before + 720, economy.getBalance(steve), 1e-9, "продавцу цена минус комиссия 10%");
        assertEquals(treasury + 80, plugin.treasury().balance(), 1e-9);
        assertTrue(plugin.warnings().unread(steve.getUniqueId()) > 0);
    }

    @Test
    void biddingWaitsUntilTheSoldPlotIsReady() {
        Plot mine = addPlot("m1", Category.SHOP, 40, 500, -1);
        plugin.service().buy(steve, mine);
        assertNull(lots.sell(steve, mine, 800, 2, 0, 0));      // без тиков сервера: участок ещё готовится
        assertTrue(mine.restorePending || mine.busy);
        assertTrue(lots.bid(alex, mine, 0).contains("готовится"));
        assertNull(lots.get(mine).bidder);
        server.getScheduler().performTicks(10);
        assertNull(lots.bid(alex, mine, 0));
    }

    @Test
    void sellRefusals() {
        Plot mine = addPlot("m1", Category.SHOP, 40, 500, -1);
        plugin.service().buy(steve, mine);
        run(alex, "lot sell m1 800 2 confirm");
        assertTrue(mine.isOwner(steve.getUniqueId()), "чужой участок продать нельзя");
        run(steve, "lot sell m1 0 2 confirm");
        run(steve, "lot sell m1 800 0 confirm");
        run(steve, "lot sell m1 800 2 5 700 confirm");           // выкуп ниже старта
        assertTrue(mine.isOwner(steve.getUniqueId()), "при неверных параметрах участок не освобождается");
        assertFalse(lots.isLot(mine));
        Plot rented = addPlot("rr", Category.SHOP, 60, -1, 10);
        plugin.service().rent(steve, rented, 1);
        run(steve, "lot sell rr 800 2 confirm");
        assertTrue(rented.isOwner(steve.getUniqueId()), "арендованный участок не продаётся");
        plugin.getConfig().set("plot-auction.player-sales", false);
        run(steve, "lot sell m1 800 2 confirm");
        assertTrue(mine.isOwner(steve.getUniqueId()));
    }

    // ------------------------------------------------------------ голограмма и клики

    @Test
    void hologramShowsBidStepAndTimeAndFollowsBids() {
        Lot lot = start(1000, 24, 100, 5000);
        lots.tick();
        assertEquals(1, alive(texts).size());
        String text = alive(texts).get(0).text;
        assertTrue(text.contains("АУКЦИОН УЧАСТКА"), text);
        assertTrue(text.contains("p1"), text);
        assertTrue(text.contains("Ставок нет"), text);
        assertTrue(text.contains("Шаг ставки"), text);
        assertTrue(text.contains("Выкуп"), text);
        assertTrue(text.contains("1 д."), "осталось времени: " + text);
        assertEquals(1, alive(zones).size());
        assertEquals(plot.holoX, alive(texts).get(0).at.getX(), 1e-9);
        assertEquals(plot.holoY + 2.2, alive(texts).get(0).at.getY(), 1e-9, "выше голограммы участка");
        lots.bid(steve, plot, 0);
        lots.tick();
        text = alive(texts).get(0).text;
        assertTrue(text.contains("Последняя ставка"), text);
        assertTrue(text.contains("Steve"), text);
        assertEquals(1, alive(texts).size(), "голограмма обновлена, не создана заново");
        assertNotNull(lot);
    }

    @Test
    void hologramAppearsOnlyNearPlayersAndDisappearsWhenFinished() {
        start(1000, 24, 100, 0);
        for (Player p : List.of(steve, alex, mia, boss)) {
            p.teleport(new Location(world, 500, 2, 500));
        }
        lots.tick();
        assertTrue(alive(texts).isEmpty(), "никого рядом - голограммы нет");
        steve.teleport(new Location(world, 5, 2, 5));
        lots.tick();
        assertEquals(1, alive(texts).size());
        minutes(24 * 60 + 1);
        lots.tick();
        assertTrue(alive(texts).isEmpty());
        assertTrue(alive(zones).isEmpty());
    }

    @Test
    void clickNeedsConfirmationThenBidsTheMinimum() {
        Lot lot = start(1000, 24, 100, 5000);
        lots.tick();
        lots.press("p1", steve, false);
        assertNull(lot.bidder, "первый клик только спрашивает");
        assertTrue(drain(steve).contains("ещё раз"));
        lots.press("p1", steve, false);
        assertEquals(steve.getUniqueId(), lot.bidder);
        assertEquals(1000, lot.bid, 1e-9);
        // подтверждение устаревает
        lots.press("p1", alex, false);
        minutes(1);
        lots.press("p1", alex, false);
        assertEquals(steve.getUniqueId(), lot.bidder, "по истечении времени нужно подтвердить заново");
        lots.press("p1", alex, false);
        assertEquals(alex.getUniqueId(), lot.bidder);
        assertEquals(1100, lot.bid, 1e-9);
        // выкуп: Shift+клик, дважды
        lots.press("p1", mia, true);
        assertTrue(lots.isLot(plot));
        lots.press("p1", mia, true);
        assertFalse(lots.isLot(plot));
        assertTrue(plot.isOwner(mia.getUniqueId()));
    }

    @Test
    void changingTheActionResetsTheConfirmationAndClickBidCanBeOff() {
        Lot lot = start(1000, 24, 100, 5000);
        lots.tick();
        lots.press("p1", steve, false);
        lots.press("p1", steve, true);          // другое действие - подтверждение не засчитывается
        assertNull(lot.bidder);
        assertTrue(lots.isLot(plot));
        lots.cancel(plot, "Boss", "тест");
        plugin.getConfig().set("plot-auction.click-bid", false);
        start(1000, 24, 100, 0);
        lots.tick();
        assertTrue(alive(zones).isEmpty(), "зоны кликов нет");
        assertFalse(alive(texts).get(0).text.contains("ПКМ"));
    }

    @Test
    void adminMovesTheLotHologram() {
        start(1000, 24, 100, 0);
        lots.tick();
        boss.teleport(new Location(world, 8, 3, 8));
        run(boss, "lot holo p1");
        lots.tick();
        var holo = alive(texts).get(0);
        assertEquals(8, holo.at.getX(), 1.0);
        assertEquals(1, alive(texts).size(), "старая убрана");
    }

    // ------------------------------------------------------------ команды, модуль, сохранение

    @Test
    void commandsListInfoAndBid() {
        run(steve, "lot");
        assertTrue(drain(steve).contains("торгов нет"));
        start(1000, 24, 100, 5000);
        run(steve, "lot list");
        String list = drain(steve);
        assertTrue(list.contains("p1") && list.contains("Магазин"), list);
        run(steve, "lot info p1");
        String info = drain(steve);
        assertTrue(info.contains("1100") || info.contains("1000"), info);
        assertTrue(info.contains("выкуп"), info);
        run(steve, "lot bid p1");
        assertEquals(steve.getUniqueId(), lots.get(plot).bidder);
        run(alex, "lot bid p1 abc");
        assertTrue(drain(alex).contains("числом"));
        run(alex, "lot bid p1 1100");
        assertEquals(alex.getUniqueId(), lots.get(plot).bidder);
        run(alex, "lot bid nope");
        assertTrue(drain(alex).contains("не найден"));
        run(mia, "lot buyout p1");
        assertTrue(plot.isOwner(mia.getUniqueId()));
    }

    @Test
    void disabledFunctionBlocksEveryoneAndHidesHolograms() {
        start(1000, 24, 100, 0);
        lots.tick();
        assertEquals(1, alive(texts).size());
        plugin.modules().set(Module.PLOT_AUCTION, false, "test");
        lots.tick();
        assertTrue(alive(texts).isEmpty());
        drain(boss);
        run(boss, "lot start p1 100");
        assertTrue(drain(boss).toLowerCase().contains("отключ"));
        run(steve, "lot bid p1");
        assertTrue(drain(steve).toLowerCase().contains("отключ"));
        assertNull(lots.get(plot).bidder);
    }

    @Test
    void lotsSurviveARestart() {
        Lot lot = start(1000, 24, 100, 5000);
        lots.bid(steve, plot, 0);
        lots.bid(alex, plot, 1100);
        minutes(24 * 60 + 1);
        lots.tick();
        start2();
        lots.save();
        LotService reloaded = new LotService(plugin);
        Lot back = reloaded.get("p1x");
        assertNotNull(back);
        assertEquals(500, back.start, 1e-9);
        assertEquals(50, back.step, 1e-9);
        assertEquals(1500, back.buyout, 1e-9);
        assertEquals(steve.getUniqueId(), back.bidder);
        assertEquals("Steve", back.bidderName);
        assertEquals(600, back.bid, 1e-9);
        assertEquals(1, back.bids);
        assertEquals(650, back.minNext(), 1e-9);
        assertEquals(1, reloaded.history().size());
        assertEquals("Alex", reloaded.history().get(0).winner());
        assertNotNull(lot);
    }

    private void start2() {
        Plot p2 = addPlot("p1x", Category.SHOP, 40, 100, -1);
        assertNull(lots.start(p2, null, null, 500, 24, 50, 1500));
        assertNull(lots.bid(steve, p2, 600));
    }

    // ------------------------------------------------------------ HTML

    @Test
    void htmlPageListsLotsWithBidButtons() throws Exception {
        FakeGui gui = new FakeGui();
        gui.dir = tmp;
        plugin.htmlMenus().setBackend(gui);
        start(1000, 24, 100, 5000);
        lots.bid(steve, plot, 0);
        assertTrue(plugin.htmlMenus().open(alex, HtmlMenus.Kind.LOTS));
        String id = alex.getUniqueId().toString().replace("-", "");
        String html = Files.readString(new File(tmp, "industryreborn/lots-" + id + ".html").toPath());
        assertTrue(html.contains("№ p1"), html);
        assertTrue(html.contains("Steve"), html);
        assertTrue(html.contains("district lot bid p1 1100.00"), html);
        assertTrue(html.contains("district lot bid p1 1300.00"), "через 2 шага");
        assertTrue(html.contains("district lot buyout p1"), html);
        assertFalse(html.contains("это вы"));
        assertTrue(plugin.htmlMenus().open(steve, HtmlMenus.Kind.LOTS));
        String mine = Files.readString(new File(tmp, "industryreborn/lots-" + steve.getUniqueId().toString().replace("-", "") + ".html").toPath());
        assertTrue(mine.contains("это вы"), "лидер видит, что ставка его");
        assertFalse(mine.contains("district lot bid p1"), mine);
        // главная страница ведёт на торги
        assertTrue(plugin.htmlMenus().open(alex, HtmlMenus.Kind.MAIN));
        String main = Files.readString(new File(tmp, "industryreborn/main-" + id + ".html").toPath());
        assertTrue(main.contains("href=\"lots-" + id + ".html\""), main);
        assertTrue(main.contains("Торгов сейчас: 1"), main);
        // итоги показываются в истории
        minutes(24 * 60 + 1);
        lots.tick();
        assertTrue(plugin.htmlMenus().open(alex, HtmlMenus.Kind.LOTS));
        html = Files.readString(new File(tmp, "industryreborn/lots-" + id + ".html").toPath());
        assertTrue(html.contains("Недавние торги") && html.contains("Steve"), html);
        assertTrue(html.contains("Сейчас торгов нет"), html);
    }

    @Test
    void htmlPageWhenDisabled() throws Exception {
        FakeGui gui = new FakeGui();
        gui.dir = tmp;
        plugin.htmlMenus().setBackend(gui);
        plugin.modules().set(Module.PLOT_AUCTION, false, "test");
        assertTrue(plugin.htmlMenus().open(alex, HtmlMenus.Kind.LOTS));
        String html = Files.readString(new File(tmp, "industryreborn/lots-" + alex.getUniqueId().toString().replace("-", "") + ".html").toPath());
        assertTrue(html.contains("отключена"), html);
    }
}
