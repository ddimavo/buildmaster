package ru.industryreborn.webhook;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.service.Module;
import ru.industryreborn.service.StatsService;

/**
 * Вебхуки: периодическая сводка и события (корректировка экономики, переключение функций, выплаты из казны) в Discord,
 * на форум XenForo или Invision Community либо на любой адрес с JSON. Запросы уходят в отдельном потоке.
 */
public final class WebhookService {

    public record Request(String method, String url, Map<String, String> headers, String body) { }

    public record Response(int status, String body) { }

    /** Отправка HTTP-запроса (в тестах подменяется). */
    public interface Transport {
        Response send(Request request) throws IOException, InterruptedException;
    }

    /** Одна точка назначения из config.yml. */
    public record Endpoint(String name, String type, String url, ConfigurationSection cfg) {
        public int intervalMinutes() {
            return cfg.getInt("interval-minutes", 60);
        }

        public Set<String> events() {
            List<String> list = cfg.getStringList("events");
            return list.isEmpty() ? Set.of("stats") : new HashSet<>(list.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
        }

        public Set<String> sections() {
            return new HashSet<>(cfg.getStringList("sections"));
        }

        public boolean edit() {
            return !cfg.getString("mode", "edit").equalsIgnoreCase("new");
        }
    }

    private static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        @Override
        public Response send(Request r) throws IOException, InterruptedException {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(r.url())).timeout(Duration.ofSeconds(20));
            r.headers().forEach(b::header);
            b.method(r.method(), r.body() == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(r.body()));
            HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(res.statusCode(), res.body());
        }
    }

    private final IndustryRebornPlugin plugin;
    private final File stateFile;
    private final Map<String, String> messageIds = new LinkedHashMap<>();
    private final Map<String, Long> lastSent = new LinkedHashMap<>();
    private final Map<String, String> lastStatus = new LinkedHashMap<>();
    private Transport transport = new HttpTransport();
    private Executor executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IndustryReborn-webhooks");
        t.setDaemon(true);
        return t;
    });
    private LongSupplier clock = System::currentTimeMillis;

    public WebhookService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "webhooks.yml");
        load();
    }

    /** Только для тестов. */
    public void setTransport(Transport transport) {
        this.transport = transport;
    }

    /** Только для тестов: выполнять отправку сразу. */
    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean enabled() {
        return plugin.modules().enabled(Module.WEBHOOKS);
    }

    public List<Endpoint> endpoints() {
        List<Endpoint> list = new ArrayList<>();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("webhooks.endpoints");
        if (root == null) {
            return list;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null || !s.getBoolean("enabled", true)) {
                continue;
            }
            String url = s.getString("url", "").trim();
            if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                continue;
            }
            list.add(new Endpoint(name, s.getString("type", "json").toLowerCase(Locale.ROOT), url.replaceAll("/+$", ""), s));
        }
        return list;
    }

    public Endpoint endpoint(String name) {
        for (Endpoint e : endpoints()) {
            if (e.name().equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }

    public String statusOf(String name) {
        return lastStatus.getOrDefault(name, "ещё не отправлялось");
    }

    public long lastSent(String name) {
        return lastSent.getOrDefault(name, 0L);
    }

    // ------------------------------------------------------------ расписание

    /** Раз в минуту: рассылает сводку тем, кому пора. */
    public void minuteTick() {
        if (!enabled()) {
            return;
        }
        for (Endpoint e : endpoints()) {
            if (!e.events().contains("stats") || e.intervalMinutes() <= 0) {
                continue;
            }
            long due = lastSent.getOrDefault(e.name(), 0L) + Math.max(1, e.intervalMinutes()) * 60_000L;
            if (clock.getAsLong() >= due) {
                sendStats(e);
            }
        }
    }

    /** Отправляет сводку сейчас. Возвращает false, если функция отключена. */
    public boolean sendStats(Endpoint e) {
        if (!enabled()) {
            return false;
        }
        StatsService.Stats stats = plugin.stats().collect();       // собираем на основном потоке
        lastSent.put(e.name(), clock.getAsLong());
        executor.execute(() -> deliverStats(e, stats));
        return true;
    }

    /** Событие: корректировка экономики, переключение функций, выплата из казны. */
    public void event(String kind, String title, String text, int color) {
        if (!enabled()) {
            return;
        }
        for (Endpoint e : endpoints()) {
            if (e.events().contains(kind)) {
                executor.execute(() -> deliverEvent(e, title, text, color));
            }
        }
    }

    /** Проверка связи: короткое сообщение. */
    public boolean test(Endpoint e) {
        if (!enabled()) {
            return false;
        }
        executor.execute(() -> deliverEvent(e, "IndustryReborn", "Проверка связи: вебхук «" + e.name() + "» работает.", 0x4CAF50));
        return true;
    }

    // ------------------------------------------------------------ доставка

    private void deliverStats(Endpoint e, StatsService.Stats stats) {
        try {
            switch (e.type()) {
                case "discord" -> discordStats(e, stats);
                case "xenforo" -> forumStats(e, WebhookFormats.bbcode(stats, e.sections()), true);
                case "invision", "ips" -> forumStats(e, WebhookFormats.html(stats, e.sections()), false);
                default -> post(e, jsonHeaders(e), WebhookFormats.json(stats, e.sections()), "статистика");
            }
        } catch (IOException | RuntimeException ex) {
            fail(e, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void deliverEvent(Endpoint e, String title, String text, int color) {
        try {
            switch (e.type()) {
                case "discord" -> {
                    Response r = transport.send(new Request("POST", e.url(), Map.of("Content-Type", "application/json"),
                            WebhookFormats.discordEvent(title, text, color, e.cfg().getString("username", "IndustryReborn"))));
                    record(e, r, "событие");
                }
                case "xenforo", "invision", "ips" -> {
                    if (e.cfg().getInt("thread-id", 0) <= 0 && e.cfg().getInt("topic", 0) <= 0) {
                        return;     // событие нужно куда-то написать: без темы форума пропускаем
                    }
                    forumNewPost(e, "<p><strong>" + title + "</strong><br>" + text.replace("\n", "<br>") + "</p>",
                            "[B]" + title + "[/B]\n" + text);
                }
                default -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("event", title);
                    body.put("text", text);
                    body.put("time", clock.getAsLong());
                    post(e, jsonHeaders(e), Json.write(body), "событие");
                }
            }
        } catch (IOException | RuntimeException ex) {
            fail(e, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, String> jsonHeaders(Endpoint e) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        String auth = e.cfg().getString("auth-header", "");
        if (!auth.isBlank()) {
            h.put("Authorization", auth);
        }
        return h;
    }

    private void post(Endpoint e, Map<String, String> headers, String body, String what) throws IOException, InterruptedException {
        record(e, transport.send(new Request("POST", e.url(), headers, body)), what);
    }

    private void record(Endpoint e, Response r, String what) {
        boolean ok = r.status() >= 200 && r.status() < 300;
        lastStatus.put(e.name(), (ok ? "успешно" : "ошибка") + " (HTTP " + r.status() + ", " + what + ")");
        if (!ok) {
            plugin.getLogger().warning("Вебхук " + e.name() + ": HTTP " + r.status() + " " + abbreviate(r.body()));
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private void fail(Endpoint e, Exception ex) {
        lastStatus.put(e.name(), "ошибка (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
        plugin.getLogger().log(Level.WARNING, "Вебхук " + e.name() + " не отправлен: " + ex.getMessage());
    }

    // ------------------------------------------------------------ Discord

    private void discordStats(Endpoint e, StatsService.Stats stats) throws IOException, InterruptedException {
        String body = WebhookFormats.discordStats(stats, e.cfg().getString("username", "IndustryReborn"), e.sections());
        Map<String, String> h = Map.of("Content-Type", "application/json");
        String id = messageIds.get(e.name());
        if (e.edit() && id != null) {
            Response r = transport.send(new Request("PATCH", e.url() + "/messages/" + id, h, body));
            if (r.status() != 404) {
                record(e, r, "статистика (правка сообщения)");
                return;
            }
            messageIds.remove(e.name());            // сообщение удалено: создадим новое
        }
        Response r = transport.send(new Request("POST", e.url() + "?wait=true", h, body));
        record(e, r, "статистика");
        if (e.edit()) {
            String newId = find(r.body(), "\"id\"\\s*:\\s*\"(\\d+)\"");
            if (newId != null) {
                messageIds.put(e.name(), newId);
                saveState();
            }
        }
    }

    // ------------------------------------------------------------ форумы

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(sb.length() > 0 ? "&" : "").append(enc(k)).append('=').append(enc(v)));
        return sb.toString();
    }

    private Map<String, String> forumHeaders(Endpoint e) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        if (e.type().equals("xenforo")) {
            h.put("XF-Api-Key", e.cfg().getString("api-key", ""));
            h.put("XF-Api-User", String.valueOf(e.cfg().getInt("api-user", 1)));
        } else {
            String key = e.cfg().getString("api-key", "");
            h.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((key + ":").getBytes(StandardCharsets.UTF_8)));
        }
        return h;
    }

    private static String find(String body, String regex) {
        if (body == null) {
            return null;
        }
        Matcher m = Pattern.compile(regex).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** Сводка на форум: правим прежнее сообщение (mode: edit) либо пишем новое. */
    private void forumStats(Endpoint e, String message, boolean bbcode) throws IOException, InterruptedException {
        Map<String, String> h = forumHeaders(e);
        String id = messageIds.get(e.name());
        boolean xf = e.type().equals("xenforo");
        if (e.edit() && id != null) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put(xf ? "message" : "post", message);
            Response r = transport.send(new Request("POST", e.url() + (xf ? "/posts/" + id + "/" : "/forums/posts/" + id), h, form(f)));
            if (r.status() != 404) {
                record(e, r, "статистика (правка сообщения)");
                return;
            }
            messageIds.remove(e.name());
        }
        Response r;
        Map<String, String> f = new LinkedHashMap<>();
        if (xf) {
            int thread = e.cfg().getInt("thread-id", 0);
            if (thread > 0) {
                f.put("thread_id", String.valueOf(thread));
                f.put("message", message);
                r = transport.send(new Request("POST", e.url() + "/posts/", h, form(f)));
                saveId(e, r, "\"post_id\"\\s*:\\s*(\\d+)");
            } else {
                f.put("node_id", String.valueOf(e.cfg().getInt("node-id", 0)));
                f.put("title", e.cfg().getString("title", "IndustryReborn: статистика района"));
                f.put("message", message);
                r = transport.send(new Request("POST", e.url() + "/threads/", h, form(f)));
                saveId(e, r, "\"first_post_id\"\\s*:\\s*(\\d+)");
            }
        } else {
            int topic = e.cfg().getInt("topic", 0);
            f.put("author", String.valueOf(e.cfg().getInt("author", 1)));
            if (topic > 0) {
                f.put("topic", String.valueOf(topic));
                f.put("post", message);
                r = transport.send(new Request("POST", e.url() + "/forums/posts", h, form(f)));
                saveId(e, r, "\"id\"\\s*:\\s*(\\d+)");
            } else {
                f.put("forum", String.valueOf(e.cfg().getInt("forum", 0)));
                f.put("title", e.cfg().getString("title", "IndustryReborn: статистика района"));
                f.put("post", message);
                r = transport.send(new Request("POST", e.url() + "/forums/topics", h, form(f)));
                String first = find(r.body(), "\"firstPost\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*(\\d+)");
                if (first != null && e.edit() && r.status() < 300) {
                    messageIds.put(e.name(), first);
                    saveState();
                }
            }
        }
        record(e, r, "статистика");
    }

    private void saveId(Endpoint e, Response r, String regex) {
        if (!e.edit() || r.status() >= 300) {
            return;
        }
        String id = find(r.body(), regex);
        if (id != null) {
            messageIds.put(e.name(), id);
            saveState();
        }
    }

    /** Новое сообщение в существующей теме форума (события). */
    private void forumNewPost(Endpoint e, String html, String bbcode) throws IOException, InterruptedException {
        Map<String, String> h = forumHeaders(e);
        Map<String, String> f = new LinkedHashMap<>();
        Response r;
        if (e.type().equals("xenforo")) {
            f.put("thread_id", String.valueOf(e.cfg().getInt("thread-id", 0)));
            f.put("message", bbcode);
            r = transport.send(new Request("POST", e.url() + "/posts/", h, form(f)));
        } else {
            f.put("topic", String.valueOf(e.cfg().getInt("topic", 0)));
            f.put("author", String.valueOf(e.cfg().getInt("author", 1)));
            f.put("post", html);
            r = transport.send(new Request("POST", e.url() + "/forums/posts", h, form(f)));
        }
        record(e, r, "событие");
    }

    // ------------------------------------------------------------ состояние

    private void load() {
        if (!stateFile.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(stateFile);
        ConfigurationSection s = yml.getConfigurationSection("messages");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                messageIds.put(k, s.getString(k));
            }
        }
    }

    private void saveState() {
        YamlConfiguration yml = new YamlConfiguration();
        messageIds.forEach((k, v) -> yml.set("messages." + k, v));
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить webhooks.yml: " + e.getMessage());
        }
    }
}
