package ru.industryreborn.service;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.service.BalanceService.Change;
import ru.industryreborn.service.BalanceService.Limit;
import ru.industryreborn.service.BalanceService.Skip;
import ru.industryreborn.service.BalanceService.Snapshot;

/**
 * Отчёты об автокорректировке экономики. После каждого запуска записываются файлы Markdown, HTML и JSON в каталог отчётов
 * (по умолчанию plugins/IndustryReborn/reports/balance, можно указать любой путь, например каталог сайта): что изменилось, по какой причине,
 * было, стало и изменение в процентах. Рядом лежат latest.* (последний отчёт) и index.json (список всех отчётов для сайта).
 */
public final class BalanceReports {

    /** Итог запуска. */
    public enum Status {
        CHANGED("Изменения применены"), UNCHANGED("Изменений не потребовалось"), NO_DATA("Недостаточно данных");

        private final String title;

        Status(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** Настройки расчёта на момент запуска (для объяснения причин). */
    public record Settings(double target, double strength, double indexMin, double indexMax, double maxStepPercent, double intervalHours) { }

    /** Отчёт одного запуска. */
    public record Report(int number, long time, String actor, Status status, Snapshot snapshot, Settings settings,
                         List<Change> changes, List<Skip> skips, String note) { }

    /** Строка списка отчётов. */
    public record Meta(int number, String id, long time, Status status, int changes, double ratio, double index) { }

    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT);
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm", Locale.ROOT);

    private final IndustryRebornPlugin plugin;

    public BalanceReports(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ настройки

    public boolean enabled() {
        return plugin.getConfig().getBoolean("auto-balance.reports.enabled", true);
    }

    /** Каталог отчётов: относительный путь считается от папки плагина. */
    public File directory() {
        String configured = plugin.getConfig().getString("auto-balance.reports.directory", "");
        if (configured == null || configured.isBlank()) {
            return new File(plugin.getDataFolder(), "reports/balance");
        }
        File f = new File(configured);
        return f.isAbsolute() ? f : new File(plugin.getDataFolder(), configured);
    }

    private List<String> formats() {
        List<String> list = new ArrayList<>();
        for (String f : plugin.getConfig().getStringList("auto-balance.reports.formats")) {
            String v = f.toLowerCase(Locale.ROOT).trim();
            if (List.of("md", "html", "json").contains(v) && !list.contains(v)) {
                list.add(v);
            }
        }
        return list.isEmpty() ? List.of("md", "html", "json") : list;
    }

    private ZoneId zone() {
        String z = plugin.getConfig().getString("auto-balance.reports.timezone", "");
        try {
            return z == null || z.isBlank() ? ZoneId.systemDefault() : ZoneId.of(z);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    public String when(long time) {
        return HUMAN.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(time), zone()));
    }

    /** Имя файлов отчёта без расширения: balance-0007_2026-11-19_20-00. */
    public String id(Report r) {
        return String.format(Locale.ROOT, "balance-%04d_%s", r.number(), FILE.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(r.time()), zone())));
    }

    // ------------------------------------------------------------ текст

