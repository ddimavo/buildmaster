package ru.industryreborn.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.util.Text;

/**
 * Автокорректировка экономики (та же логика, что в таблице IndustryReborn-Balance.xlsx). Раз в интервал сравнивается приток денег
 * (заработок игроков) со стоком (приходом в казну), из отношения считается индекс цен, и цена электричества, налоги и коэффициенты
 * двигаются к значению «база × индекс» небольшим шагом. Значения, заданные администратором вручную, не трогаются.
 * Каждое изменение пишется в журнал и сообщается в чат.
 */
public final class BalanceService {

    /** Показатели за окно измерения. */
    public record Snapshot(double sink, double faucet, double playerHours, double ratio, double index, boolean enough) { }

    /** Чем ограничено изменение: ничем, шагом за один запуск, нижним или верхним пределом. */
    public enum Limit { NONE, STEP, MIN, MAX }

    /**
     * Одно изменение параметра.
     *
     * @param base    базовое значение (из config.yml)
     * @param desired к чему стремится значение при текущем индексе (после пределов)
     * @param limit   что не дало дойти до желаемого значения за один запуск
     * @param bound   значение предела, если limit = MIN или MAX
     */
    public record Change(String key, String title, double from, double to, String unit, double base, double desired, Limit limit, double bound) {

        public Change(String key, String title, double from, double to, String unit) {
            this(key, title, from, to, unit, from, to, Limit.NONE, 0);
        }

        /** Изменение в процентах от прежнего значения (0, если прежнее значение 0). */
        public double percent() {
            return from == 0 ? 0 : (to - from) / Math.abs(from) * 100.0;
        }
    }

    /** Параметр, который в этот запуск не менялся, и почему. */
    public record Skip(String key, String title, String why) { }

    /** Результат расчёта: что изменится и что осталось без изменений. */
    public record Plan(List<Change> changes, List<Skip> skips) { }

    private static final long HOUR = 3_600_000L;

    private final IndustryRebornPlugin plugin;
    private final File file;
    private final Map<String, Double> applied = new LinkedHashMap<>();
    private final TreeMap<Long, Double> playerMinutes = new TreeMap<>();
    private final List<String> history = new ArrayList<>();
    private long lastRun;
    private int reportSeq;
    private final BalanceReports reports;
    private LongSupplier clock = System::currentTimeMillis;
    private long lastSave;

