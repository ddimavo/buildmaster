package ru.industryreborn.webhook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.industryreborn.service.StatsService;

/** Оформление сводки для разных площадок: Discord (embed), форумы (BBCode и HTML), обычный JSON. */
public final class WebhookFormats {

    private WebhookFormats() { }

    private static List<StatsService.Section> pick(StatsService.Stats stats, Set<String> only) {
        List<StatsService.Section> list = new ArrayList<>();
        for (StatsService.Section s : stats.sections()) {
            if (only.isEmpty() || only.contains(s.id())) {
                list.add(s);
            }
        }
        return list;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------ Discord

    /** Тело вебхука Discord: один embed с полем на раздел. */
    public static String discordStats(StatsService.Stats stats, String username, Set<String> only) {
        List<Object> fields = new ArrayList<>();
        for (StatsService.Section s : pick(stats, only)) {
            StringBuilder v = new StringBuilder();
            for (StatsService.Metric m : s.metrics()) {
                v.append("**").append(m.label()).append(":** ").append(m.text()).append('\n');
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", cut(s.title(), 256));
            f.put("value", cut(v.toString().trim(), 1024));
            f.put("inline", Boolean.TRUE);
            fields.add(f);
            if (fields.size() >= 24) {
                break;
            }
        }
        if (!stats.topRevenue().isEmpty() && (only.isEmpty() || only.contains("top"))) {
            StringBuilder v = new StringBuilder();
            int i = 1;
            for (StatsService.Top t : stats.topRevenue()) {
                v.append(i++).append(". ").append(t.name()).append(" (").append(t.owner()).append(") - ").append(t.text()).append('\n');
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", "Лучшие предприятия по выручке");
            f.put("value", cut(v.toString().trim(), 1024));
            f.put("inline", Boolean.FALSE);
            fields.add(f);
        }
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", "IndustryReborn: статистика района");
        embed.put("color", 0xFFCF5A);
        embed.put("fields", fields);
        embed.put("timestamp", Instant.ofEpochMilli(stats.time()).toString());
        Map<String, Object> body = new LinkedHashMap<>();
        if (username != null && !username.isBlank()) {
            body.put("username", username);
        }
        body.put("embeds", List.of(embed));
        return Json.write(body);
    }

    /** Короткое событие для Discord. */
    public static String discordEvent(String title, String text, int color, String username) {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", cut(title, 256));
        embed.put("description", cut(text, 4000));
        embed.put("color", color);
        embed.put("timestamp", Instant.now().toString());
        Map<String, Object> body = new LinkedHashMap<>();
        if (username != null && !username.isBlank()) {
            body.put("username", username);
        }
        body.put("embeds", List.of(embed));
        return Json.write(body);
    }

    // ------------------------------------------------------------ форумы

    /** BBCode (XenForo). */
    public static String bbcode(StatsService.Stats stats, Set<String> only) {
        StringBuilder sb = new StringBuilder("[SIZE=5][B]IndustryReborn: статистика района[/B][/SIZE]\n");
        for (StatsService.Section s : pick(stats, only)) {
            sb.append("\n[B]").append(s.title()).append("[/B]\n[LIST]\n");
            for (StatsService.Metric m : s.metrics()) {
                sb.append("[*]").append(m.label()).append(": [B]").append(m.text()).append("[/B]\n");
            }
            sb.append("[/LIST]\n");
        }
        if (!stats.topRevenue().isEmpty() && (only.isEmpty() || only.contains("top"))) {
            sb.append("\n[B]Лучшие предприятия по выручке[/B]\n[LIST=1]\n");
            for (StatsService.Top t : stats.topRevenue()) {
                sb.append("[*]").append(t.name()).append(" (").append(t.owner()).append(") - [B]").append(t.text()).append("[/B]\n");
            }
            sb.append("[/LIST]\n");
        }
        sb.append("\n[SIZE=2]Обновлено: ").append(Instant.ofEpochMilli(stats.time())).append("[/SIZE]");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** HTML (Invision Community). */
    public static String html(StatsService.Stats stats, Set<String> only) {
        StringBuilder sb = new StringBuilder("<h2>IndustryReborn: статистика района</h2>");
        for (StatsService.Section s : pick(stats, only)) {
            sb.append("<h3>").append(esc(s.title())).append("</h3><ul>");
            for (StatsService.Metric m : s.metrics()) {
                sb.append("<li>").append(esc(m.label())).append(": <strong>").append(esc(m.text())).append("</strong></li>");
            }
            sb.append("</ul>");
        }
        if (!stats.topRevenue().isEmpty() && (only.isEmpty() || only.contains("top"))) {
            sb.append("<h3>Лучшие предприятия по выручке</h3><ol>");
            for (StatsService.Top t : stats.topRevenue()) {
                sb.append("<li>").append(esc(t.name())).append(" (").append(esc(t.owner())).append(") - <strong>").append(esc(t.text())).append("</strong></li>");
            }
            sb.append("</ol>");
        }
        sb.append("<p><small>Обновлено: ").append(Instant.ofEpochMilli(stats.time())).append("</small></p>");
        return sb.toString();
    }

    /** Простой JSON для собственных обработчиков: время, плоский список показателей и разделы. */
    public static String json(StatsService.Stats stats, Set<String> only) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("plugin", "IndustryReborn");
        root.put("time", stats.time());
        Map<String, Object> sections = new LinkedHashMap<>();
        for (StatsService.Section s : pick(stats, only)) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            for (StatsService.Metric m : s.metrics()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("label", m.label());
                one.put("value", m.value());
                one.put("text", m.text());
                metrics.put(m.id(), one);
            }
            Map<String, Object> sec = new LinkedHashMap<>();
            sec.put("title", s.title());
            sec.put("metrics", metrics);
            sections.put(s.id(), sec);
        }
        root.put("sections", sections);
        List<Object> top = new ArrayList<>();
        for (StatsService.Top t : stats.topRevenue()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", t.name());
            one.put("owner", t.owner());
            one.put("revenue", t.value());
            top.add(one);
        }
        root.put("top-revenue", top);
        return Json.write(root);
    }
}
