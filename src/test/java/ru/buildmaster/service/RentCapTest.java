package ru.buildmaster.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RentCapTest {

    @Test
    void growsWithSize() {
        assertEquals(40, RentCap.compute(2000, 20, 10, 500), 1e-9);
        assertEquals(100, RentCap.compute(5000, 20, 10, 500), 1e-9);
    }

    @Test
    void smallPlotsGetTheMinimum() {
        assertEquals(10, RentCap.compute(100, 20, 10, 500), 1e-9);
        assertEquals(10, RentCap.compute(0, 20, 10, 500), 1e-9);
        assertEquals(10, RentCap.compute(-5, 20, 10, 500), 1e-9);
    }

    @Test
    void hugePlotsAreCapped() {
        assertEquals(500, RentCap.compute(10_000_000, 20, 10, 500), 1e-9);
    }

    @Test
    void zeroMaxMeansNoUpperLimitAndRoundingIsToCents() {
        assertEquals(20000, RentCap.compute(1_000_000, 20, 10, 0), 1e-9);
        assertEquals(0.33, RentCap.compute(1000, 0.333, 0, 0), 1e-9);
    }
}
