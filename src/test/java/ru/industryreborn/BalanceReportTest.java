package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.service.BalanceReports;
import ru.industryreborn.service.BalanceService;
import ru.industryreborn.support.PluginTestBase;

/** Отчёты об автокорректировке: запуск минимум раз в 3 суток, что изменилось и почему, файлы md/html/json, индекс для сайта. */
class BalanceReportTest extends PluginTestBase {

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private PlayerMock boss;
    private PlayerMock steve;

    @BeforeEach
    void setUp() {
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("modules.auto-balance", true);
        plugin.getConfig().set("auto-balance.faucet-per-day", 10_000);
        plugin.getConfig().set("auto-balance.reports.timezone", "UTC");
        plugin.getConfig().set("electricity.base-rate-per-day", 100);
        plugin.getConfig().set("bank.rent-tax-percent", 5);
        plugin.getConfig().set("goods.sales-tax-percent", 3);
        plugin.getConfig().set("bank.property-tax.shop", 200);
        plugin.balance().setClock(clock::get);
        boss = admin("Boss");
        steve = player("Steve", 100_000);
    }

    private BalanceReports reports() {
        return plugin.balance().reports();
    }

    private String read(String name) throws Exception {
        return Files.readString(new File(reports().directory(), name).toPath(), StandardCharsets.UTF_8);
    }

    private BalanceReports.Report lastReport() {
        return reports().load(plugin.balance().lastReportNumber());
    }

    // ------------------------------------------------------------ интервал

    @Test
    void theDefaultIntervalIsThreeDaysAndItCanNeverExceedTheMaximum() {
        assertEquals(72 * 3_600_000L, plugin.balance().intervalMillis());
        plugin.getConfig().set("auto-balance.interval-hours", 24);
        assertEquals(24 * 3_600_000L, plugin.balance().intervalMillis(), "чаще можно");
        plugin.getConfig().set("auto-balance.interval-hours", 500);
        assertEquals(72 * 3_600_000L, plugin.balance().intervalMillis(), "реже 3 суток нельзя: корректировка идёт минимум раз в 3 дня");
        plugin.getConfig().set("auto-balance.max-interval-hours", 48);
        assertEquals(48 * 3_600_000L, plugin.balance().intervalMillis(), "предел настраивается");
        plugin.getConfig().set("auto-balance.max-interval-hours", 0);
        assertEquals(3_600_000L, plugin.balance().intervalMillis(), "не меньше часа");
    }

    @Test
    void anAutomaticRunHappensAtLeastEveryThreeDaysAndEachRunLeavesAReport() {
        plugin.treasury().deposit(5000, "тест");
        plugin.balance().run(false, "система");
        assertEquals(1, plugin.balance().lastReportNumber());
        plugin.balance().minuteTick();
        assertEquals(1, plugin.balance().lastReportNumber(), "раньше срока нового отчёта нет");
        clock.addAndGet(71 * 3_600_000L);
        plugin.balance().minuteTick();
        assertEquals(1, plugin.balance().lastReportNumber());
        clock.addAndGet(2 * 3_600_000L);
        plugin.balance().minuteTick();
        assertEquals(2, plugin.balance().lastReportNumber(), "прошло больше 3 суток: запуск и новый отчёт");
        assertEquals(2, reports().list().size());
    }

    // ------------------------------------------------------------ содержимое

