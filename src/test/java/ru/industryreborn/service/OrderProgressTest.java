package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OrderProgressTest {

    @Test
    void severalStationsWorkInParallel() {
        assertEquals(10.0, OrderProgress.secondsPerUnit(10, 1, 1.0), 1e-9);
        assertEquals(5.0, OrderProgress.secondsPerUnit(10, 2, 1.0), 1e-9);
        assertEquals(2.5, OrderProgress.secondsPerUnit(10, 4, 1.0), 1e-9);
    }

    @Test
    void noStationsMeansTheOrderCannotMove() {
        assertTrue(Double.isInfinite(OrderProgress.secondsPerUnit(10, 0, 1.0)));
        assertEquals(0, OrderProgress.unitsReady(1_000_000, Double.POSITIVE_INFINITY, 10));
        assertTrue(Double.isInfinite(OrderProgress.remainingSeconds(10, 0, Double.POSITIVE_INFINITY)));
    }

    @Test
    void timeFactorScalesDuration() {
        assertEquals(5.0, OrderProgress.secondsPerUnit(10, 1, 0.5), 1e-9);
        assertEquals(20.0, OrderProgress.secondsPerUnit(10, 1, 2.0), 1e-9);
        assertEquals(0.0, OrderProgress.secondsPerUnit(10, 1, 0.0), 1e-9);
    }

    @Test
    void readyUnitsGrowWithProgressAndAreCapped() {
        assertEquals(0, OrderProgress.unitsReady(4.9, 5, 10));
        assertEquals(1, OrderProgress.unitsReady(5.0, 5, 10));
        assertEquals(3, OrderProgress.unitsReady(17, 5, 10));
        assertEquals(10, OrderProgress.unitsReady(500, 5, 10), "не больше размера заказа");
    }

    @Test
    void zeroDurationFinishesImmediately() {
        assertEquals(7, OrderProgress.unitsReady(0, 0, 7));
    }

    @Test
    void remainingTime() {
        assertEquals(100, OrderProgress.remainingSeconds(20, 0, 5), 1e-9);
        assertEquals(50, OrderProgress.remainingSeconds(20, 50, 5), 1e-9);
        assertEquals(0, OrderProgress.remainingSeconds(20, 120, 5), 1e-9);
    }
}
