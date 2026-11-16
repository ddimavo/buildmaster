package ru.buildmaster.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;

class DoorTextTest {

    private static Plot hotel() {
        Plot h = new Plot("h", "world", new Box(0, 0, 0, 30, 6, 30));
        h.category = Category.HOTEL;
        h.name = "Север";
        return h;
    }

    private static Plot room() {
        Plot r = new Plot("h-101", "world", new Box(4, 1, 4, 7, 3, 7));
        r.category = Category.ROOM;
        r.parentId = "h";
        r.rentPerDay = 60;
        return r;
    }

    private static String text(List<String> lines) {
        return String.join("\n", lines).replaceAll("<[^>]+>", "");
    }

    @Test
    void theNumberDropsTheHotelPrefix() {
        assertEquals("101", DoorText.number(room(), hotel()));
        Plot odd = room();
        assertEquals("h-101", DoorText.number(odd, null));
        assertEquals("h-101", DoorText.number(odd, new Plot("x", "world", new Box(0, 0, 0, 1, 1, 1))));
        Plot upper = new Plot("H-12", "world", new Box(0, 0, 0, 1, 1, 1));
        assertEquals("12", DoorText.number(upper, hotel()), "регистр не важен");
    }

    @Test
    void aFreeRoomShowsPriceAndSize() {
        String t = text(DoorText.lines(room(), hotel(), v -> v + "$", 0));
        assertTrue(t.contains("Номер 101"), t);
        assertTrue(t.contains("Север"));
        assertTrue(t.contains("● Свободен"));
        assertTrue(t.contains("60.0$") || t.contains("60$"), t);
        assertTrue(t.contains("4×3×4"));
    }

    @Test
    void anOccupiedRoomShowsTheGuestAndTheTimeLeft() {
        Plot r = room();
        r.status = Plot.Status.RENTED;
        r.ownerName = "Alex";
        r.rentedUntil = 3 * 86_400_000L + 1000;
        String t = text(DoorText.lines(r, hotel(), v -> v + "$", 0));
        assertTrue(t.contains("● Занят"), t);
        assertTrue(t.contains("Гость: Alex"));
        assertTrue(t.contains("3 д."), t);
        assertFalse(t.contains("Свободен"));
    }

    @Test
    void aFrozenRoomWaitsForPayment() {
        Plot r = room();
        r.status = Plot.Status.RENTED;
        r.ownerName = "Alex";
        r.rentedUntil = 1000;
        r.graceUntil = Long.MAX_VALUE;
        assertTrue(text(DoorText.lines(r, hotel(), v -> "", 2000)).contains("Ожидает оплаты"));
    }

    @Test
    void aRoomBeingCleanedSaysSo() {
        Plot r = room();
        r.restorePending = true;
        String t = text(DoorText.lines(r, hotel(), v -> "", 0));
        assertTrue(t.contains("Идёт уборка"));
        assertFalse(t.contains("Свободен"));
    }

    @Test
    void aRoomNameIsShownWhenSet() {
        Plot r = room();
        r.name = "Люкс";
        assertTrue(text(DoorText.lines(r, hotel(), v -> "", 0)).contains("Люкс"));
    }

    @Test
    void escapesTagsInNames() {
        Plot r = room();
        r.status = Plot.Status.RENTED;
        r.ownerName = "<red>Evil";
        r.rentedUntil = 5000;
        String raw = String.join("\n", DoorText.lines(r, hotel(), v -> "", 0));
        assertTrue(raw.contains("\\<red>Evil"), "теги из имени экранируются: " + raw);
    }

    // ------------------------------------------------------------ положение

    @Test
    void theAnchorIsOnTheCorridorSideAboveTheDoor() {
        Box room = new Box(4, 1, 4, 7, 3, 7);
        // дверь в южной стене (z = 3): интерьер к +z, коридор к -z
        double[] a = DoorText.anchor(room, 5, 1, 3);
        assertNotNull(a);
        assertEquals(5.5, a[0], 1e-9);
        assertTrue(a[2] < 3.5, "со стороны коридора: " + a[2]);
        assertEquals(3.65, a[1], 1e-9);
        // дверь в северной стене (z = 8)
        double[] n = DoorText.anchor(room, 5, 1, 8);
        assertTrue(n[2] > 8.5);
        // восточная (x = 8) и западная (x = 3) стены
        assertTrue(DoorText.anchor(room, 8, 1, 5)[0] > 8.5);
        assertTrue(DoorText.anchor(room, 3, 1, 5)[0] < 3.5);
    }

    @Test
    void aDoorNotAdjacentToTheRoomHasNoAnchor() {
        assertNull(DoorText.anchor(new Box(4, 1, 4, 7, 3, 7), 20, 1, 20));
        assertNull(DoorText.anchor(new Box(4, 1, 4, 7, 3, 7), 5, 1, 5), "дверь внутри комнаты: сторон нет");
    }
}