    @Test
    void aReportDescribesEveryChangeWithBeforeAfterPercentAndReason() throws Exception {
        plugin.treasury().deposit(5000, "тест");             // сток/приток 0.5 - индекс выше единицы, шаг ограничен 10%
        plugin.balance().run(false, "Boss");
        BalanceReports.Report r = lastReport();
        assertEquals(1, r.number());
        assertEquals(BalanceReports.Status.CHANGED, r.status());
        assertEquals("Boss", r.actor());
        assertEquals(0.5, r.snapshot().ratio(), 1e-9);
        var power = r.changes().stream().filter(c -> c.key().equals("electricity")).findFirst().orElseThrow();
        assertEquals(100, power.from(), 1e-9);
        assertEquals(110, power.to(), 1e-9);
        assertEquals(10.0, power.percent(), 1e-9);
        assertEquals(BalanceService.Limit.STEP, power.limit(), "ограничено шагом 10% за запуск");
        assertTrue(reports().cause(r).contains("повышаются"));
        assertTrue(reports().shortReason(r, power).contains("стремится к 137.89 (база 100 × индекс 1.38)"), reports().shortReason(r, power));
        String reason = reports().reason(r, power);
        assertTrue(reason.contains("0.50") && reason.contains("0.95") && reason.contains("повышается") && reason.contains("10%"), reason);
        var rent = r.changes().stream().filter(c -> c.key().equals("rent-tax")).findFirst().orElseThrow();
        assertEquals("+10.0%", BalanceReports.percent(rent));
        assertEquals("5%", BalanceReports.value(rent.from(), rent.unit()));
        assertEquals("5.5%", BalanceReports.value(rent.to(), rent.unit()));
        assertEquals("×1.25", BalanceReports.value(1.25, "×"));
        assertTrue(reports().line(rent).contains("5% → 5.5% (+10.0%)"), reports().line(rent));
    }

    @Test
    void anUnchangedRunAndAnInsufficientDataRunAlsoProduceReports() {
        // данных нет: приток 0 (нет faucet-per-day и игроков)
        plugin.getConfig().set("auto-balance.faucet-per-day", 0);
        plugin.balance().run(false, "система");
        var noData = lastReport();
        assertEquals(BalanceReports.Status.NO_DATA, noData.status());
        assertTrue(noData.changes().isEmpty());
        assertTrue(noData.note().contains("Приток денег не определён"), noData.note());
        assertFalse(reports().markdown(noData).contains("**Причина:**"), "без данных причины корректировки нет");
        assertEquals("", reports().json(noData).get("cause").getAsString());
        assertTrue(reports().markdown(noData).contains("Изменений не было."));
        // данные есть, но менять нечего: сток/приток на цели
        plugin.getConfig().set("auto-balance.faucet-per-day", 10_000);
        plugin.treasury().deposit(9500, "тест");
        clock.addAndGet(80 * 3_600_000L);
        plugin.balance().run(false, "система");
        var same = lastReport();
        assertEquals(BalanceReports.Status.UNCHANGED, same.status());
        assertFalse(same.skips().isEmpty());
        assertTrue(same.skips().stream().anyMatch(k -> k.why().contains("уже у целевого")), same.skips().toString());
        // не хватает часов онлайн для оценки притока
        plugin.getConfig().set("auto-balance.faucet-per-day", 0);
        plugin.getConfig().set("auto-balance.income-per-player-hour", 600);
        clock.addAndGet(80 * 3_600_000L);
        plugin.balance().run(false, "система");
        assertEquals(3, plugin.balance().lastReportNumber());
    }

    @Test
    void skippedParametersExplainWhy() {
        plugin.getConfig().set("auto-balance.adjust.sales-tax", false);
        plugin.getConfig().set("bank.property-tax.house", 0);
        plugin.treasury().deposit(5000, "тест");
        boss.performCommand("district power admin base 200");   // электричество задано вручную
        plugin.balance().run(false, "Boss");
        var skips = lastReport().skips();
        assertTrue(skips.stream().anyMatch(k -> k.key().equals("electricity") && k.why().contains("вручную")), skips.toString());
        assertTrue(skips.stream().anyMatch(k -> k.key().equals("sales-tax") && k.why().contains("отключена")), skips.toString());
        assertTrue(skips.stream().anyMatch(k -> k.key().equals("property-tax.house") && k.why().contains("равно нулю")), skips.toString());
    }