    public BalanceService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "balance.yml");
        this.reports = new BalanceReports(plugin);
        load();
    }

    /** Отчёты об автокорректировке (файлы для публикации на сайте). */
    public BalanceReports reports() {
        return reports;
    }

    /** Номер последнего отчёта (0 - отчётов ещё не было). */
    public int lastReportNumber() {
        return reportSeq;
    }

    private BalanceReports.Report makeReport(String actor, BalanceReports.Status status, Snapshot s, List<Change> changes, List<Skip> skips, String note) {
        reportSeq++;
        var settings = new BalanceReports.Settings(Math.max(0.01, cfg("target-ratio", 0.95)), cfg("strength", 0.8), cfg("index-min", 0.7), cfg("index-max", 1.5),
                cfg("max-step-percent", 10), intervalMillis() / (double) HOUR);
        return new BalanceReports.Report(reportSeq, now(), actor, status, s, settings, List.copyOf(changes), List.copyOf(skips), note);
    }

    /** Только для тестов: подмена часов. */
    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    private long now() {
        return clock.getAsLong();
    }

    // ------------------------------------------------------------ настройки

    private boolean on() {
        return plugin.modules().enabled(Module.AUTO_BALANCE);
    }

    private double cfg(String key, double def) {
        return plugin.getConfig().getDouble("auto-balance." + key, def);
    }

    /** Период между запусками: не больше max-interval-hours (по умолчанию 3 суток) - корректировка идёт минимум раз в 3 дня. */
    public long intervalMillis() {
        double hours = Math.min(cfg("interval-hours", 72), Math.max(1, cfg("max-interval-hours", 72)));
        return Math.max(1, (long) (hours * HOUR));
    }

    public long lastRun() {
        return lastRun;
    }

    public long nextRun() {
        return lastRun + intervalMillis();
    }

    private boolean adjusts(String what) {
        return plugin.getConfig().getBoolean("auto-balance.adjust." + what, true);
    }

    /** Применённое автокорректировкой значение либо null (используется настоящее из конфига). */
    public Double applied(String key) {
        if (!on()) {
            return null;
        }
        return applied.get(key);
    }

    public Map<String, Double> appliedValues() {
        return Map.copyOf(applied);
    }

    public List<String> history() {
        return List.copyOf(history);
    }

    // ------------------------------------------------------------ измерения

    /** Раз в минуту: учитывает, сколько игроков сейчас на сервере (для расчёта заработка игроков). */
    public void minuteTick() {
        if (!on()) {
            return;
        }
        long hour = Math.floorDiv(now(), HOUR);
        playerMinutes.merge(hour, (double) Bukkit.getOnlinePlayers().size(), Double::sum);
        while (playerMinutes.size() > 24 * 14) {
            playerMinutes.pollFirstEntry();
        }
        if (now() - lastSave > 600_000L) {
            save();
        }
        if (lastRun > 0 && now() >= nextRun() || lastRun == 0 && sampledHours() >= cfg("min-sample-hours", 6)) {
            run(false, "система");
        }
    }

    /** Сколько часов накоплено данных об онлайне (не больше окна). */
    public double sampledHours() {
        long from = Math.floorDiv(now() - 24 * HOUR, HOUR);
        return playerMinutes.tailMap(from, true).size();
    }

    public Snapshot measure() {
        long since = now() - 24 * HOUR;
        double sink = plugin.treasury().incomeSince(since);
        double playerHours = playerMinutes.tailMap(Math.floorDiv(since, HOUR), true).values().stream().mapToDouble(Double::doubleValue).sum() / 60.0;
        double fixed = cfg("faucet-per-day", 0);
        double faucet = fixed > 0 ? fixed : playerHours * cfg("income-per-player-hour", 600);
        double ratio = faucet <= 1e-9 ? 0 : sink / faucet;
        double target = Math.max(0.01, cfg("target-ratio", 0.95));
        double index = Math.max(cfg("index-min", 0.7), Math.min(cfg("index-max", 1.5),
                1 + cfg("strength", 0.8) * (target - ratio) / target));
        boolean enough = faucet > 1e-9 && (fixed > 0 || sampledHours() >= cfg("min-sample-hours", 6));
        return new Snapshot(sink, faucet, playerHours, ratio, index, enough);
    }

    // ------------------------------------------------------------ расчёт

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double step(double base, double current, double desired) {
        double limit = Math.max(0.0001, base * cfg("max-step-percent", 10) / 100.0);
        double delta = Math.max(-limit, Math.min(limit, desired - current));
        return round(current + delta);
    }

    private void consider(Plan plan, String key, String title, String unit, double base, double min, double max, double index,
                          boolean manual, boolean allowed) {
        if (!allowed) {
            plan.skips().add(new Skip(key, title, "корректировка этого параметра отключена (auto-balance.adjust в config.yml)"));
            return;
        }
        if (manual) {
            plan.skips().add(new Skip(key, title, "значение задано администратором вручную: автокорректировка его не трогает"));
            return;
        }
        if (base <= 0) {
            plan.skips().add(new Skip(key, title, "базовое значение равно нулю: корректировать нечего"));
            return;
        }
        double current = applied.getOrDefault(key, base);
        double raw = base * index;
        double desired = Math.max(min, Math.min(max, raw));
        double stepLimit = Math.max(0.0001, base * cfg("max-step-percent", 10) / 100.0);
        double next = Math.max(min, Math.min(max, step(base, current, desired)));
        if (Math.abs(next - current) >= 0.005) {
            Limit limit = Limit.NONE;
            double bound = 0;
            if (Math.abs(desired - current) > stepLimit + 1e-9 && Math.abs(next - desired) > 0.005) {
                limit = Limit.STEP;
            } else if (raw < min - 1e-9 && Math.abs(next - min) < 0.005) {
                limit = Limit.MIN;
                bound = min;
            } else if (raw > max + 1e-9 && Math.abs(next - max) < 0.005) {
                limit = Limit.MAX;
                bound = max;
            }
            plan.changes().add(new Change(key, title, current, next, unit, base, desired, limit, bound));
        } else {
            plan.skips().add(new Skip(key, title, "изменений не требуется: значение " + fmt(current) + unit + " уже у целевого " + fmt(desired) + unit));
        }
    }

    /** Что изменится при текущих показателях (ничего не применяет). */
    public List<Change> plan(Snapshot s) {
        return planDetailed(s).changes();
    }

    /** То же, но с перечнем параметров, оставшихся без изменений, и причинами. */
    public Plan planDetailed(Snapshot s) {
        Plan out = new Plan(new ArrayList<>(), new ArrayList<>());
        double idx = s.index();
        var config = plugin.getConfig();
        consider(out, "electricity", "Цена электричества (за 1 V в сутки)", "", config.getDouble("electricity.base-rate-per-day", 100),
                cfg("limits.electricity-min", 5), cfg("limits.electricity-max", 500), idx, plugin.power().baseIsManual(), adjusts("electricity"));
        consider(out, "rent-tax", "Налог на аренду", "%", config.getDouble("bank.rent-tax-percent", 0),
                cfg("limits.rent-tax-min", 0), cfg("limits.rent-tax-max", 20), idx, plugin.bank().rentTaxIsManual(), adjusts("rent-tax"));
        consider(out, "sales-tax", "Налог с продаж", "%", config.getDouble("goods.sales-tax-percent", 0),
                cfg("limits.sales-tax-min", 0), cfg("limits.sales-tax-max", 12), idx, false, adjusts("sales-tax"));
        for (Category c : Category.values()) {
            if (c.isAdminOnly() || c.isRentOnly()) {
                continue;
            }
            double base = config.getDouble("bank.property-tax." + c.key(), 0);
            consider(out, "property-tax." + c.key(), "Налог на имущество: " + c.title(), "", base,
                    base * cfg("index-min", 0.7), base * cfg("index-max", 1.5), idx, plugin.bank().propertyTaxIsManual(c), adjusts("property-tax"));
        }
        consider(out, "services", "Цены услуг мастерских (множитель)", "×", 1.0, cfg("index-min", 0.7), cfg("index-max", 1.5), idx, false, adjusts("services"));
        consider(out, "build-price", "Цена строительства зданий за блок (множитель)", "×", 1.0, cfg("index-min", 0.7), cfg("index-max", 1.5), idx, false, adjusts("build-price"));
        consider(out, "auction", "Цены лотов аукциона казны (множитель)", "×", 1.0, cfg("index-min", 0.7), cfg("index-max", 1.5), idx, false, adjusts("auction"));
        return out;
    }

    /**
     * Запускает корректировку. apply=false - только посмотреть. Возвращает применённые (или запланированные) изменения;
     * пустой список - менять нечего или данных мало.
     */
    public List<Change> run(boolean previewOnly, String actor) {
        if (!on()) {
            return List.of();
        }
        Snapshot s = measure();
        if (!s.enough()) {
            if (!previewOnly) {
                lastRun = now();
                String note = s.faucet() <= 1e-9
                        ? "Приток денег не определён: за сутки не набралось игрочасов (или faucet-per-day равен 0), корректировать не по чему."
                        : "Накоплено " + fmt(sampledHours()) + " ч данных об онлайне, нужно не менее " + fmt(cfg("min-sample-hours", 6)) + " ч.";
                var report = makeReport(actor, BalanceReports.Status.NO_DATA, s, List.of(), List.of(), note);
                history.add(0, historyLine(s, 0, report.number()));
                trimHistory();
                save();
                reports.write(report);
                plugin.journal().add("-", null, actor, Journal.Action.BALANCE, "Автокорректировка: данных недостаточно, отчёт №" + report.number() + ". " + note);
            }
            return List.of();
        }
        Plan plan = planDetailed(s);
        List<Change> changes = plan.changes();
        if (previewOnly) {
            return changes;
        }
        lastRun = now();
        for (Change c : changes) {
            applied.put(c.key(), c.to());
        }
        var report = makeReport(actor, changes.isEmpty() ? BalanceReports.Status.UNCHANGED : BalanceReports.Status.CHANGED, s, changes, plan.skips(), "");
        history.add(0, historyLine(s, changes.size(), report.number()));
        trimHistory();
        save();
        reports.write(report);
        if (!changes.isEmpty()) {
            announce(s, changes, actor, report);
        } else {
            plugin.journal().add("-", null, actor, Journal.Action.BALANCE, "Автокорректировка: изменений не потребовалось (сток/приток "
                    + String.format(Locale.ROOT, "%.2f", s.ratio()) + ", индекс " + String.format(Locale.ROOT, "%.2f", s.index()) + "), отчёт №" + report.number());
        }
        return changes;
    }

    private String historyLine(Snapshot s, int changes, int number) {
        return now() + "|" + String.format(Locale.ROOT, "%.3f|%.3f|%d|%d", s.ratio(), s.index(), changes, number);
    }

    private void trimHistory() {
        while (history.size() > 60) {
            history.remove(history.size() - 1);
        }
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private void announce(Snapshot s, List<Change> changes, String actor, BalanceReports.Report report) {
        boolean tariffs = false;
        StringBuilder chat = new StringBuilder();
        StringBuilder webhookLines = new StringBuilder();
        for (Change c : changes) {
            String arrow = BalanceReports.value(c.from(), c.unit()) + " → " + BalanceReports.value(c.to(), c.unit()) + " (" + BalanceReports.percent(c) + ")";
            plugin.journal().add("-", null, actor, Journal.Action.BALANCE,
                    c.title() + ": " + arrow + ". " + reports.reason(report, c) + " (отчёт №" + report.number() + ")");
            chat.append("\n<gray> • <white>").append(Text.esc(c.title())).append(": <yellow>").append(Text.esc(arrow));
            webhookLines.append("\n• ").append(reports.line(c)).append(" - ").append(reports.reason(report, c));
            if (c.key().equals("electricity")) {
                tariffs = true;
            }
        }
        if (tariffs) {
            plugin.power().reloadTariffs(actor, "автокорректировка цены электричества");
        }
        plugin.webhooks().event("balance", "Экономика скорректирована",
                "Отчёт №" + report.number() + ". Сток/приток " + String.format(Locale.ROOT, "%.2f", s.ratio()) + ", индекс "
                        + String.format(Locale.ROOT, "%.2f", s.index()) + webhookLines, 0xFFCF5A);
        String head = "<gold>Экономика скорректирована <gray>(сток/приток <white>" + String.format(Locale.ROOT, "%.2f", s.ratio())
                + "</white>, цель " + fmt(cfg("target-ratio", 0.95)) + ", индекс <white>" + String.format(Locale.ROOT, "%.2f", s.index()) + "</white>):";
        String mode = plugin.getConfig().getString("auto-balance.notify", "admins").toLowerCase(Locale.ROOT);
        if (!mode.equals("none")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (mode.equals("all") || p.hasPermission("industryreborn.admin")) {
                    Text.send(p, head + chat + "\n<gray>Отчёт №" + report.number() + ": /district balance report");
                }
            }
        }
        plugin.getLogger().info("Автокорректировка экономики: " + changes.size() + " изменений, индекс " + String.format(Locale.ROOT, "%.2f", s.index()));
    }

    /** Сбрасывает всё применённое: возвращаются значения из config.yml. */
    public int reset(String actor) {
        int n = applied.size();
        boolean tariffs = applied.containsKey("electricity");
        applied.clear();
        lastRun = now();
        save();
        if (n > 0) {
            plugin.journal().add("-", null, actor, Journal.Action.BALANCE, "Сброс автокорректировки: возвращены значения из config.yml (" + n + ")");
            if (tariffs) {
                plugin.power().reloadTariffs(actor, "сброс автокорректировки");
            }
        }
        return n;
    }

    // ------------------------------------------------------------ хранение

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        lastRun = yml.getLong("last-run");
        reportSeq = yml.getInt("report-seq");
        var a = yml.getConfigurationSection("applied");
        if (a != null) {
            for (String k : a.getKeys(false)) {
                applied.put(k, a.getDouble(k));
            }
        }
        var h = yml.getConfigurationSection("hourly");
        if (h != null) {
            for (String k : h.getKeys(false)) {
                try {
                    playerMinutes.put(Long.parseLong(k), h.getDouble(k));
                } catch (NumberFormatException ignored) {
                    // повреждённая запись
                }
            }
        }
        history.addAll(yml.getStringList("history"));
    }

    public void save() {
        lastSave = now();
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("last-run", lastRun);
        yml.set("report-seq", reportSeq);
        applied.forEach((k, v) -> yml.set("applied." + k, v));
        playerMinutes.forEach((k, v) -> yml.set("hourly." + k, v));
        yml.set("history", history);
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить balance.yml: " + e.getMessage());
        }
    }
}
