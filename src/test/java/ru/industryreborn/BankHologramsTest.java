package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.BankBoardText;
import ru.industryreborn.support.PluginTestBase;

/** Голограммы банка: доска налогов, комиссий и казны и отдельная доска последних операций. */
class BankHologramsTest extends PluginTestBase {

    private PlayerMock boss;
    private PlayerMock steve;
    private Plot bank;
    private final java.util.List<Location> spawned = new java.util.ArrayList<>();
    private final java.util.List<net.kyori.adventure.text.Component> texts = new java.util.ArrayList<>();

    private org.bukkit.entity.TextDisplay fake(Location at, java.util.function.Consumer<org.bukkit.entity.TextDisplay> init) {
        int idx = spawned.size();
        spawned.add(at);
        texts.add(null);
        boolean[] valid = {true};
        org.bukkit.entity.TextDisplay d = (org.bukkit.entity.TextDisplay) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.entity.TextDisplay.class.getClassLoader(), new Class<?>[] {org.bukkit.entity.TextDisplay.class},
                (proxy, m, a) -> {
                    switch (m.getName()) {
                        case "isValid":
                            return valid[0];
                        case "remove":
                            valid[0] = false;
                            return null;
                        case "text":
                            if (a != null && a.length == 1) {
                                texts.set(idx, (net.kyori.adventure.text.Component) a[0]);
                            }
                            return texts.get(idx);
                        case "teleport":
                            spawned.set(idx, (Location) a[0]);
                            return true;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == a[0];
                        default:
                            return m.getReturnType() == boolean.class ? (Object) false : null;
                    }
                });
        init.accept(d);
        return d;
    }

    private static String plain(List<String> lines) {
        return String.join("\n", lines).replaceAll("<[^>]+>", "");
    }

    @BeforeEach
    void setUp() {
        plugin.bankHolograms().setSpawner(this::fake);
        server.getWorld("world").loadChunk(0, 0);
        boss = admin("Boss");
        steve = player("Steve", 10_000);
        bank = addPlot("bank", Category.BANK, 0, -1, -1);
        bank.hasHolo = true;
        bank.holoX = 3;
        bank.holoY = 5;
        bank.holoZ = 3;
        boss.teleport(new Location(server.getWorld("world"), 3, 1, 3));
    }

    @Test
    void boardListsTaxesCommissionsAndTheTreasury() {
        plugin.getConfig().set("bank.rent-tax-percent", 7);
        plugin.getConfig().set("goods.sales-tax-percent", 3);
        plugin.getConfig().set("bank.deposit-commission-percent", 2);
        plugin.getConfig().set("bank.withdraw-commission-percent", 1.5);
        plugin.getConfig().set("bank.property-tax.shop", 250);
        plugin.treasury().deposit(1234, "тест");
        String t = plain(BankBoardText.board(plugin, bank));
        assertTrue(t.contains("Налог на аренду: 7%"), t);
        assertTrue(t.contains("Налог с продаж: 3%"), t);
        assertTrue(t.contains("Пополнение счёта: 2%"), t);
        assertTrue(t.contains("Снятие со счёта: 1.5%"), t);
        assertTrue(t.contains("Магазин") && t.contains("250"), t);
        assertTrue(t.contains("Баланс") && t.contains("1234"), t);
    }

    @Test
    void logShowsRecentOperationsNewestFirstAndRespectsTheLimit() {
        plugin.getConfig().set("holograms.bank-log-lines", 3);
        assertTrue(plain(BankBoardText.log(plugin, bank)).contains("Операций ещё не было"));
        plugin.treasury().deposit(100, "первая");
        plugin.treasury().deposit(200, "вторая");
        plugin.treasury().deposit(300, "третья");
        plugin.treasury().withdraw(50, "Boss", "четвёртая");
        List<String> lines = BankBoardText.log(plugin, bank);
        assertEquals(1 + 3, lines.size(), "заголовок и три операции");
        String t = plain(lines);
        assertTrue(t.indexOf("четвёртая") < t.indexOf("третья"), "новые сверху");
        assertFalse(t.contains("первая"));
        assertTrue(lines.get(1).contains("-"), "расход со знаком минус");
        assertTrue(lines.get(2).contains("+"));
    }

    @Test
    void longReasonsAreShortened() {
        plugin.treasury().deposit(10, "х".repeat(200));
        String t = plain(BankBoardText.log(plugin, bank));
        assertFalse(t.contains("х".repeat(60)));
        assertTrue(t.contains("…"));
    }

    @Test
    void twoSeparateDisplaysAppearNearThePlayerAndOnlyForBanks() {
        plugin.bankHolograms().tick();
        assertEquals(2, plugin.bankHolograms().spawnedCount());
        Plot shop = addPlot("s", Category.SHOP, 30, -1, -1);
        shop.hasHolo = true;
        plugin.bankHolograms().tick();
        assertEquals(2, plugin.bankHolograms().spawnedCount());
        boss.teleport(new Location(server.getWorld("world"), 500, 1, 500));
        steve.teleport(new Location(server.getWorld("world"), 500, 1, 500));
        plugin.bankHolograms().tick();
        assertEquals(0, plugin.bankHolograms().spawnedCount(), "никого рядом - голограммы убраны");
    }

    @Test
    void boardAndLogHaveSeparatePositionsThatMoveIndependently() {
        double[] board = plugin.bankHolograms().boardAnchor(bank, 10);
        double[] log = plugin.bankHolograms().logAnchor(bank, 10, 5);
        assertTrue(log[1] < board[1], "доска операций под доской налогов");
        bank.hasHolo = false;     // общей голограммы нет (в MockBukkit TextDisplay не реализован)
        assertNull(plugin.bankHolograms().boardAnchor(bank, 10));
        boss.teleport(new Location(server.getWorld("world"), 4, 1, 4));
        boss.performCommand("district hologram services bank");
        assertNotNull(bank.servicesPos);
        assertNull(bank.logPos);
        boss.teleport(new Location(server.getWorld("world"), 6, 1, 6));
        boss.performCommand("district hologram log bank");
        assertNotNull(bank.logPos);
        assertEquals(6, plugin.bankHolograms().logAnchor(bank, 10, 5)[0], 1.0);
        assertEquals(bank.servicesPos[0], plugin.bankHolograms().boardAnchor(bank, 10)[0], 1e-9);
    }

    @Test
    void positionsSurviveARestart() {
        bank.servicesPos = new double[] {1, 2, 3};
        bank.logPos = new double[] {4, 5, 6};
        plugin.plots().save();
        plugin.plots().load();
        Plot loaded = plugin.plots().get("bank");
        assertArrayEquals(new double[] {4, 5, 6}, loaded.logPos);
        assertArrayEquals(new double[] {1, 2, 3}, loaded.servicesPos);
    }

    @Test
    void disabledInConfigNothingIsShown() {
        plugin.getConfig().set("holograms.bank", false);
        plugin.bankHolograms().tick();
        assertEquals(0, plugin.bankHolograms().spawnedCount());
    }
}