    @Test
    void limitsAreNamedInTheReason() {
        plugin.getConfig().set("auto-balance.limits.sales-tax-max", 3.2);          // налог с продаж 3% не уйдёт выше 3.2
        plugin.treasury().deposit(5000, "тест");
        plugin.balance().run(false, "Boss");
        var r = lastReport();
        var sales = r.changes().stream().filter(c -> c.key().equals("sales-tax")).findFirst().orElseThrow();
        assertEquals(3.2, sales.to(), 1e-9);
        assertEquals(BalanceService.Limit.MAX, sales.limit());
        assertTrue(reports().reason(r, sales).contains("верхний предел 3.2%"), reports().reason(r, sales));
    }

    // ------------------------------------------------------------ файлы

    @Test
    void filesInThreeFormatsLatestCopiesAndAnIndexAreWritten() throws Exception {
        plugin.treasury().deposit(5000, "тест");
        plugin.balance().run(false, "Boss");
        File dir = reports().directory();
        String base = "balance-0001_2023-11-14_22-13";
        for (String f : new String[] {base + ".md", base + ".html", base + ".json", "latest.md", "latest.html", "latest.json", "index.json"}) {
            assertTrue(new File(dir, f).exists(), f);
        }
        String md = read(base + ".md");
        assertTrue(md.startsWith("# Отчёт о корректировке экономики №1"), md);
        assertTrue(md.contains("| Параметр | Было | Стало | Изменение | Причина |"));
        assertTrue(md.contains("| Налог на аренду | 5% | 5.5% | +10.0% | стремится к 6.89% (база 5% × индекс 1.38); за один запуск не больше 10% базового значения |"), md);
        assertTrue(md.contains("**Причина:** В казну уходит меньше целевого: сток/приток 0.50 при цели 0.95, индекс цен 1.38 - цены и налоги повышаются."), md);
        assertTrue(md.contains("## Как это считается"));
        assertEquals(md, read("latest.md"));
        String html = read(base + ".html");
        assertTrue(html.contains("<!DOCTYPE html>") && html.contains("<th>Причина</th>") && html.contains("+10.0%") && html.contains("<b>Причина:</b> В казну уходит меньше"), html);
        JsonObject json = JsonParser.parseString(read(base + ".json")).getAsJsonObject();
        assertEquals(1, json.get("number").getAsInt());
        assertEquals("CHANGED", json.get("status").getAsString());
        assertEquals("14.11.2023 22:13", json.get("date").getAsString());
        var first = json.getAsJsonArray("changes").get(0).getAsJsonObject();
        assertTrue(first.has("from") && first.has("to") && first.has("percent") && first.has("reason") && first.has("shortReason") && first.has("key"));
        assertTrue(json.get("cause").getAsString().contains("цены и налоги повышаются"));
        JsonObject index = JsonParser.parseString(read("index.json")).getAsJsonObject();
        assertEquals(1, index.getAsJsonArray("reports").size());
        assertEquals(base + ".html", index.getAsJsonArray("reports").get(0).getAsJsonObject().getAsJsonObject("files").get("html").getAsString());
    }

    @Test
    void theIndexListsNewestFirstAndRetentionDeletesOldFiles() throws Exception {
        plugin.getConfig().set("auto-balance.reports.keep", 2);
        for (int i = 0; i < 3; i++) {
            plugin.treasury().deposit(1000, "тест");
            clock.addAndGet(80 * 3_600_000L);
            plugin.balance().run(false, "система");
        }
        var list = reports().list();
        assertEquals(2, list.size());
        assertEquals(3, list.get(0).number(), "новые первыми");
        assertEquals(2, list.get(1).number());
        File dir = reports().directory();
        assertEquals(0, dir.listFiles((d, n) -> n.startsWith("balance-0001")).length, "самый старый отчёт удалён вместе с файлами");
        assertEquals(3, dir.listFiles((d, n) -> n.startsWith("balance-0003")).length);
    }

