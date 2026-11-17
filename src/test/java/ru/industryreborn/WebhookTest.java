package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Module;
import ru.industryreborn.service.StatsService;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.webhook.WebhookFormats;
import ru.industryreborn.webhook.WebhookService;

/** Вебхуки: сводка и события в Discord, XenForo, Invision и на обычный адрес. */
class WebhookTest extends PluginTestBase {

    private final List<WebhookService.Request> sent = new CopyOnWriteArrayList<>();
    private final List<WebhookService.Response> answers = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private PlayerMock boss;
    private PlayerMock steve;

    @BeforeEach
    void setUp() {
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.shop", 0);
        plugin.getConfig().set("modules.webhooks", true);
        plugin.webhooks().setExecutor(Runnable::run);
        plugin.webhooks().setClock(clock::get);
        plugin.webhooks().setTransport(r -> {
            sent.add(r);
            return answers.isEmpty() ? new WebhookService.Response(200, "{}") : answers.remove(0);
        });
        boss = admin("Boss");
        steve = player("Steve", 100_000);
        Plot shop = addPlot("s", Category.SHOP, 0, -1, 80);
        shop.name = "Лавка \"Феи\"";
        assertTrue(plugin.service().rent(steve, shop, 3));
        shop.revenue = 1234;
        plugin.treasury().deposit(500, "Аренда");
    }

