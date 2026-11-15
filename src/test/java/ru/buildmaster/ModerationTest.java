package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Request;
import ru.buildmaster.service.Journal;
import ru.buildmaster.support.PluginTestBase;

/** Названия и описания публикуются только после одобрения администратором. */
class ModerationTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock boss;
    private Plot plot;

    @BeforeEach
    void setUpPlot() {
        steve = player("Steve", 1000);
        boss = admin("Boss");
        plot = addPlot("a", Category.SHOP, 0, 100, -1);
        plugin.service().buy(steve, plot);
    }

    @Test
    void playerTextWaitsForApproval() {
        assertNull(plugin.moderation().submit(steve, plot, Request.Type.NAME, "Лавка Стива"));
        assertNull(plot.name, "до одобрения название не публикуется");
        assertEquals(1, plugin.moderation().pending().size());
    }

    @Test
    void approvalPublishesAndLogsIt() {
        plugin.moderation().submit(steve, plot, Request.Type.NAME, "Лавка Стива");
        Request r = plugin.moderation().pending().get(0);

        plugin.moderation().approve(r, "Boss");

        assertEquals("Лавка Стива", plot.name);
        assertTrue(plugin.moderation().pending().isEmpty());
        assertEquals(Request.Status.APPROVED, r.status);
        Journal.Entry last = plugin.journal().forPlayer(steve.getUniqueId()).get(0);
        assertEquals(Journal.Action.NAME_APPROVED, last.action());
        assertEquals("Boss", last.actor());
    }

    @Test
    void rejectionKeepsOldTextAndStoresReasonInJournal() {
        plot.name = "Старое имя";
        plugin.moderation().submit(steve, plot, Request.Type.NAME, "Плохое имя");
        Request r = plugin.moderation().pending().get(0);

        plugin.moderation().reject(r, "Boss", "Оскорбительное название");

        assertEquals("Старое имя", plot.name);
        assertEquals(Request.Status.REJECTED, r.status);
        Journal.Entry last = plugin.journal().forPlayer(steve.getUniqueId()).get(0);
        assertEquals(Journal.Action.NAME_REJECTED, last.action());
        assertTrue(last.reason().contains("Оскорбительное название"), "причина доступна игроку в журнале");
        assertTrue(last.reason().contains("Плохое имя"));
    }

    @Test
    void decidedRequestCannotBeDecidedAgain() {
        plugin.moderation().submit(steve, plot, Request.Type.DESCRIPTION, "Хороший магазин");
        Request r = plugin.moderation().pending().get(0);
        plugin.moderation().reject(r, "Boss", "причина");
        plugin.moderation().approve(r, "Boss");
        assertEquals(Request.Status.REJECTED, r.status);
        assertNull(plot.description);
    }

    @Test
    void newSubmissionReplacesPendingOneOfSameType() {
        plugin.moderation().submit(steve, plot, Request.Type.NAME, "Первое");
        plugin.moderation().submit(steve, plot, Request.Type.NAME, "Второе");
        plugin.moderation().submit(steve, plot, Request.Type.DESCRIPTION, "Описание");
        assertEquals(2, plugin.moderation().pending().size());
        assertEquals("Второе", plugin.moderation().pending().stream()
                .filter(r -> r.type == Request.Type.NAME).findFirst().orElseThrow().text);
    }

    @Test
    void invalidTextIsRejectedImmediately() {
        assertNotNull(plugin.moderation().submit(steve, plot, Request.Type.NAME, "<red>Взлом"));
        assertNotNull(plugin.moderation().submit(steve, plot, Request.Type.NAME, "а".repeat(100)));
        assertNotNull(plugin.moderation().submit(steve, plot, Request.Type.DESCRIPTION, "  "));
        assertTrue(plugin.moderation().pending().isEmpty());
    }

    @Test
    void descriptionHasItsOwnLongerLimit() {
        assertNull(plugin.moderation().submit(steve, plot, Request.Type.DESCRIPTION, "а".repeat(120)));
        assertNotNull(plugin.moderation().submit(steve, plot, Request.Type.DESCRIPTION, "а".repeat(121)));
    }

    @Test
    void adminTextIsPublishedWithoutQueue() {
        assertNull(plugin.moderation().submit(boss, plot, Request.Type.NAME, "Государственный магазин"));
        assertEquals("Государственный магазин", plot.name);
        assertTrue(plugin.moderation().pending().isEmpty());
    }

    @Test
    void requestsSurviveRestart() {
        plugin.moderation().submit(steve, plot, Request.Type.NAME, "Лавка");
        Request r = plugin.moderation().pending().get(0);
        plugin.moderation().reject(r, "Boss", "нельзя");
        plugin.moderation().submit(steve, plot, Request.Type.DESCRIPTION, "Описание");

        plugin.moderation().load();

        assertEquals(1, plugin.moderation().pending().size());
        Request rejected = plugin.moderation().forPlayer(steve.getUniqueId()).stream()
                .filter(x -> x.status == Request.Status.REJECTED).findFirst().orElseThrow();
        assertEquals("нельзя", rejected.reason);
        assertEquals("Boss", rejected.decidedBy);
    }

    @Test
    void offlineDecisionIsDeliveredOnJoin() {
        plugin.moderation().submit(steve, plot, Request.Type.NAME, "Лавка");
        Request r = plugin.moderation().pending().get(0);
        java.util.UUID id = steve.getUniqueId();
        steve.disconnect();
        plugin.moderation().reject(r, "Boss", "Запрещённое слово");
        assertFalse(r.notified);

        PlayerMock back = new PlayerMock(server, "Steve", id);
        server.addPlayer(back);
        plugin.moderation().deliverMissed(back);

        assertTrue(r.notified);
        String all = drain(back);
        assertTrue(all.contains("Запрещённое слово"), all);
    }

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }
}
