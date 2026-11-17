package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Journal;
import ru.industryreborn.support.PluginTestBase;

/** Покупка, аренда, продление, лимиты, отсрочка платежа. */
class PlotLifecycleTest extends PluginTestBase {

    private static final long DAY = 86_400_000L;

    @Test
    void buyingTransfersManagementAndPaysTreasury() {
        PlayerMock steve = player("Steve", 1000);
        Plot plot = addPlot("a", Category.SHOP, 0, 400, 20);

        assertTrue(plugin.service().buy(steve, plot));

        assertEquals(Plot.Status.OWNED, plot.status);
        assertTrue(plot.isOwner(steve.getUniqueId()));
        assertEquals(600, economy.getBalance(steve), 1e-9);
        assertEquals(400, plugin.treasury().balance(), 1e-9);
        assertEquals(Journal.Action.BOUGHT, plugin.journal().forPlayer(steve.getUniqueId()).get(0).action());
    }

    @Test
    void buyingFailsWithoutMoneyAndChangesNothing() {
        PlayerMock steve = player("Steve", 100);
        Plot plot = addPlot("a", Category.SHOP, 0, 400, 20);

        assertFalse(plugin.service().buy(steve, plot));

        assertTrue(plot.isFree());
        assertEquals(100, economy.getBalance(steve), 1e-9);
        assertEquals(0, plugin.treasury().balance(), 1e-9);
    }

    @Test
    void cannotBuyPlotThatIsNotForSale() {
        PlayerMock steve = player("Steve", 10_000);
        Plot plot = addPlot("a", Category.SHOP, 0, -1, 20);
        assertFalse(plugin.service().buy(steve, plot));
        assertTrue(plot.isFree());
    }

    @Test
    void rentMultipliesDailyPriceByDays() {
        PlayerMock steve = player("Steve", 1000);
        Plot plot = addPlot("a", Category.SHOP, 0, -1, 25);

        assertTrue(plugin.service().rent(steve, plot, 7));

        assertEquals(Plot.Status.RENTED, plot.status);
        assertEquals(825, economy.getBalance(steve), 1e-9);
        assertEquals(175, plugin.treasury().balance(), 1e-9);
        long remaining = plot.rentedUntil - System.currentTimeMillis();
        assertTrue(Math.abs(remaining - 7 * DAY) < 5_000, "осталось ~7 суток, а не " + remaining);
    }

    @Test
    void rentIsLimitedToFourteenDays() {
        PlayerMock steve = player("Steve", 100_000);
        Plot plot = addPlot("a", Category.SHOP, 0, -1, 10);

        assertFalse(plugin.service().rent(steve, plot, 15));
        assertFalse(plugin.service().rent(steve, plot, 0));
        assertTrue(plot.isFree());
        assertTrue(plugin.service().rent(steve, plot, 14));
    }

    @Test
    void extendCannotExceedFourteenDaysInTotal() {
        PlayerMock steve = player("Steve", 100_000);
        Plot plot = addPlot("a", Category.SHOP, 0, -1, 10);
        plugin.service().rent(steve, plot, 14);
        // прошло два дня: осталось 12
        plot.rentedUntil = System.currentTimeMillis() + 12 * DAY;
        double before = economy.getBalance(steve);

        assertFalse(plugin.service().extend(steve, plot, 3), "3 суток превысят 14");
        assertEquals(before, economy.getBalance(steve), 1e-9);
        assertTrue(plugin.service().extend(steve, plot, 2));
        assertEquals(before - 20, economy.getBalance(steve), 1e-9);
        long remaining = plot.rentedUntil - System.currentTimeMillis();
        assertTrue(Math.abs(remaining - 14 * DAY) < 5_000);
        assertFalse(plugin.service().extend(steve, plot, 1), "лимит исчерпан");
    }

    @Test
    void onlyTheManagerCanExtend() {
        PlayerMock steve = player("Steve", 1000);
        PlayerMock alex = player("Alex", 1000);
        Plot plot = addPlot("a", Category.SHOP, 0, -1, 10);
        plugin.service().rent(steve, plot, 3);
        assertFalse(plugin.service().extend(alex, plot, 1));
    }

    @Test
    void categoryLimitBlocksSecondWorkshop() {
        PlayerMock steve = player("Steve", 100_000);
        Plot w1 = addPlot("w1", Category.WORKSHOP, 0, 100, -1);
        Plot w2 = addPlot("w2", Category.WORKSHOP, 20, 100, -1);
        assertTrue(plugin.service().buy(steve, w1));
        assertFalse(plugin.service().buy(steve, w2), "лимит мастерских по умолчанию 1");
        assertTrue(w2.isFree());
    }