    private void endpoint(String name, Map<String, Object> values) {
        YamlConfiguration yml = new YamlConfiguration();
        values.forEach((k, v) -> yml.set("webhooks.endpoints." + name + "." + k, v));
        for (String key : yml.getConfigurationSection("webhooks.endpoints." + name).getKeys(false)) {
            plugin.getConfig().set("webhooks.endpoints." + name + "." + key, yml.get("webhooks.endpoints." + name + "." + key));
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ сводка

    @Test
    void statsCoverAllSectionsWithNumbersAndText() {
        StatsService.Stats s = plugin.stats().collect();
        assertEquals(2, s.metric("general", "online").value(), 0);
        assertTrue(s.metric("economy", "treasury").value() >= 500);
        assertEquals(1, s.metric("general", "cat.shop").value(), 0);
        assertNotNull(s.section("electricity"));
        assertNotNull(s.section("production"));
        assertNotNull(s.section("hotels"));
        assertEquals(1234, s.topRevenue().get(0).value(), 0);
        assertTrue(StatsService.flat(s).containsKey("economy.treasury"));
        plugin.modules().set(Module.ELECTRICITY, false, "Boss");
        assertNull(plugin.stats().collect().section("electricity"), "отключённые функции в сводку не попадают");
    }

    @Test
    void discordEmbedHasFieldsAndEscapesText() {
        String json = WebhookFormats.discordStats(plugin.stats().collect(), "Бот", java.util.Set.of());
        assertTrue(json.contains("\"username\":\"Бот\"") && json.contains("\"embeds\""));
        assertTrue(json.contains("Лавка \\\"Феи\\\""), "кавычки экранируются: " + json);
        assertTrue(json.contains("\"fields\"") && json.contains("Баланс казны"));
    }

    @Test
    void sectionsFilterWorksInEveryFormat() {
        var stats = plugin.stats().collect();
        java.util.Set<String> only = java.util.Set.of("economy");
        assertFalse(WebhookFormats.bbcode(stats, only).contains("Электросеть"));
        assertTrue(WebhookFormats.bbcode(stats, only).contains("Баланс казны"));
        assertFalse(WebhookFormats.html(stats, only).contains("Электросеть"));
        assertFalse(WebhookFormats.json(stats, only).contains("\"electricity\""));
        assertFalse(WebhookFormats.discordStats(stats, null, only).contains("Электросеть"));
    }

    @Test
    void htmlAndBbcodeEscapeNames() {
        var stats = plugin.stats().collect();
        assertTrue(WebhookFormats.html(stats, java.util.Set.of()).contains("Лавка \"Феи\""));
        plugin.plots().get("s").name = "<b>Хак</b> & Ко";
        String html = WebhookFormats.html(plugin.stats().collect(), java.util.Set.of());
        assertTrue(html.contains("&lt;b&gt;Хак&lt;/b&gt; &amp; Ко"));
        assertFalse(html.contains("<b>Хак"));
    }

    // ------------------------------------------------------------ Discord

    @Test
    void discordCreatesTheMessageThenEditsIt() {
        endpoint("d", Map.of("type", "discord", "url", "https://discord.test/api/webhooks/1/abc", "interval-minutes", 10));
        answers.add(new WebhookService.Response(200, "{\"id\":\"998877\",\"channel_id\":\"1\"}"));
        assertTrue(plugin.webhooks().sendStats(plugin.webhooks().endpoint("d")));
        assertEquals("POST", sent.get(0).method());
        assertEquals("https://discord.test/api/webhooks/1/abc?wait=true", sent.get(0).url());
        assertTrue(sent.get(0).body().contains("embeds"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("d"));
        assertEquals("PATCH", sent.get(1).method());
        assertEquals("https://discord.test/api/webhooks/1/abc/messages/998877", sent.get(1).url());
    }

    @Test
    void discordFallsBackToANewMessageWhenTheOldOneIsGone() {
        endpoint("d", Map.of("type", "discord", "url", "https://discord.test/hook"));
        answers.add(new WebhookService.Response(200, "{\"id\":\"1\"}"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("d"));
        answers.add(new WebhookService.Response(404, "{}"));
        answers.add(new WebhookService.Response(200, "{\"id\":\"2\"}"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("d"));
        assertEquals("PATCH", sent.get(1).method());
        assertEquals("POST", sent.get(2).method());
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("d"));
        assertTrue(sent.get(3).url().endsWith("/messages/2"));
    }

    @Test
    void newModeAlwaysPostsANewMessage() {
        endpoint("d", Map.of("type", "discord", "url", "https://discord.test/hook", "mode", "new"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("d"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("d"));
        assertEquals(List.of("POST", "POST"), sent.stream().map(WebhookService.Request::method).toList());
    }

    @Test
    void messageIdsSurviveARestart() {
        endpoint("d", Map.of("type", "discord", "url", "https://discord.test/hook"));
        answers.add(new WebhookService.Response(200, "{\"id\":\"777\"}"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("d"));
        var fresh = new WebhookService(plugin);
        fresh.setExecutor(Runnable::run);
        fresh.setTransport(r -> {
            sent.add(r);
            return new WebhookService.Response(200, "{}");
        });
        fresh.sendStats(fresh.endpoint("d"));
        assertEquals("PATCH", sent.get(sent.size() - 1).method());
        assertTrue(sent.get(sent.size() - 1).url().endsWith("/messages/777"));
    }

    // ------------------------------------------------------------ форумы

    @Test
    void xenforoPostsToAThreadThenEditsThePost() {
        endpoint("x", Map.of("type", "xenforo", "url", "https://forum.test/api/", "api-key", "KEY", "api-user", 7, "thread-id", 123));
        answers.add(new WebhookService.Response(200, "{\"success\":true,\"post\":{\"post_id\":4567,\"thread_id\":123}}"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("x"));
        var first = sent.get(0);
        assertEquals("https://forum.test/api/posts/", first.url());
        assertEquals("KEY", first.headers().get("XF-Api-Key"));
        assertEquals("7", first.headers().get("XF-Api-User"));
        assertTrue(first.body().contains("thread_id=123"));
        assertTrue(decode(first.body()).contains("[B]Район[/B]"), decode(first.body()));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("x"));
        var second = sent.get(1);
        assertEquals("https://forum.test/api/posts/4567/", second.url());
        assertTrue(second.body().startsWith("message="));
        assertFalse(second.body().contains("thread_id"));
    }

    @Test
    void xenforoCanCreateTheThreadFirst() {
        endpoint("x", Map.of("type", "xenforo", "url", "https://forum.test/api", "api-key", "K", "node-id", 5, "title", "Статистика"));
        answers.add(new WebhookService.Response(200, "{\"thread\":{\"thread_id\":9,\"first_post_id\":321}}"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("x"));
        assertEquals("https://forum.test/api/threads/", sent.get(0).url());
        assertTrue(sent.get(0).body().contains("node_id=5") && decode(sent.get(0).body()).contains("title=Статистика"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("x"));
        assertEquals("https://forum.test/api/posts/321/", sent.get(1).url());
    }

    @Test
    void invisionPostsWithBasicAuthThenEdits() {
        endpoint("i", Map.of("type", "invision", "url", "https://forum.test/api", "api-key", "SECRET", "author", 3, "topic", 45));
        answers.add(new WebhookService.Response(201, "{\"id\":888,\"topic\":{\"id\":45}}"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("i"));
        var first = sent.get(0);
        assertEquals("https://forum.test/api/forums/posts", first.url());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("SECRET:".getBytes(StandardCharsets.UTF_8)), first.headers().get("Authorization"));
        assertTrue(first.body().contains("topic=45") && first.body().contains("author=3"));
        assertTrue(decode(first.body()).contains("<h2>IndustryReborn"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("i"));
        assertEquals("https://forum.test/api/forums/posts/888", sent.get(1).url());
        assertTrue(sent.get(1).body().startsWith("post="));
    }

    @Test
    void invisionCanCreateATopic() {
        endpoint("i", Map.of("type", "invision", "url", "https://forum.test/api", "api-key", "K", "forum", 2, "title", "Статистика"));
        answers.add(new WebhookService.Response(201, "{\"id\":10,\"firstPost\":{\"id\":777}}"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("i"));
        assertEquals("https://forum.test/api/forums/topics", sent.get(0).url());
        assertTrue(sent.get(0).body().contains("forum=2"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("i"));
        assertEquals("https://forum.test/api/forums/posts/777", sent.get(1).url());
    }

    // ------------------------------------------------------------ события, расписание, команды

    @Test
    void eventsGoOnlyToSubscribersAndCoverBalanceModulesAndPayouts() {
        endpoint("a", Map.of("type", "discord", "url", "https://discord.test/a", "events", List.of("balance", "module", "payout")));
        endpoint("b", Map.of("type", "discord", "url", "https://discord.test/b", "events", List.of("stats")));
        boss.performCommand("district module jobs off");
        assertEquals(1, sent.size());
        assertEquals("https://discord.test/a", sent.get(0).url());
        assertTrue(sent.get(0).body().contains("Работа и зарплаты") && sent.get(0).body().contains("отключена"));
        plugin.payouts().prize("Boss", steve, 100, "Турнир");
        assertTrue(sent.get(sent.size() - 1).body().contains("Приз из казны"));
        assertTrue(sent.get(sent.size() - 1).body().contains("Турнир"));
        plugin.getConfig().set("modules.auto-balance", true);
        plugin.getConfig().set("auto-balance.faucet-per-day", 10_000);
        plugin.balance().run(false, "система");
        assertTrue(sent.stream().anyMatch(r -> r.body().contains("Экономика скорректирована")));
        assertTrue(sent.stream().noneMatch(r -> r.url().endsWith("/b")));
    }

    @Test
    void theScheduleSendsStatsWhenTheIntervalPasses() {
        endpoint("d", Map.of("type", "json", "url", "https://example.test/hook", "interval-minutes", 30));
        plugin.webhooks().minuteTick();
        assertEquals(1, sent.size(), "первая отправка сразу");
        assertTrue(sent.get(0).body().contains("\"sections\""));
        plugin.webhooks().minuteTick();
        assertEquals(1, sent.size());
        clock.addAndGet(31 * 60_000L);
        plugin.webhooks().minuteTick();
        assertEquals(2, sent.size());
    }

    @Test
    void intervalZeroMeansNoSchedule() {
        endpoint("d", Map.of("type", "json", "url", "https://example.test/hook", "interval-minutes", 0));
        plugin.webhooks().minuteTick();
        assertTrue(sent.isEmpty());
    }

    @Test
    void theWebhooksModuleSwitchBlocksEverything() {
        endpoint("d", Map.of("type", "json", "url", "https://example.test/hook"));
        plugin.modules().set(Module.WEBHOOKS, false, "Boss");
        plugin.webhooks().minuteTick();
        assertFalse(plugin.webhooks().sendStats(plugin.webhooks().endpoint("d")));
        assertFalse(plugin.webhooks().test(plugin.webhooks().endpoint("d")));
        assertTrue(sent.isEmpty());
        boss.performCommand("district webhook list");
        assertTrue(sent.isEmpty());
    }

    @Test
    void invalidEndpointsAreIgnored() {
        endpoint("bad", Map.of("type", "discord", "url", "ftp://x"));
        endpoint("empty", Map.of("type", "discord", "url", ""));
        endpoint("off", Map.of("type", "discord", "url", "https://a.test", "enabled", false));
        assertTrue(plugin.webhooks().endpoints().isEmpty());
    }

    @Test
    void failuresAreRecordedAndNeverThrow() {
        endpoint("d", Map.of("type", "json", "url", "https://example.test/hook"));
        plugin.webhooks().setTransport(r -> {
            throw new java.io.IOException("нет связи");
        });
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("d"));
        assertTrue(plugin.webhooks().statusOf("d").contains("нет связи"));
        plugin.webhooks().setTransport(r -> new WebhookService.Response(500, "oops"));
        plugin.webhooks().sendStats(plugin.webhooks().endpoint("d"));
        assertTrue(plugin.webhooks().statusOf("d").contains("HTTP 500"));
    }

    @Test
    void commandsListSendAndTestAreForAdminsOnly() {
        endpoint("d", Map.of("type", "json", "url", "https://example.test/hook"));
        boss.performCommand("district webhook send d");
        assertEquals(1, sent.size());
        boss.performCommand("district webhook test d");
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).body().contains("Проверка связи"));
        boss.performCommand("district webhook send nope");
        assertEquals(2, sent.size());
        steve.performCommand("district webhook send d");
        assertEquals(2, sent.size());
    }

    // ------------------------------------------------------------ настоящий HTTP

    @Test
    void realHttpDeliveryToALocalServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch got = new CountDownLatch(1);
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> auth = new CopyOnWriteArrayList<>();
        server.createContext("/hook", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
            got.countDown();
        });
        server.start();
        try {
            endpoint("real", Map.of("type", "json", "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "auth-header", "Bearer T"));
            var real = new WebhookService(plugin);
            real.sendStats(real.endpoint("real"));
            assertTrue(got.await(10, TimeUnit.SECONDS), "запрос не дошёл");
            assertTrue(bodies.get(0).contains("\"plugin\":\"IndustryReborn\""));
            assertEquals("Bearer T", auth.get(0));
        } finally {
            server.stop(0);
        }
    }
}
