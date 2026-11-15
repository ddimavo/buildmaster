package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.Journal;
import ru.buildmaster.support.PluginTestBase;

/** Команды /district: права, обязательные причины, ограничения. */
class CommandTest extends PluginTestBase {

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    @Test
    void ordinaryPlayerCannotUseAdminCommands() {
        PlayerMock steve = player("Steve", 0);
        Plot p = addPlot("a", Category.SHOP, 0, 100, -1);
        for (String cmd : new String[] {"reload", "evict a причина", "seize a причина", "reset a причина",
                "create x shop 1 1", "delete a", "setprice a 5", "treasury", "moderation", "arrest list"}) {
            steve.performCommand("district " + cmd);
            assertTrue(drain(steve).contains("Недостаточно прав"), cmd);
        }
        assertEquals(100, p.price, 1e-9, "цена не изменилась");
    }

    @Test
    void adminDecisionsRequireAReason() {
        PlayerMock steve = player("Steve", 1000);
        PlayerMock boss = admin("Boss");
        Plot p = addPlot("a", Category.SHOP, 0, 100, -1);
        plugin.service().buy(steve, p);

        for (String cmd : new String[] {"evict a", "seize a", "reset a"}) {
            boss.performCommand("district " + cmd);
            assertTrue(drain(boss).contains("причина обязательна"), cmd);
        }
        assertTrue(p.isOwner(steve.getUniqueId()), "без причины ничего не произошло");
    }

    @Test
    void seizeTakesPlotAndRecordsReasonForThePlayer() {
        PlayerMock steve = player("Steve", 1000);
        PlayerMock boss = admin("Boss");
        Plot p = addPlot("a", Category.SHOP, 0, 100, -1);
        plugin.service().buy(steve, p);

        boss.performCommand("district seize a торговля запрещёнными товарами");

        assertTrue(p.isFree());
        Journal.Entry e = plugin.journal().forPlayer(steve.getUniqueId()).get(0);
        assertEquals(Journal.Action.SEIZED, e.action());
        assertEquals("Boss", e.actor());
        assertEquals("торговля запрещёнными товарами", e.reason());
        assertTrue(drain(steve).contains("торговля запрещёнными товарами"), "игрок видит причину");
    }

    @Test
    void adminCanTakePlotEvenIfBought() {
        PlayerMock steve = player("Steve", 1000);
        PlayerMock boss = admin("Boss");
        Plot p = addPlot("a", Category.HOUSE, 0, 100, -1);
        plugin.service().buy(steve, p);
        assertEquals(Plot.Status.OWNED, p.status);

        boss.performCommand("district evict a нарушение правил");

        assertTrue(p.isFree());
        assertNull(p.owner);
    }

    @Test
    void playerCannotReleaseSomeoneElsesPlot() {
        PlayerMock steve = player("Steve", 1000);
        PlayerMock alex = player("Alex", 1000);
        Plot p = addPlot("a", Category.SHOP, 0, 100, -1);
        plugin.service().buy(steve, p);

        alex.performCommand("district release a confirm");

        assertTrue(p.isOwner(steve.getUniqueId()));
    }

    @Test
    void releaseNeedsConfirmation() {
        PlayerMock steve = player("Steve", 1000);
        Plot p = addPlot("a", Category.SHOP, 0, 100, -1);
        plugin.service().buy(steve, p);

        steve.performCommand("district release a");

        assertTrue(p.isOwner(steve.getUniqueId()));
        assertTrue(drain(steve).contains("confirm"));
    }

    @Test
    void releaseConfirmedFreesThePlotAndLogsIt() {
        PlayerMock steve = player("Steve", 1000);
        Plot p = addPlot("a", Category.SHOP, 0, 100, -1);
        plugin.service().buy(steve, p);

        steve.performCommand("district release a confirm");

        assertTrue(p.isFree());
        assertEquals(Journal.Action.RELEASED, plugin.journal().forPlayer(steve.getUniqueId()).get(0).action());
    }

    @Test
    void extendCommandRespectsFourteenDayCap() {
        PlayerMock steve = player("Steve", 100_000);
        Plot p = addPlot("a", Category.SHOP, 0, -1, 10);
        steve.performCommand("district rent 14 a");
        assertEquals(Plot.Status.RENTED, p.status);
        long until = p.rentedUntil;

        steve.performCommand("district extend 1 a");

        assertEquals(until, p.rentedUntil, "срок не изменился");
    }

    @Test
    void autoRenewAndAutoResetToggles() {
        PlayerMock steve = player("Steve", 1000);
        Plot p = addPlot("a", Category.SHOP, 0, -1, 10);
        steve.performCommand("district rent 2 a");

        steve.performCommand("district autorenew on a");
        assertTrue(p.autoRenew);
        steve.performCommand("district autorenew off a");
        assertFalse(p.autoRenew);
        steve.performCommand("district autoreset off a");
        assertFalse(p.resetOnRelease);
    }

    @Test
    void trustAndUntrust() {
        PlayerMock steve = player("Steve", 1000);
        PlayerMock alex = player("Alex", 1000);
        Plot p = addPlot("a", Category.SHOP, 0, -1, 10);
        steve.performCommand("district rent 2 a");

        steve.performCommand("district trust Alex a");
        assertTrue(p.trusted.contains(alex.getUniqueId()));
        steve.performCommand("district untrust Alex a");
        assertFalse(p.trusted.contains(alex.getUniqueId()));
    }

    @Test
    void playerNameCommandGoesToModeration() {
        PlayerMock steve = player("Steve", 1000);
        Plot p = addPlot("a", Category.SHOP, 0, -1, 10);
        steve.performCommand("district rent 2 a");

        steve.performCommand("district name a Кузница    Стива");

        assertNull(p.name);
        assertEquals("Кузница Стива", plugin.moderation().pending().get(0).text);
    }

    @Test
    void treasuryPayoutNeedsFundsAndAReason() {
        PlayerMock boss = admin("Boss");
        PlayerMock steve = player("Steve", 0);
        plugin.treasury().deposit(1000, "тест");

        boss.performCommand("district treasury pay Steve 5000 приз");
        assertEquals(0, economy.getBalance(steve), 1e-9);
        assertEquals(1000, plugin.treasury().balance(), 1e-9);

        boss.performCommand("district treasury pay Steve 400 Приз победителю конкурса");
        assertEquals(400, economy.getBalance(steve), 1e-9);
        assertEquals(600, plugin.treasury().balance(), 1e-9);
        assertEquals(Journal.Action.TREASURY, plugin.journal().forPlayer(steve.getUniqueId()).get(0).action());
    }

    @Test
    void deleteIsRefusedForOccupiedPlot() {
        PlayerMock steve = player("Steve", 1000);
        PlayerMock boss = admin("Boss");
        Plot p = addPlot("a", Category.SHOP, 0, 100, -1);
        plugin.service().buy(steve, p);

        boss.performCommand("district delete a");

        assertNotNull(plugin.plots().get("a"));
    }

    @Test
    void createRequiresValidCategoryAndSelection() {
        PlayerMock boss = admin("Boss");
        boss.performCommand("district create x castle 100 -");
        assertTrue(drain(boss).contains("Неизвестный тип"));
        boss.performCommand("district create x shop 100 -");
        assertTrue(drain(boss).contains("выделите область"));
        assertNull(plugin.plots().get("x"));
    }
}
