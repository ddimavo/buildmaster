package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Request;
import ru.industryreborn.model.RoomClass;
import ru.industryreborn.service.DoorText;
import ru.industryreborn.service.Journal;
import ru.industryreborn.support.PluginTestBase;

/** Классы номеров отеля: эконом по умолчанию, управляющий подаёт заявку, администратор решает. */
class RoomClassTest extends PluginTestBase {

    private PlayerMock steve;     // управляющий отеля
    private PlayerMock alex;      // посторонний
    private PlayerMock boss;
    private Plot hotel;
    private Plot room;

    @BeforeEach
    void setUp() {
        plugin.getConfig().set("limits.total", 0);
        steve = player("Steve", 1_000_000);
        alex = player("Alex", 1_000_000);
        boss = admin("Boss");
        hotel = addPlot("h", Category.HOTEL, 0, 1000, -1);
        assertTrue(plugin.service().buy(steve, hotel));
        room = addPlot("h-101", Category.ROOM, 20, -1, 50);
        room.parentId = hotel.id;
        plugin.hotels().invalidate();
    }

    @Test
    void everyRoomIsEconomyByDefault() {
        assertEquals(RoomClass.ECONOMY, room.roomClass);
    }

    @Test
    void classParsingAcceptsKeysAndRussianTitles() {
        assertEquals(RoomClass.LUXURY, RoomClass.parse("Люкс"));
        assertEquals(RoomClass.LUXURY, RoomClass.parse("luxury"));
        assertEquals(RoomClass.STANDARD, RoomClass.parse("СТАНДАРТ"));
        assertEquals(RoomClass.COMFORT, RoomClass.parse("comfort"));
        assertEquals(RoomClass.ECONOMY, RoomClass.parse("эконом"));
        assertEquals(RoomClass.STANDARD, RoomClass.parse("2"));
        assertEquals(RoomClass.LUXURY, RoomClass.parse("4"));
        assertNull(RoomClass.parse("президентский"));
        assertNull(RoomClass.parse(null));
    }

    @Test
    void anAdminChangesTheClassImmediatelyWithoutReview() {
        assertNull(plugin.hotels().changeClass(boss, room, RoomClass.LUXURY));
        assertEquals(RoomClass.LUXURY, room.roomClass);
        assertTrue(plugin.moderation().pending().isEmpty());
        assertTrue(plugin.journal().forPlot("h-101").stream().anyMatch(e -> e.reason() != null && e.reason().contains("Люкс")));
        assertNotNull(plugin.hotels().changeClass(boss, room, RoomClass.LUXURY), "тот же класс - ошибка");
    }

    @Test
    void aManagerOnlyFilesARequestAndNothingChangesUntilApproval() {
        assertEquals("", plugin.hotels().changeClass(steve, room, RoomClass.COMFORT), "пустая строка - заявка подана");
        assertEquals(RoomClass.ECONOMY, room.roomClass, "до решения класс прежний");
        Request r = plugin.moderation().pendingRoomClass("h-101");
        assertNotNull(r);
        assertEquals(Request.Type.ROOM_CLASS, r.type);
        assertEquals("Эконом → Комфорт", plugin.moderation().describe(r));
        assertNotNull(plugin.hotels().changeClass(steve, room, RoomClass.LUXURY), "вторая заявка, пока первая не решена, не принимается");
        assertEquals(1, plugin.moderation().pending().size());
    }

    @Test
    void approvalChangesTheClassAndNotifiesTheManager() {
        plugin.hotels().changeClass(steve, room, RoomClass.LUXURY);
        Request r = plugin.moderation().pendingRoomClass("h-101");
        plugin.moderation().approve(r, "Boss");
        assertEquals(RoomClass.LUXURY, room.roomClass);
        assertEquals(Request.Status.APPROVED, r.status);
        assertNull(plugin.moderation().pendingRoomClass("h-101"));
        assertTrue(plugin.journal().forPlayer(steve.getUniqueId()).stream().anyMatch(e -> e.action() == Journal.Action.HOTEL));
    }

    @Test
    void rejectionKeepsTheClassAndStoresTheReason() {
        plugin.hotels().changeClass(steve, room, RoomClass.LUXURY);
        Request r = plugin.moderation().pendingRoomClass("h-101");
        plugin.moderation().reject(r, "Boss", "Мебели на люкс не хватает");
        assertEquals(RoomClass.ECONOMY, room.roomClass);
        assertEquals(Request.Status.REJECTED, r.status);
        Journal.Entry last = plugin.journal().forPlayer(steve.getUniqueId()).get(0);
        assertTrue(last.reason().contains("Мебели на люкс не хватает"));
        assertEquals("", plugin.hotels().changeClass(steve, room, RoomClass.STANDARD));
    }

    @Test
    void anOutsiderCannotAskAndAnOldRequestFromAFormerManagerIsVoided() {
        String refused = plugin.hotels().changeClass(alex, room, RoomClass.LUXURY);
        assertTrue(refused != null && !refused.isEmpty(), "посторонний получает отказ, а не заявку");
        assertTrue(plugin.moderation().pending().isEmpty());
        plugin.hotels().changeClass(steve, room, RoomClass.COMFORT);
        Request r = plugin.moderation().pendingRoomClass("h-101");
        plugin.service().release(steve, hotel);                 // отель сменил управляющего
        plugin.moderation().approve(r, "Boss");
        assertEquals(RoomClass.ECONOMY, room.roomClass, "заявка устарела и не применяется");
        assertEquals(Request.Status.REJECTED, r.status);
        assertTrue(r.reason.contains("устарела"));
    }

    @Test
    void theClassCommandShowsRequestsAndChangesForAdmins() {
        steve.performCommand("district room class h-101 комфорт");
        assertEquals(RoomClass.ECONOMY, room.roomClass);
        assertNotNull(plugin.moderation().pendingRoomClass("h-101"));
        alex.performCommand("district room class h-101 люкс");
        assertEquals(1, plugin.moderation().pending().size(), "посторонний заявку не подаёт");
        steve.performCommand("district room class h-101 президентский");
        steve.performCommand("district room class h-101");
        boss.performCommand("district room class h-101 стандарт");
        assertEquals(RoomClass.STANDARD, room.roomClass);
        boss.performCommand("district room class h-101 люкс");
        assertEquals(RoomClass.LUXURY, room.roomClass);
    }

    @Test
    void theClassIsShownOnTheDoorAndSurvivesARestart() {
        plugin.hotels().changeClass(boss, room, RoomClass.COMFORT);
        String text = String.join("\n", DoorText.lines(room, hotel, d -> String.valueOf(d), 0));
        assertTrue(text.contains("Комфорт") && text.contains("★★★"), text);
        plugin.plots().save();
        var fresh = new ru.industryreborn.service.PlotManager(plugin);
        fresh.load();
        assertEquals(RoomClass.COMFORT, fresh.get("h-101").roomClass);
        assertEquals(RoomClass.ECONOMY, fresh.get("h").roomClass, "по умолчанию эконом");
    }
}
