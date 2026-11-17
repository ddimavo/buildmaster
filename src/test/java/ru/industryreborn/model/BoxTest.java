package ru.industryreborn.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BoxTest {

    @Test
    void normalizesCorners() {
        Box b = Box.of(5, 10, 7, 1, 2, 3);
        assertEquals(new Box(1, 2, 3, 5, 10, 7), b);
        assertEquals(5 * 9 * 5, b.volume());
        assertEquals("5×9×5", b.describe().replace("x", "×"));
    }

    @Test
    void containsIsInclusive() {
        Box b = new Box(0, 0, 0, 3, 3, 3);
        assertTrue(b.contains(0, 0, 0));
        assertTrue(b.contains(3, 3, 3));
        assertFalse(b.contains(4, 0, 0));
        assertFalse(b.contains(0, -1, 0));
    }

    @Test
    void intersection() {
        Box a = new Box(0, 0, 0, 10, 10, 10);
        Box b = new Box(5, 5, 5, 20, 20, 20);
        assertEquals(new Box(5, 5, 5, 10, 10, 10), a.intersection(b));
        assertNull(a.intersection(new Box(11, 0, 0, 12, 1, 1)));
        assertTrue(a.contains(new Box(1, 1, 1, 9, 9, 9)));
        assertFalse(a.contains(b));
    }

    @Test
    void serializeRoundTrip() {
        Box b = new Box(-20, -64, 100, -5, 200, 130);
        assertEquals(b, Box.parse(b.serialize()));
        assertThrows(IllegalArgumentException.class, () -> Box.parse("1,2,3"));
    }
}