    /** Число без лишних нулей: 5, 5.5, 3.25. */
    private static String fmt(double v) {
        double r = Math.round(v * 100.0) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : new java.math.BigDecimal(r).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String money(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Изменение в процентах: «+10.0%», «-4.5%». */
    public static String percent(Change c) {
        return String.format(Locale.ROOT, "%+.1f%%", c.percent());
    }

    public static String value(double v, String unit) {
        return "×".equals(unit) ? "×" + fmt(v) : fmt(v) + unit;
    }

    /** Причина изменения человеческим языком. */
    public String reason(Report r, Change c) {
        Settings s = r.settings();
        Snapshot n = r.snapshot();
        StringBuilder sb = new StringBuilder();
        String ratio = String.format(Locale.ROOT, "%.2f", n.ratio());
        String target = fmt(s.target());
        String index = String.format(Locale.ROOT, "%.2f", n.index());
        if (n.index() > 1.0001) {
            sb.append("В казну уходит меньше целевого: сток/приток ").append(ratio).append(" при цели ").append(target)
                    .append(", индекс цен ").append(index).append(" - значение повышается");
        } else if (n.index() < 0.9999) {
            sb.append("В казну уходит больше целевого: сток/приток ").append(ratio).append(" при цели ").append(target)
                    .append(", индекс цен ").append(index).append(" - значение снижается");
        } else {
            sb.append("Сток/приток ").append(ratio).append(" на целевом уровне (").append(target).append("), индекс цен 1.00 - значение возвращается к базовому ")
                    .append(value(c.base(), c.unit()));
        }
        switch (c.limit()) {
            case STEP -> sb.append("; за один запуск изменение ограничено ").append(fmt(s.maxStepPercent())).append("% базового значения (желаемое значение ")
                    .append(value(c.desired(), c.unit())).append(")");
            case MIN -> sb.append("; достигнут нижний предел ").append(value(c.bound(), c.unit()));
            case MAX -> sb.append("; достигнут верхний предел ").append(value(c.bound(), c.unit()));
            default -> { }
        }
        return sb.toString();
    }

    /** Общая причина запуска: что показали измерения и куда двигаются цены. */
    public String cause(Report r) {
        Settings s = r.settings();
        Snapshot n = r.snapshot();
        String ratio = String.format(Locale.ROOT, "%.2f", n.ratio());
        String index = String.format(Locale.ROOT, "%.2f", n.index());
        if (n.index() > 1.0001) {
            return "В казну уходит меньше целевого: сток/приток " + ratio + " при цели " + fmt(s.target()) + ", индекс цен " + index + " - цены и налоги повышаются.";
        }
        if (n.index() < 0.9999) {
            return "В казну уходит больше целевого: сток/приток " + ratio + " при цели " + fmt(s.target()) + ", индекс цен " + index + " - цены и налоги снижаются.";
        }
        return "Сток/приток " + ratio + " на целевом уровне (" + fmt(s.target()) + "), индекс цен 1.00 - значения возвращаются к базовым.";
    }

    /** Короткая причина конкретного изменения (для таблиц): к чему стремится значение и чем ограничено. */
    public String shortReason(Report r, Change c) {
        StringBuilder sb = new StringBuilder("стремится к ").append(value(c.desired(), c.unit())).append(" (база ").append(value(c.base(), c.unit()))
                .append(" × индекс ").append(String.format(Locale.ROOT, "%.2f", r.snapshot().index())).append(")");
        switch (c.limit()) {
            case STEP -> sb.append("; за один запуск не больше ").append(fmt(r.settings().maxStepPercent())).append("% базового значения");
            case MIN -> sb.append("; нижний предел ").append(value(c.bound(), c.unit()));
            case MAX -> sb.append("; верхний предел ").append(value(c.bound(), c.unit()));
            default -> { }
        }
        return sb.toString();
    }

    /** Строка изменения для чата и вебхуков: «Налог на аренду: 5% → 5.5% (+10.0%)». */
    public String line(Change c) {
        return c.title() + ": " + value(c.from(), c.unit()) + " → " + value(c.to(), c.unit()) + " (" + percent(c) + ")";
    }

    // ------------------------------------------------------------ форматы

    private String measurementRows(Report r, boolean markdown) {
        Snapshot n = r.snapshot();
        Settings s = r.settings();
        List<String[]> rows = List.of(
                new String[] {"Сток (приход в казну за сутки)", plugin.economy().format(n.sink())},
                new String[] {"Приток (заработок игроков за сутки)", plugin.economy().format(n.faucet())},
                new String[] {"Игрочасов за сутки", money(n.playerHours())},
                new String[] {"Отношение сток/приток", String.format(Locale.ROOT, "%.2f (цель %s)", n.ratio(), fmt(s.target()))},
                new String[] {"Индекс цен", String.format(Locale.ROOT, "%.2f (пределы %s-%s, сила %s)", n.index(), fmt(s.indexMin()), fmt(s.indexMax()), fmt(s.strength()))},
                new String[] {"Данных достаточно", n.enough() ? "да" : "нет"});
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            sb.append(markdown ? "| " + row[0] + " | " + row[1] + " |\n"
                    : "<tr><td>" + html(row[0]) + "</td><td>" + html(row[1]) + "</td></tr>\n");
        }
        return sb.toString();
    }

    private static String md(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String howItWorks(Report r) {
        return "Раз в " + fmt(r.settings().intervalHours()) + " ч (не реже раза в 3 суток) плагин сравнивает сток денег в казну с притоком денег в экономику. "
                + "Индекс цен = 1 + сила × (цель - сток/приток) / цель, в пределах " + fmt(r.settings().indexMin()) + "-" + fmt(r.settings().indexMax())
                + ". Значение каждого параметра стремится к «база × индекс», но за один запуск меняется не больше чем на "
                + fmt(r.settings().maxStepPercent()) + "% базового значения и не выходит за пределы. Значения, заданные администратором вручную, не меняются.";
    }

    public String markdown(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Отчёт о корректировке экономики №").append(r.number()).append("\n\n");
        sb.append("**Дата:** ").append(when(r.time())).append(" · **Запуск:** ").append(r.actor()).append(" · **Итог:** ").append(r.status().title()).append("\n\n");
        if (r.note() != null && !r.note().isBlank()) {
            sb.append("> ").append(r.note()).append("\n\n");
        }
        sb.append("## Показатели\n\n| Показатель | Значение |\n|---|---|\n").append(measurementRows(r, true)).append('\n');
        sb.append("## Что изменилось\n\n");
        if (r.status() != Status.NO_DATA) {
            sb.append("**Причина:** ").append(cause(r)).append("\n\n");
        }
        if (r.changes().isEmpty()) {
            sb.append("Изменений не было.\n\n");
        } else {
            sb.append("| Параметр | Было | Стало | Изменение | Причина |\n|---|---|---|---|---|\n");
            for (Change c : r.changes()) {
                sb.append("| ").append(md(c.title())).append(" | ").append(value(c.from(), c.unit())).append(" | ").append(value(c.to(), c.unit()))
                        .append(" | ").append(percent(c)).append(" | ").append(md(shortReason(r, c))).append(" |\n");
            }
            sb.append('\n');
        }
        if (!r.skips().isEmpty()) {
            sb.append("## Без изменений\n\n");
            for (Skip k : r.skips()) {
                sb.append("- **").append(k.title()).append("** - ").append(k.why()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("## Как это считается\n\n").append(howItWorks(r)).append('\n');
        return sb.toString();
    }

    public String htmlPage(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\"><title>Отчёт о корректировке экономики №").append(r.number()).append("</title>\n")
                .append("<style>body{font:15px/1.5 system-ui,sans-serif;max-width:960px;margin:24px auto;padding:0 16px;color:#222}")
                .append("table{border-collapse:collapse;width:100%;margin:8px 0 20px}th,td{border:1px solid #ccc;padding:6px 10px;text-align:left;vertical-align:top}")
                .append("th{background:#f2f2f2}.up{color:#b3261e;font-weight:600}.down{color:#1b7f3b;font-weight:600}.muted{color:#666}</style></head><body>\n");
        sb.append("<h1>Отчёт о корректировке экономики №").append(r.number()).append("</h1>\n");
        sb.append("<p><b>Дата:</b> ").append(html(when(r.time()))).append(" · <b>Запуск:</b> ").append(html(r.actor())).append(" · <b>Итог:</b> ")
                .append(html(r.status().title())).append("</p>\n");
        if (r.note() != null && !r.note().isBlank()) {
            sb.append("<p class=\"muted\">").append(html(r.note())).append("</p>\n");
        }
        sb.append("<h2>Показатели</h2>\n<table><tr><th>Показатель</th><th>Значение</th></tr>\n").append(measurementRows(r, false)).append("</table>\n");
        sb.append("<h2>Что изменилось</h2>\n");
        if (r.status() != Status.NO_DATA) {
            sb.append("<p><b>Причина:</b> ").append(html(cause(r))).append("</p>\n");
        }
        if (r.changes().isEmpty()) {
            sb.append("<p>Изменений не было.</p>\n");
        } else {
            sb.append("<table><tr><th>Параметр</th><th>Было</th><th>Стало</th><th>Изменение</th><th>Причина</th></tr>\n");
            for (Change c : r.changes()) {
                sb.append("<tr><td>").append(html(c.title())).append("</td><td>").append(html(value(c.from(), c.unit()))).append("</td><td>")
                        .append(html(value(c.to(), c.unit()))).append("</td><td class=\"").append(c.to() >= c.from() ? "up" : "down").append("\">")
                        .append(html(percent(c))).append("</td><td>").append(html(shortReason(r, c))).append("</td></tr>\n");
            }
            sb.append("</table>\n");
        }
        if (!r.skips().isEmpty()) {
            sb.append("<h2>Без изменений</h2>\n<ul>\n");
            for (Skip k : r.skips()) {
                sb.append("<li><b>").append(html(k.title())).append("</b> - ").append(html(k.why())).append("</li>\n");
            }
            sb.append("</ul>\n");
        }
        sb.append("<h2>Как это считается</h2>\n<p>").append(html(howItWorks(r))).append("</p>\n</body></html>\n");
        return sb.toString();
    }

    public JsonObject json(Report r) {
        JsonObject o = new JsonObject();
        o.addProperty("number", r.number());
        o.addProperty("id", id(r));
        o.addProperty("time", r.time());
        o.addProperty("date", when(r.time()));
        o.addProperty("actor", r.actor());
        o.addProperty("status", r.status().name());
        o.addProperty("statusTitle", r.status().title());
        o.addProperty("note", r.note() == null ? "" : r.note());
        o.addProperty("cause", r.status() == Status.NO_DATA ? "" : cause(r));
        Snapshot n = r.snapshot();
        JsonObject m = new JsonObject();
        m.addProperty("sink", n.sink());
        m.addProperty("faucet", n.faucet());
        m.addProperty("playerHours", n.playerHours());
        m.addProperty("ratio", n.ratio());
        m.addProperty("index", n.index());
        m.addProperty("enough", n.enough());
        o.add("measurement", m);
        Settings s = r.settings();
        JsonObject st = new JsonObject();
        st.addProperty("target", s.target());
        st.addProperty("strength", s.strength());
        st.addProperty("indexMin", s.indexMin());
        st.addProperty("indexMax", s.indexMax());
        st.addProperty("maxStepPercent", s.maxStepPercent());
        st.addProperty("intervalHours", s.intervalHours());
        o.add("settings", st);
        JsonArray changes = new JsonArray();
        for (Change c : r.changes()) {
            JsonObject j = new JsonObject();
            j.addProperty("key", c.key());
            j.addProperty("title", c.title());
            j.addProperty("unit", c.unit());
            j.addProperty("from", c.from());
            j.addProperty("to", c.to());
            j.addProperty("percent", Math.round(c.percent() * 100.0) / 100.0);
            j.addProperty("base", c.base());
            j.addProperty("desired", c.desired());
            j.addProperty("limit", c.limit().name());
            j.addProperty("bound", c.bound());
            j.addProperty("reason", reason(r, c));
            j.addProperty("shortReason", shortReason(r, c));
            changes.add(j);
        }
        o.add("changes", changes);
        JsonArray skips = new JsonArray();
        for (Skip k : r.skips()) {
            JsonObject j = new JsonObject();
            j.addProperty("key", k.key());
            j.addProperty("title", k.title());
            j.addProperty("why", k.why());
            skips.add(j);
        }
        o.add("unchanged", skips);
        return o;
    }

    /** Отчёт из JSON-файла (для /district balance report после перезапуска). */
    public Report parse(JsonObject o) {
        JsonObject m = o.getAsJsonObject("measurement");
        JsonObject st = o.getAsJsonObject("settings");
        Snapshot n = new Snapshot(m.get("sink").getAsDouble(), m.get("faucet").getAsDouble(), m.get("playerHours").getAsDouble(), m.get("ratio").getAsDouble(),
                m.get("index").getAsDouble(), m.get("enough").getAsBoolean());
        Settings s = new Settings(st.get("target").getAsDouble(), st.get("strength").getAsDouble(), st.get("indexMin").getAsDouble(), st.get("indexMax").getAsDouble(),
                st.get("maxStepPercent").getAsDouble(), st.get("intervalHours").getAsDouble());
        List<Change> changes = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("changes")) {
            JsonObject j = e.getAsJsonObject();
            changes.add(new Change(j.get("key").getAsString(), j.get("title").getAsString(), j.get("from").getAsDouble(), j.get("to").getAsDouble(),
                    j.get("unit").getAsString(), j.get("base").getAsDouble(), j.get("desired").getAsDouble(), Limit.valueOf(j.get("limit").getAsString()),
                    j.get("bound").getAsDouble()));
        }
        List<Skip> skips = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("unchanged")) {
            JsonObject j = e.getAsJsonObject();
            skips.add(new Skip(j.get("key").getAsString(), j.get("title").getAsString(), j.get("why").getAsString()));
        }
        return new Report(o.get("number").getAsInt(), o.get("time").getAsLong(), o.get("actor").getAsString(), Status.valueOf(o.get("status").getAsString()),
                n, s, changes, skips, o.get("note").getAsString());
    }

    // ------------------------------------------------------------ файлы

    /** Записывает отчёт в выбранных форматах, обновляет latest.* и index.json, чистит старые. Возвращает имена записанных файлов. */
    public List<File> write(Report r) {
        List<File> written = new ArrayList<>();
        if (!enabled()) {
            return written;
        }
        File dir = directory();
        try {
            Files.createDirectories(dir.toPath());
            String id = id(r);
            for (String f : formats()) {
                String text = switch (f) {
                    case "md" -> markdown(r);
                    case "html" -> htmlPage(r);
                    default -> new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json(r)) + "\n";
                };
                File file = new File(dir, id + "." + f);
                Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
                Files.writeString(new File(dir, "latest." + f).toPath(), text, StandardCharsets.UTF_8);
                written.add(file);
            }
            writeIndex(r, id);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось записать отчёт об автокорректировке в " + dir, e);
        }
        return written;
    }

    private void writeIndex(Report r, String id) throws IOException {
        File dir = directory();
        JsonArray list = new JsonArray();
        JsonObject self = new JsonObject();
        self.addProperty("number", r.number());
        self.addProperty("id", id);
        self.addProperty("time", r.time());
        self.addProperty("date", when(r.time()));
        self.addProperty("status", r.status().name());
        self.addProperty("statusTitle", r.status().title());
        self.addProperty("changes", r.changes().size());
        self.addProperty("ratio", r.snapshot().ratio());
        self.addProperty("index", r.snapshot().index());
        JsonObject files = new JsonObject();
        for (String f : formats()) {
            files.addProperty(f, id + "." + f);
        }
        self.add("files", files);
        list.add(self);
        File index = new File(dir, "index.json");
        if (index.exists()) {
            try {
                for (JsonElement e : JsonParser.parseString(Files.readString(index.toPath(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("reports")) {
                    if (e.getAsJsonObject().get("number").getAsInt() != r.number()) {
                        list.add(e);
                    }
                }
            } catch (RuntimeException ignored) {
                // повреждённый индекс собирается заново из новых отчётов
            }
        }
        int keep = Math.max(0, plugin.getConfig().getInt("auto-balance.reports.keep", 0));
        while (keep > 0 && list.size() > keep) {
            JsonObject old = list.remove(list.size() - 1).getAsJsonObject();
            for (var e : old.getAsJsonObject("files").entrySet()) {
                Files.deleteIfExists(new File(dir, e.getValue().getAsString()).toPath());
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("updated", when(r.time()));
        root.add("reports", list);
        Files.writeString(index.toPath(), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n", StandardCharsets.UTF_8);
    }

    /** Список отчётов, новые первыми (из index.json). */
    public List<Meta> list() {
        List<Meta> out = new ArrayList<>();
        File index = new File(directory(), "index.json");
        if (!index.exists()) {
            return out;
        }
        try {
            for (JsonElement e : JsonParser.parseString(Files.readString(index.toPath(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("reports")) {
                JsonObject j = e.getAsJsonObject();
                out.add(new Meta(j.get("number").getAsInt(), j.get("id").getAsString(), j.get("time").getAsLong(), Status.valueOf(j.get("status").getAsString()),
                        j.get("changes").getAsInt(), j.get("ratio").getAsDouble(), j.get("index").getAsDouble()));
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось прочитать index.json отчётов", e);
        }
        return out;
    }

    /** Отчёт по номеру из JSON-файла либо null. */
    public Report load(int number) {
        for (Meta m : list()) {
            if (m.number() == number) {
                try {
                    return parse(JsonParser.parseString(Files.readString(new File(directory(), m.id() + ".json").toPath(), StandardCharsets.UTF_8)).getAsJsonObject());
                } catch (IOException | RuntimeException e) {
                    return null;        // нет JSON-файла (формат отключён) или файл повреждён
                }
            }
        }
        return null;
    }
}
