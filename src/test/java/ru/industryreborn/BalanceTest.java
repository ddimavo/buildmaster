package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Station;
import ru.industryreborn.service.BalanceService;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.Module;
import ru.industryreborn.service.Treasury;
import ru.industryreborn.support.PluginTestBase;

/** Автокорректировка экономики: расчёт индекса, шаг, пределы, ручные значения, журнал и уведомления. */
class BalanceTest extends PluginTestBase {

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private PlayerMock boss;
    private PlayerMock steve;

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
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.workshop", 0);
        plugin.getConfig().set("modules.auto-balance", true);
        plugin.getConfig().set("auto-balance.faucet-per-day", 10_000);
        plugin.getConfig().set("electricity.base-rate-per-day", 100);
        plugin.getConfig().set("bank.rent-tax-percent", 5);
        plugin.getConfig().set("goods.sales-tax-percent", 3);
        plugin.getConfig().set("bank.property-tax.shop", 200);
        plugin.balance().setClock(clock::get);
        boss = admin("Boss");
        steve = player("Steve", 100_000);
    }

    private void income(double amount) {
        plugin.treasury().deposit(amount, "Тест");
    }

    private double change(List<BalanceService.Change> list, String key) {
        return list.stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow().to();
    }

    @Test
    void lowSinkRaisesPricesInSmallSteps() {
        income(5000);                      // сток 5000 при притоке 10000: отношение 0.5
        var s = plugin.balance().measure();
        assertEquals(0.5, s.ratio(), 1e-9);
        assertEquals(1 + 0.8 * (0.95 - 0.5) / 0.95, s.index(), 1e-9);
        var changes = plugin.balance().run(false, "Boss");
        assertEquals(110, change(changes, "electricity"), 1e-9, "не больше 10% базы за запуск");
        assertEquals(5.5, change(changes, "rent-tax"), 1e-9);
        assertEquals(3.3, change(changes, "sales-tax"), 1e-9);
        assertEquals(220, change(changes, "property-tax.shop"), 1e-9);
        assertEquals(1.1, change(changes, "services"), 1e-9);
        assertEquals(1.1, change(changes, "auction"), 1e-9);
        assertEquals(110, plugin.power().base(), 1e-9);
        assertEquals(5.5, plugin.bank().rentTax(), 1e-9);
        assertEquals(3.3, plugin.goods().salesTaxPercent(), 1e-9);
        assertEquals(220, plugin.bank().propertyTax(Category.SHOP), 1e-9);
        assertEquals(1.1 * plugin.getConfig().getDouble("services.smelt.default-price", 1.0), plugin.catalog().get("smelt").defaultPrice(), 1e-9);
        // второй запуск: ещё шаг
        assertEquals(120, change(plugin.balance().run(false, "Boss"), "electricity"), 1e-9);
    }

    @Test
    void highSinkLowersPricesAndNeverBelowTheLimits() {
        income(20_000);                    // отношение 2.0: индекс падает до нижнего предела
        plugin.getConfig().set("auto-balance.limits.electricity-min", 95);
        var changes = plugin.balance().run(false, "Boss");
        assertEquals(95, change(changes, "electricity"), 1e-9, "нижний предел цены электричества");
        assertEquals(4.5, change(changes, "rent-tax"), 1e-9);
        assertEquals(0.7, plugin.balance().measure().index(), 1e-9);
    }

    @Test
    void indexIsClampedToTheConfiguredBand() {
        plugin.getConfig().set("auto-balance.index-max", 1.2);
        assertEquals(1.2, plugin.balance().measure().index(), 1e-9, "сток 0 при притоке 10000: индекс упирается в потолок");
    }

    @Test
    void previewChangesNothing() {
        income(5000);
        var plan = plugin.balance().run(true, "Boss");
        assertFalse(plan.isEmpty());
        assertTrue(plugin.balance().appliedValues().isEmpty());
        assertEquals(100, plugin.power().base(), 1e-9);
        assertEquals(0, plugin.balance().lastRun());
    }

    @Test
    void valuesSetByAnAdministratorAreLeftAlone() {
        income(5000);
        boss.performCommand("district power admin base 80");
        boss.performCommand("district bank admin tax rent 7");
        var changes = plugin.balance().run(false, "Boss");
        assertTrue(changes.stream().noneMatch(c -> c.key().equals("electricity")), "цена электричества задана вручную");
        assertTrue(changes.stream().noneMatch(c -> c.key().equals("rent-tax")), "налог на аренду задан вручную");
        assertEquals(80, plugin.power().base(), 1e-9);
        assertEquals(7, plugin.bank().rentTax(), 1e-9);
        assertTrue(changes.stream().anyMatch(c -> c.key().equals("sales-tax")));
    }

    @Test
    void togglesPerParameterAreRespected() {
        income(5000);
        plugin.getConfig().set("auto-balance.adjust.electricity", false);
        plugin.getConfig().set("auto-balance.adjust.property-tax", false);
        var changes = plugin.balance().run(false, "Boss");
        assertTrue(changes.stream().noneMatch(c -> c.key().equals("electricity") || c.key().startsWith("property-tax")));
        assertFalse(changes.isEmpty());
    }

    @Test
    void notEnoughDataMeansNoChanges() {
        plugin.getConfig().set("auto-balance.faucet-per-day", 0);
        income(1000);
        assertFalse(plugin.balance().measure().enough());
        assertTrue(plugin.balance().run(false, "Boss").isEmpty());
        assertEquals(100, plugin.power().base(), 1e-9);
    }

    @Test
    void theModuleSwitchStopsEverythingAndHidesAppliedValues() {
        income(5000);
        plugin.balance().run(false, "Boss");
        assertEquals(110, plugin.power().base(), 1e-9);
        plugin.modules().set(Module.AUTO_BALANCE, false, "Boss");
        assertEquals(100, plugin.power().base(), 1e-9, "применённые значения не действуют, пока функция выключена");
        assertTrue(plugin.balance().run(false, "Boss").isEmpty());
        drain(boss);
        boss.performCommand("district balance status");
        assertTrue(drain(boss).contains("отключена"));
        plugin.modules().set(Module.AUTO_BALANCE, true, "Boss");
        assertEquals(110, plugin.power().base(), 1e-9);
    }

    @Test
    void everyChangeIsJournaledAndAdminsAreNotified() {
        income(5000);
        drain(boss);
        drain(steve);
        plugin.balance().run(false, "система");
        var entries = plugin.journal().forPlot("-").stream().filter(e -> e.action() == Journal.Action.BALANCE).toList();
        assertEquals(7, entries.size(), "по записи на каждое изменение");
        assertTrue(entries.stream().anyMatch(e -> e.reason().contains("Цена электричества") && e.reason().contains("100 → 110")));
        String chat = drain(boss);
        assertTrue(chat.contains("Экономика скорректирована") && chat.contains("100 → 110"), chat);
        assertEquals("", drain(steve), "обычным игрокам не пишем");
        plugin.getConfig().set("auto-balance.notify", "all");
        plugin.balance().run(false, "система");
        assertTrue(drain(steve).contains("скорректирована"));
        plugin.getConfig().set("auto-balance.notify", "none");
        drain(boss);
        plugin.balance().run(false, "система");
        assertEquals("", drain(boss));
    }

    @Test
    void electricityTariffsOfWorkshopsFollowTheNewPrice() {
        Plot w = addPlot("w", Category.WORKSHOP, 40, -1, 10);
        assertTrue(plugin.service().rent(steve, w, 3));
        server.getWorld("world").getBlockAt(41, 1, 1).setType(Material.FURNACE);
        plugin.stations().addMachine(w, 41, 1, 1, Station.fromName("FURNACE"));
        plugin.stations().inc(w, Station.fromName("FURNACE"));
        plugin.power().recalc(w);
        double before = w.account.ratePerDay();
        income(5000);
        plugin.balance().run(false, "Boss");
        assertEquals(before * 1.1, w.account.ratePerDay(), 1e-6);
        plugin.balance().reset("Boss");
        assertEquals(before, w.account.ratePerDay(), 1e-6, "сброс возвращает тариф");
    }

    @Test
    void resetRestoresConfigValuesAndIsJournaled() {
        income(5000);
        plugin.balance().run(false, "Boss");
        assertEquals(7, plugin.balance().reset("Boss"));
        assertEquals(100, plugin.power().base(), 1e-9);
        assertEquals(3, plugin.goods().salesTaxPercent(), 1e-9);
        assertTrue(plugin.journal().forPlot("-").stream().anyMatch(e -> e.reason().startsWith("Сброс автокорректировки")));
        assertEquals(0, plugin.balance().reset("Boss"));
    }

    @Test
    void stateSurvivesARestart() {
        income(5000);
        plugin.balance().run(false, "Boss");
        BalanceService fresh = new BalanceService(plugin);
        assertEquals(110, fresh.appliedValues().get("electricity"), 1e-9);
        assertEquals(plugin.balance().lastRun(), fresh.lastRun());
        assertFalse(fresh.history().isEmpty());
    }

    @Test
    void onlineSamplesBuildThePlayerHoursAndTriggerTheFirstRun() {
        plugin.getConfig().set("auto-balance.faucet-per-day", 0);
        plugin.getConfig().set("auto-balance.income-per-player-hour", 600);
        plugin.getConfig().set("auto-balance.min-sample-hours", 3);
        income(1000);
        for (int hour = 0; hour < 4; hour++) {
            for (int minute = 0; minute < 60; minute++) {
                plugin.balance().minuteTick();           // на сервере двое: Boss и Steve
            }
            clock.addAndGet(3_600_000L);
        }
        var s = plugin.balance().measure();
        assertEquals(2 * 4, s.playerHours(), 1e-6, "2 игрока × 4 часа");
        assertEquals(8 * 600, s.faucet(), 1e-6);
        assertTrue(plugin.balance().lastRun() > 0, "первый запуск после накопления данных");
    }

    @Test
    void anAutomaticRunHappensWhenTheIntervalPasses() {
        income(5000);
        plugin.balance().run(false, "система");
        assertEquals(110, plugin.power().base(), 1e-9);
        plugin.balance().minuteTick();
        assertEquals(110, plugin.power().base(), 1e-9, "интервал ещё не прошёл");
        clock.addAndGet(25 * 3_600_000L);
        plugin.balance().minuteTick();
        assertEquals(120, plugin.power().base(), 1e-9, "интервал прошёл: ещё один шаг");
    }

    @Test
    void treasuryBucketsAreIndependentOfTheLogLength() {
        Treasury t = new Treasury();
        long now = System.currentTimeMillis();
        t.deposit(100, "a");
        t.deposit(50, "b");
        t.withdraw(30, "Boss", "c");
        assertEquals(150, t.incomeSince(now - 3_600_000L), 1e-9);
        assertEquals(30, t.expenseSince(now - 3_600_000L), 1e-9);
        assertEquals(0, t.incomeSince(now + 2 * 3_600_000L), 1e-9);
        File f = new File(plugin.getDataFolder(), "treasury-test.yml");
        try {
            t.save(f);
        } catch (java.io.IOException e) {
            fail(e);
        }
        Treasury loaded = Treasury.load(f);
        assertEquals(150, loaded.incomeSince(now - 3_600_000L), 1e-9);
        f.delete();
    }

    @Test
    void commandsStatusPreviewRunResetAndHistoryWork() {
        income(5000);
        drain(boss);
        boss.performCommand("district balance preview");
        assertTrue(drain(boss).contains("предварительный"));
        assertEquals(100, plugin.power().base(), 1e-9);
        boss.performCommand("district balance run");
        assertEquals(110, plugin.power().base(), 1e-9);
        drain(boss);
        boss.performCommand("district balance history");
        assertTrue(drain(boss).contains("индекс"));
        boss.performCommand("district balance reset");
        assertEquals(100, plugin.power().base(), 1e-9);
        steve.performCommand("district balance run");
        assertEquals(100, plugin.power().base(), 1e-9, "обычному игроку нельзя");
    }

    @Test
    void servicePricesAndAuctionScaleFollowTheIndex() {
        income(5000);
        plugin.balance().run(false, "Boss");
        double smelt = plugin.getConfig().getDouble("services.smelt.default-price", 1.0);
        assertEquals(smelt * 1.1, plugin.catalog().get("smelt").defaultPrice(), 1e-9);
        assertEquals(plugin.getConfig().getDouble("services.smelt.max-price", 20.0) * 1.1, plugin.catalog().get("smelt").maxPrice(), 1e-9);
        assertEquals(1.1, plugin.balance().applied("auction"), 1e-9);
    }
}
