package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.industryreborn.model.Box;

class RoomScannerTest {

    /** Мир из сплошного камня; свободные клетки заданы явно. */
    private static RoomScanner.Fillable open(Set<String> cells) {
        return (x, y, z) -> cells.contains(x + "," + y + "," + z);
    }

    private static Set<String> cube(int x0, int y0, int z0, int x1, int y1, int z1) {
        Set<String> s = new HashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    s.add(x + "," + y + "," + z);
                }
            }
        }
        return s;
    }

    private static final Box HOTEL = new Box(0, 0, 0, 30, 10, 30);

    @Test
    void anEnclosedRoomGivesItsBoundingBox() {
        Set<String> room = cube(5, 1, 5, 8, 3, 9);
        RoomScanner.Result r = RoomScanner.scan(open(room), 5, 1, 5, HOTEL, 1000);
        assertTrue(r.ok());
        assertEquals(new Box(5, 1, 5, 8, 3, 9), r.box());
        assertEquals(4 * 3 * 5, r.cells());
    }

    @Test
    void furnitureInsideDoesNotShrinkTheBox() {
        Set<String> room = cube(5, 1, 5, 8, 3, 9);
        room.remove("6,1,6");        // кровать
        room.remove("7,2,7");
        RoomScanner.Result r = RoomScanner.scan(open(room), 5, 1, 5, HOTEL, 1000);
        assertEquals(new Box(5, 1, 5, 8, 3, 9), r.box());
    }

    @Test
    void anLShapedRoomGivesTheBoundingBoxOfAllCells() {
        Set<String> room = cube(5, 1, 5, 8, 2, 6);
        room.addAll(cube(5, 1, 7, 6, 2, 10));
        RoomScanner.Result r = RoomScanner.scan(open(room), 5, 1, 5, HOTEL, 1000);
        assertEquals(new Box(5, 1, 5, 8, 2, 10), r.box());
    }

    @Test
    void aHoleToTheOutsideMeansNotEnclosed() {
        Set<String> room = cube(0, 1, 5, 8, 3, 9);
        room.add("-1,2,7");          // окно на границе отеля, ведущее наружу
        RoomScanner.Result r = RoomScanner.scan(open(room), 4, 1, 5, HOTEL, 1000);
        assertFalse(r.ok());
        assertTrue(r.error().contains("не замкнута"), r.error());
    }

    @Test
    void tooBigARegionIsRejected() {
        Set<String> hall = cube(1, 1, 1, 29, 5, 29);
        RoomScanner.Result r = RoomScanner.scan(open(hall), 1, 1, 1, HOTEL, 500);
        assertFalse(r.ok());
        assertTrue(r.error().contains("500"));
    }

    @Test
    void aStartInsideASolidBlockOrOutsideTheHotelFails() {
        assertFalse(RoomScanner.scan(open(Set.of()), 5, 1, 5, HOTEL, 100).ok());
        assertFalse(RoomScanner.scan(open(cube(40, 1, 40, 42, 3, 42)), 40, 1, 40, HOTEL, 100).ok());
    }

    @Test
    void aSingleCellRoomIsFound() {
        RoomScanner.Result r = RoomScanner.scan(open(Set.of("3,3,3")), 3, 3, 3, HOTEL, 10);
        assertEquals(new Box(3, 3, 3, 3, 3, 3), r.box());
        assertEquals(1, r.cells());
    }

    @Test
    void negativeCoordinatesWork() {
        Box hotel = new Box(-30, -10, -30, 0, 10, 0);
        Set<String> room = cube(-10, -2, -10, -7, 0, -6);
        RoomScanner.Result r = RoomScanner.scan(open(room), -10, -2, -10, hotel, 1000);
        assertEquals(new Box(-10, -2, -10, -7, 0, -6), r.box());
        assertEquals(4 * 3 * 5, r.cells(), "клетки с разными знаками не склеиваются");
    }
}