    @Test
    void theDirectoryAndFormatsAreConfigurable() throws Exception {
        plugin.getConfig().set("auto-balance.reports.directory", "site/out");
        plugin.getConfig().set("auto-balance.reports.formats", java.util.List.of("md", "bogus", "md"));
        plugin.treasury().deposit(5000, "тест");
        plugin.balance().run(false, "Boss");
        File dir = new File(plugin.getDataFolder(), "site/out");
        assertEquals(new File(plugin.getDataFolder(), "site/out"), reports().directory());
        assertTrue(new File(dir, "latest.md").exists());
        assertFalse(new File(dir, "latest.html").exists(), "формат не выбран");
        assertFalse(new File(dir, "latest.json").exists());
        plugin.getConfig().set("auto-balance.reports.directory", new File(plugin.getDataFolder(), "abs").getPath());
        assertEquals(new File(plugin.getDataFolder(), "abs"), reports().directory(), "абсолютный путь");
        plugin.getConfig().set("auto-balance.reports.formats", java.util.List.of());
        plugin.treasury().deposit(1000, "тест");
        clock.addAndGet(80 * 3_600_000L);
        plugin.balance().run(false, "Boss");
        assertTrue(new File(plugin.getDataFolder(), "abs/latest.json").exists(), "пустой список форматов - все три");
        plugin.getConfig().set("auto-balance.reports.enabled", false);
        plugin.treasury().deposit(1000, "тест");
        clock.addAndGet(80 * 3_600_000L);
        int before = reports().list().size();
        plugin.balance().run(false, "Boss");
        assertEquals(before, reports().list().size(), "отчёты отключены: файлов нет, корректировка идёт");
        assertTrue(plugin.balance().history().get(0).endsWith("|3"), "запуск всё равно учтён в истории: " + plugin.balance().history().get(0));
    }

    @Test
    void reportNumbersSurviveARestartAndPreviewWritesNothing() {
        plugin.treasury().deposit(5000, "тест");
        plugin.balance().run(true, "Boss");
        assertEquals(0, plugin.balance().lastReportNumber(), "предпросмотр отчёта не создаёт");
        assertTrue(reports().list().isEmpty());
        plugin.balance().run(false, "Boss");
        plugin.balance().save();
        var fresh = new BalanceService(plugin);
        assertEquals(1, fresh.lastReportNumber());
        assertEquals("1", fresh.history().get(0).split("\\|")[4], "в истории указан номер отчёта");
    }

    // ------------------------------------------------------------ команды, журнал, вебхуки

    @Test
    void theReportCommandShowsListsAndLoadsReports() {
        boss.performCommand("district balance report");
        plugin.treasury().deposit(5000, "тест");
        boss.performCommand("district balance run");
        boss.performCommand("district balance report");
        boss.performCommand("district balance report latest");
        boss.performCommand("district balance report 1");
        boss.performCommand("district balance report list");
        boss.performCommand("district balance report 99");
        boss.performCommand("district balance report abc");
        boss.performCommand("district balance history");
        steve.performCommand("district balance report");
        assertEquals(1, plugin.balance().lastReportNumber());
    }

    @Test
    void theJournalAndChatCarryPercentReasonAndTheReportNumber() {
        plugin.treasury().deposit(5000, "тест");
        plugin.balance().run(false, "Boss");
        var journal = plugin.journal().forPlot("-");
        assertTrue(journal.stream().anyMatch(e -> e.reason() != null && e.reason().contains("+10.0%") && e.reason().contains("отчёт №1")
                && e.reason().contains("повышается")), journal.toString());
        String chat = String.valueOf(boss.nextMessage());
        StringBuilder all = new StringBuilder(chat);
        String m;
        while ((m = boss.nextMessage()) != null) {
            all.append(m);
        }
        assertTrue(all.toString().contains("+10.0%") && all.toString().contains("Отчёт №1"), all.toString());
    }

    @Test
    void anOldReportWithoutAJsonFileCannotBeLoaded() throws Exception {
        plugin.treasury().deposit(5000, "тест");
        plugin.balance().run(false, "Boss");
        for (File f : reports().directory().listFiles((d, n) -> n.endsWith(".json") && !n.equals("index.json"))) {
            assertTrue(f.delete());
        }
        assertNull(reports().load(1));
        assertNull(reports().load(42));
    }

