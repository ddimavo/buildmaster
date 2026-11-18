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

    // ------------------------------------------------------------ цены, срок, электричество

    @Test
    void changingTheClassFitsThePriceIntoItsRange() {
        room.rentPerDay = 50;
        plugin.hotels().changeClass(boss, room, RoomClass.LUXURY);                 // от 300
        assertEquals(300, room.rentPerDay, 1e-9);
        assertTrue(plugin.journal().forPlot("h-101").stream().anyMatch(e -> e.reason() != null && e.reason().contains("подогнана")));
        room.rentPerDay = 900;
        plugin.hotels().changeClass(boss, room, RoomClass.ECONOMY);                // до 100
        assertEquals(100, room.rentPerDay, 1e-9);
        room.rentPerDay = 60;
        plugin.hotels().changeClass(boss, room, RoomClass.STANDARD);               // 60 уже в границах 50-250
        assertEquals(60, room.rentPerDay, 1e-9);
    }

    @Test
    void aManagerPriceMustStayInsideTheClassRangeButAnAdminIsFree() {
        hotel.maxRoomRate = 10_000;
        room.roomClass = RoomClass.STANDARD;                                       // 50-250
        assertNull(plugin.hotels().setRate(steve, room, 120));
        assertEquals(120, room.rentPerDay, 1e-9);
        assertTrue(plugin.hotels().setRate(steve, room, 400).contains("50"), "выше границы класса");
        assertNotNull(plugin.hotels().setRate(steve, room, 10), "ниже границы класса");
        assertEquals(120, room.rentPerDay, 1e-9);
        assertNull(plugin.hotels().setRate(boss, room, 400), "администратор назначает любую цену");
        assertEquals(400, room.rentPerDay, 1e-9);
    }

    @Test
    void theRentTermDependsOnTheClass() {
        room.rentPerDay = 10;
        assertFalse(plugin.service().rent(alex, room, 4), "эконом - до 3 суток");
        assertTrue(plugin.service().rent(alex, room, 3));
        assertEquals(3, plugin.service().maxRentDays(room));
        assertFalse(plugin.service().extend(alex, room, 1), "всего не больше 3 суток");
        plugin.hotels().changeClass(boss, room, RoomClass.COMFORT);
        assertEquals(14, plugin.service().maxRentDays(room));
        room.rentPerDay = 200;
        assertTrue(plugin.service().extend(alex, room, 5), "теперь можно продлить");
        assertEquals(14, plugin.service().maxRentDays(hotel), "обычные участки - общий предел");
    }

    @Test
    void disablingClassesRemovesEveryLimitAndMultiplier() {
        plugin.getConfig().set("hotel-classes.enabled", false);
        room.rentPerDay = 5000;
        assertTrue(plugin.service().rent(alex, room, 14), "эконом без ограничений срока");
        assertNull(plugin.hotels().setRate(boss, room, 7));
        hotel.maxRoomRate = 10_000;
        assertNull(plugin.hotels().setRate(steve, room, 9999));
        room.roomClass = RoomClass.LUXURY;
        assertEquals(1.0, plugin.hotels().powerFactor(RoomClass.LUXURY), 1e-9);
        assertEquals(0, plugin.hotels().maxRate(RoomClass.ECONOMY), 1e-9);
    }

    @Test
    void configValuesOverrideTheDefaults() {
        plugin.getConfig().set("hotel-classes.economy.max-days", 5);
        plugin.getConfig().set("hotel-classes.economy.max-rate", 0);
        plugin.getConfig().set("hotel-classes.economy.power", 2.0);
        assertEquals(5, plugin.hotels().maxDays(RoomClass.ECONOMY));
        assertEquals(0, plugin.hotels().maxRate(RoomClass.ECONOMY), 1e-9);
        assertEquals(2.0, plugin.hotels().powerFactor(RoomClass.ECONOMY), 1e-9);
        plugin.getConfig().set("hotel-classes.luxury.max-days", 99);
        assertEquals(14, plugin.hotels().maxDays(RoomClass.LUXURY), "не больше общего max-rent-days");
    }

    @Test
    void electricityUseGrowsWithTheClass() {
        double base = plugin.power().roomUnits(room);
        room.roomClass = RoomClass.COMFORT;
        assertEquals(base * 1.2, plugin.power().roomUnits(room), 1e-9);
        room.roomClass = RoomClass.LUXURY;
        assertEquals(base * 1.5, plugin.power().roomUnits(room), 1e-9);
    }

    // ------------------------------------------------------------ статистика

    @Test
    void revenueAndCountsAreSummedPerClass() {
        Plot room2 = addPlot("h-102", Category.ROOM, 40, -1, 50);
        room2.parentId = hotel.id;
        room2.roomClass = RoomClass.LUXURY;
        plugin.hotels().invalidate();
        plugin.hotels().income(room, 100, "аренда");
        plugin.hotels().income(room2, 300, "аренда");
        plugin.hotels().income(room2, 50, "аренда");
        var sum = plugin.hotels().classSummary(hotel);
        assertEquals(1, sum.get(RoomClass.ECONOMY)[0]);
        assertEquals(1, sum.get(RoomClass.LUXURY)[0]);
        assertEquals(0, sum.get(RoomClass.COMFORT)[0]);
        assertEquals(room.roomRevenue, sum.get(RoomClass.ECONOMY)[2], 1e-9);
        assertTrue(room2.roomRevenue > room.roomRevenue);
        var stats = plugin.stats().collect();
        assertEquals(1, stats.metric("hotels", "rooms-luxury").value(), 1e-9);
        assertEquals(room2.roomRevenue, stats.metric("hotels", "revenue-luxury").value(), 1e-9);
        assertEquals(0, stats.metric("hotels", "rooms-comfort").value(), 1e-9);
        plugin.plots().save();
        var fresh = new ru.industryreborn.service.PlotManager(plugin);
        fresh.load();
        assertEquals(room2.roomRevenue, fresh.get("h-102").roomRevenue, 1e-9, "доход номера сохраняется");
    }

    // ------------------------------------------------------------ телепорт к двери

    @Test
    void theStandSpotIsInTheCorridorInFrontOfTheDoorFacingIt() {
        var interior = new ru.industryreborn.model.Box(20, 0, 0, 29, 9, 9);
        double[] spot = DoorText.standSpot(interior, 24, 1, -1);                   // дверь в южной... северной стене (z = -1)
        assertNotNull(spot);
        assertEquals(24.5, spot[0], 1e-9);
        assertEquals(-2.0, spot[2], 1e-9, "в коридоре, вне интерьера");
        assertEquals(0.0, spot[3], 1e-9, "лицом к двери (на юг)");
        double[] east = DoorText.standSpot(interior, 30, 1, 4);                    // дверь в восточной стене
        assertEquals(32.0, east[0], 1e-9);
        assertEquals(90.0, Math.abs(east[3]), 1e-9);
        assertNull(DoorText.standSpot(interior, 24, 1, 4), "дверь не в стене: стороны нет");
    }

    @Test
    void theModerationMenuTeleportsTheAdminToTheDoorOfARoomAwaitingAClass() {
        room.doors.add(new int[] {24, 1, -1});
        plugin.hotels().changeClass(steve, room, RoomClass.LUXURY);
        Request r = plugin.moderation().pendingRoomClass("h-101");
        var spot = plugin.moderation().teleportSpot(r);
        assertNotNull(spot);
        assertEquals(24.5, spot.getX(), 1e-9);
        assertEquals(-2.0, spot.getZ(), 1e-9);
        boss.teleport(new org.bukkit.Location(server.getWorld("world"), 300, 1, 300));
        new ru.industryreborn.gui.ModerationMenu(plugin, boss, 0).open();
        var icon = boss.getOpenInventory().getTopInventory().getItem(9);
        assertEquals(org.bukkit.Material.RED_BED, icon.getType());
        String lore = String.valueOf(icon.lore());
        assertTrue(lore.contains("Эконом") && lore.contains("Люкс") && lore.contains("двери"), lore);
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryClickEvent(boss.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 9,
                org.bukkit.event.inventory.ClickType.SHIFT_LEFT, org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY));
        assertEquals(24.5, boss.getLocation().getX(), 1e-6, "администратор у двери номера");
        assertEquals(-2.0, boss.getLocation().getZ(), 1e-6);
        assertEquals(RoomClass.ECONOMY, room.roomClass, "телепорт ничего не решает");
    }

    @Test
    void theTeleportCommandAndRoomsWithoutDoorsFallBack() {
        plugin.hotels().changeClass(steve, room, RoomClass.LUXURY);
        Request r = plugin.moderation().pendingRoomClass("h-101");
        assertNull(plugin.moderation().teleportSpot(r), "дверь не задана: обычный телепорт к номеру");
        room.doors.add(new int[] {24, 1, -1});
        boss.performCommand("district moderation tp " + ru.industryreborn.service.ModerationService.shortId(r));
        assertEquals(24.5, boss.getLocation().getX(), 1e-6);
        assertEquals(-2.0, boss.getLocation().getZ(), 1e-6);
    }

    // ------------------------------------------------------------ меню

    @Test
    void theRoomsMenuShowsClassesFiltersAndLetsTheManagerAsk() {
        Plot room2 = addPlot("h-102", Category.ROOM, 40, -1, 80);
        room2.parentId = hotel.id;
        room2.roomClass = RoomClass.LUXURY;
        plugin.hotels().invalidate();
        new ru.industryreborn.gui.RoomsMenu(plugin, steve, hotel, null, 0).open();
        var inv = steve.getOpenInventory().getTopInventory();
        assertEquals(RoomClass.ECONOMY.icon(), inv.getItem(9).getType());
        assertEquals(RoomClass.LUXURY.icon(), inv.getItem(10).getType());
        assertTrue(String.valueOf(inv.getItem(9).lore()).contains("Эконом"));
        assertTrue(String.valueOf(inv.getItem(4).lore()).contains("Люкс"), "сводка по классам");
        steve.simulateInventoryClick(steve.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, 2);   // фильтр: эконом
        inv = steve.getOpenInventory().getTopInventory();
        assertEquals(RoomClass.ECONOMY.icon(), inv.getItem(9).getType());
        assertTrue(inv.getItem(10).getType().name().endsWith("PANE"), "остался один номер");
        steve.simulateInventoryClick(steve.getOpenInventory(), org.bukkit.event.inventory.ClickType.RIGHT, 9);
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof ru.industryreborn.gui.RoomClassMenu);
        steve.simulateInventoryClick(steve.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, 16);   // люкс
        assertNotNull(plugin.moderation().pendingRoomClass("h-101"), "управляющий подал заявку");
        assertEquals(RoomClass.ECONOMY, room.roomClass);
        assertTrue(String.valueOf(steve.getOpenInventory().getTopInventory().getItem(16).lore()).contains("Заявка подана"));
    }

    @Test
    void anAdminPicksAClassInTheMenuImmediatelyAndGuestsGetNoRightClickMenu() {
        new ru.industryreborn.gui.RoomClassMenu(plugin, boss, room, null).open();
        boss.simulateInventoryClick(boss.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, 14);     // комфорт
        assertEquals(RoomClass.COMFORT, room.roomClass);
        assertTrue(plugin.moderation().pending().isEmpty());
        room.status = Plot.Status.RENTED;
        room.owner = alex.getUniqueId();
        room.ownerName = "Alex";
        room.rentedUntil = System.currentTimeMillis() + 86_400_000L;
        new ru.industryreborn.gui.RoomsMenu(plugin, alex, hotel, null, 0).open();
        alex.simulateInventoryClick(alex.getOpenInventory(), org.bukkit.event.inventory.ClickType.RIGHT, 9);
        assertFalse(alex.getOpenInventory().getTopInventory().getHolder() instanceof ru.industryreborn.gui.RoomClassMenu, "гость класс не меняет");
        new ru.industryreborn.gui.PlotMenu(plugin, steve, room, null).open();
        assertEquals(RoomClass.COMFORT.icon(), steve.getOpenInventory().getTopInventory().getItem(51).getType(), "кнопка класса у управляющего отеля");
        new ru.industryreborn.gui.PlotMenu(plugin, alex, room, null).open();
        assertTrue(alex.getOpenInventory().getTopInventory().getItem(51).getType().name().endsWith("PANE"), "у гостя её нет");
    }
}
