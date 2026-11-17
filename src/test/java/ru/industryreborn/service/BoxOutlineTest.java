package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.industryreborn.model.Box;
import ru.industryreborn.util.BoxOutline;

class BoxOutlineTest {

    @Test
    void theOutlineFollowsBlockBoundaries() {
        Box box = new Box(10, 5, 20, 12, 6, 23);      // 3 x 2 x 4
        List<double[]> pts = BoxOutline.edgePoints(box, 10_000);
        for (double[] p : pts) {
            assertTrue(p[0] >= 10 && p[0] <= 13 && p[1] >= 5 && p[1] <= 7 && p[2] >= 20 && p[2] <= 24, "точка внутри границ");
            boolean onX = p[0] == 10 || p[0] == 13;
            boolean onY = p[1] == 5 || p[1] == 7;
            boolean onZ = p[2] == 20 || p[2] == 24;
            assertTrue((onX ? 1 : 0) + (onY ? 1 : 0) + (onZ ? 1 : 0) >= 2, "точка лежит на ребре");
        }
    }

    @Test
    void thereIsAPointAtEveryCorner() {
        Box box = new Box(0, 0, 0, 4, 4, 4);
        List<double[]> pts = BoxOutline.edgePoints(box, 10_000);
        for (double[] c : BoxOutline.corners(box)) {
            assertTrue(pts.stream().anyMatch(p -> p[0] == c[0] && p[1] == c[1] && p[2] == c[2]));
        }
        assertEquals(8, BoxOutline.corners(box).size());
    }

    @Test
    void bigBoxesAreThinnedToTheBudget() {
        Box huge = new Box(0, 0, 0, 199, 99, 199);
        List<double[]> pts = BoxOutline.edgePoints(huge, 400);
        assertTrue(pts.size() <= 400 + 12 * 2 + 100, "точек немного: " + pts.size());
        assertTrue(pts.size() > 100);
    }

    @Test
    void smallBoxesHaveAtLeastOnePointPerBlock() {
        Box box = new Box(0, 0, 0, 9, 0, 0);          // 10 x 1 x 1
        List<double[]> pts = BoxOutline.edgePoints(box, 10_000);
        long onLongEdges = pts.stream().filter(p -> p[1] == 0 && p[2] == 0).count();
        assertTrue(onLongEdges >= 10, "по блоку на длину ребра: " + onLongEdges);
    }

    @Test
    void aSingleBlockStillHasAnOutline() {
        Box one = new Box(5, 5, 5, 5, 5, 5);
        assertFalse(BoxOutline.edgePoints(one, 100).isEmpty());
        assertEquals(12.0, BoxOutline.edgeLength(one), 1e-9);
    }
}