    private String messages(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m.replaceAll("§.", "")).append('\n');
        }
        return sb.toString();
    }

    @Test
    void aFallingIndexIsDescribedAsDecreasesAndShowsTheLowerLimit() throws Exception {
        plugin.treasury().deposit(20_000, "тест");              // сток/приток 2.0: индекс падает до нижнего предела 0.7
        plugin.getConfig().set("auto-balance.limits.sales-tax-min", 2.95);
        plugin.balance().run(false, "Boss");
        var r = lastReport();
        assertTrue(reports().cause(r).contains("снижаются") && reports().cause(r).contains("больше целевого"), reports().cause(r));
        var sales = r.changes().stream().filter(c -> c.key().equals("sales-tax")).findFirst().orElseThrow();
        assertEquals(2.95, sales.to(), 1e-9);
        assertEquals(BalanceService.Limit.MIN, sales.limit());
        assertEquals(2.95, sales.bound(), 1e-9);
        assertTrue(sales.percent() < 0);
        assertEquals("-1.7%", BalanceReports.percent(sales));
        assertTrue(reports().shortReason(r, sales).contains("нижний предел 2.95%"), reports().shortReason(r, sales));
        assertTrue(reports().reason(r, sales).contains("значение снижается") && reports().reason(r, sales).contains("достигнут нижний предел 2.95%"), reports().reason(r, sales));
        String html = reports().htmlPage(r);
        assertTrue(html.contains("class=\"down\""), "снижение выделено классом down");
        assertFalse(html.contains("class=\"up\""), "повышений нет");
        plugin.treasury().deposit(1, "x");
    }

    @Test
    void anUpwardChangeIsMarkedUpInTheHtmlPage() {
        plugin.treasury().deposit(5000, "тест");
        plugin.balance().run(false, "Boss");
        String html = reports().htmlPage(lastReport());
        assertTrue(html.contains("class=\"up\""));
        assertFalse(html.contains("class=\"down\""));
    }

    @Test
    void whenNothingIsSkippedTheReportHasNoUnchangedSection() {
        for (var c : ru.industryreborn.model.Category.values()) {
            if (!c.isAdminOnly() && !c.isRentOnly()) {
                plugin.getConfig().set("bank.property-tax." + c.key(), 100);
            }
        }
        plugin.treasury().deposit(5000, "тест");
        plugin.balance().run(false, "Boss");
        var r = lastReport();
        assertTrue(r.skips().isEmpty(), r.skips().toString());
        assertFalse(reports().markdown(r).contains("## Без изменений"));
        assertFalse(reports().htmlPage(r).contains("<h2>Без изменений</h2>"));
    }

    @Test
    void writingTheSameReportTwiceKeepsASingleIndexEntry() {
        plugin.treasury().deposit(5000, "тест");
        plugin.balance().run(false, "Boss");
        reports().write(lastReport());
        reports().write(lastReport());
        assertEquals(1, reports().list().size(), "повторная запись того же номера заменяет запись индекса");
    }

    @Test
    void theReportListCommandPrintsTheListAndTheSiteHint() {
        plugin.treasury().deposit(5000, "тест");
        boss.performCommand("district balance run");
        messages(boss);
        boss.performCommand("district balance report list");
        String out = messages(boss);
        assertTrue(out.contains("№1") && out.contains("Изменения применены") && out.contains("Файлы для сайта") && out.contains("latest.md"), out);
        boss.performCommand("district balance report 1");
        String one = messages(boss);
        assertTrue(one.contains("Отчёт №1") && one.contains("+10.0%") && one.contains("Налог на аренду"), one);
        boss.performCommand("district balance report 99");
        assertTrue(messages(boss).contains("не найден"));
        boss.performCommand("district balance report abc");
        assertTrue(messages(boss).contains("Использование"));
    }
}