    @Test
    void totalLimitBlocksSeventhPlot() {
        PlayerMock steve = player("Steve", 1_000_000);
        plugin.getConfig().set("limits.total", 2);
        plugin.getConfig().set("limits.categories.shop", 0);
        Plot a = addPlot("a", Category.SHOP, 0, 10, -1);
        Plot b = addPlot("b", Category.SHOP, 20, 10, -1);
        Plot c = addPlot("c", Category.SHOP, 40, 10, -1);
        assertTrue(plugin.service().buy(steve, a));
        assertTrue(plugin.service().buy(steve, b));
        assertFalse(plugin.service().buy(steve, c));
    }

    @Test
    void autoRenewChargesAndExtendsAtExpiry() {
        PlayerMock steve = player("Steve", 1000);
        Plot plot = addPlot("a", Category.SHOP, 0, -1, 10);
        plugin.service().rent(steve, plot, 1);
        plugin.service().setAutoRenew(plot, true);
        plot.rentedUntil = System.currentTimeMillis() - 1000;
        double before = economy.getBalance(steve);

        plugin.service().tick();

        assertEquals(Plot.Status.RENTED, plot.status);
        assertEquals(before - 70, economy.getBalance(steve), 1e-9, "автопродление на 7 суток по 10");
        assertTrue(plot.rentedUntil > System.currentTimeMillis() + 6 * DAY);
        assertEquals(0, plot.graceUntil);
    }

    @Test
    void notEnoughMoneyStartsThreeDayGraceAndFreezes() {
        PlayerMock steve = player("Steve", 10);
        Plot plot = addPlot("a", Category.SHOP, 0, -1, 10);
        plugin.service().rent(steve, plot, 1);
        plugin.service().setAutoRenew(plot, true);
        plot.rentedUntil = System.currentTimeMillis() - 1000;

        plugin.service().tick();

        assertEquals(Plot.Status.RENTED, plot.status, "участок ещё у игрока");
        assertTrue(plot.isFrozen());
        long grace = plot.graceUntil - System.currentTimeMillis();
        assertTrue(Math.abs(grace - 3 * DAY) < 5_000, "отсрочка 3 суток");
        assertEquals(Journal.Action.GRACE_STARTED, plugin.journal().forPlayer(steve.getUniqueId()).get(0).action());
    }

    @Test
    void topUpDuringGraceUnfreezesPlot() {
        PlayerMock steve = player("Steve", 10);
        Plot plot = addPlot("a", Category.SHOP, 0, -1, 10);
        plugin.service().rent(steve, plot, 1);
        plugin.service().setAutoRenew(plot, true);
        plot.rentedUntil = System.currentTimeMillis() - 1000;
        plugin.service().tick();
        assertTrue(plot.isFrozen());

        economy.set(steve, 500);
        plugin.service().tick();

        assertFalse(plot.isFrozen());
        assertEquals(0, plot.graceUntil);
        assertTrue(plot.rentedUntil > System.currentTimeMillis() + 6 * DAY);
    }

    @Test
    void manualExtendDuringGraceUnfreezes() {
        PlayerMock steve = player("Steve", 10);
        Plot plot = addPlot("a", Category.SHOP, 0, -1, 10);
        plugin.service().rent(steve, plot, 1);
        plugin.service().setAutoRenew(plot, true);
        plot.rentedUntil = System.currentTimeMillis() - 1000;
        plugin.service().tick();
        economy.set(steve, 100);

        assertTrue(plugin.service().extend(steve, plot, 2));

        assertFalse(plot.isFrozen());
        assertTrue(plot.rentedUntil > System.currentTimeMillis() + DAY);
    }

    @Test
    void graceIsNotRepeatedlyRestartedOnEveryTick() {
        PlayerMock steve = player("Steve", 10);
        Plot plot = addPlot("a", Category.SHOP, 0, -1, 10);
        plugin.service().rent(steve, plot, 1);
        plugin.service().setAutoRenew(plot, true);
        plot.rentedUntil = System.currentTimeMillis() - 1000;
        plugin.service().tick();
        long first = plot.graceUntil;

        plugin.service().tick();
        plugin.service().tick();

        assertEquals(first, plot.graceUntil);
        assertEquals(1, plugin.journal().forPlot("a").stream()
                .filter(e -> e.action() == Journal.Action.GRACE_STARTED).count());
    }

    @Test
    void noAutoRenewMeansNoGraceAndPlotIsReleased() {
        PlayerMock steve = player("Steve", 1000);
        Plot plot = addPlot("a", Category.SHOP, 0, -1, 10);
        plugin.service().rent(steve, plot, 1);
        plot.rentedUntil = System.currentTimeMillis() - 1000;

        plugin.service().tick();

        assertTrue(plot.isFree());
        assertNull(plot.owner);
        assertFalse(plot.isFrozen());
        List<Journal.Entry> log = plugin.journal().forPlayer(steve.getUniqueId());
        assertEquals(Journal.Action.EXPIRED, log.get(0).action());
    }
}
