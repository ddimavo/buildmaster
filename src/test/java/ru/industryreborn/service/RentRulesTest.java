package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RentRulesTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_000 * DAY;

    @Test
    void extendAfterTwoDaysOfFourteenAllowsTwo() {
        // арендовал на 14 суток, прошло 2 - осталось 12, продлить можно на 2
        assertEquals(2, RentRules.maxExtendDays(NOW, NOW + 12 * DAY, DAY, 14));
    }

    @Test
    void fractionalRemainingRoundsDown() {
        assertEquals(1, RentRules.maxExtendDays(NOW, NOW + 12 * DAY + DAY / 2, DAY, 14));
        assertEquals(0, RentRules.maxExtendDays(NOW, NOW + 13 * DAY + DAY / 2, DAY, 14));
    }

    @Test
    void fullRentLeavesNoRoom() {
        assertEquals(0, RentRules.maxExtendDays(NOW, NOW + 14 * DAY, DAY, 14));
        assertEquals(0, RentRules.maxExtendDays(NOW, NOW + 20 * DAY, DAY, 14));
    }

    @Test
    void expiredRentAllowsFullPeriod() {
        assertEquals(14, RentRules.maxExtendDays(NOW, NOW - 5 * DAY, DAY, 14));
        assertEquals(14, RentRules.maxExtendDays(NOW, NOW, DAY, 14));
    }

    @Test
    void remainingNeverNegative() {
        assertEquals(0, RentRules.remaining(NOW, NOW - 1));
        assertEquals(5, RentRules.remaining(NOW, NOW + 5));
    }

    @Test
    void newRentDaysBounds() {
        assertTrue(RentRules.validNewRent(1, 14));
        assertTrue(RentRules.validNewRent(14, 14));
        assertFalse(RentRules.validNewRent(0, 14));
        assertFalse(RentRules.validNewRent(15, 14));
        assertFalse(RentRules.validNewRent(-3, 14));
    }

    @Test
    void costIsDailyPriceTimesDays() {
        assertEquals(700.0, RentRules.cost(100.0, 7), 1e-9);
        assertEquals(1400.0, RentRules.cost(100.0, 14), 1e-9);
        assertEquals(0.0, RentRules.cost(0, 5), 1e-9);
    }

    @Test
    void rentPriceLimit() {
        assertTrue(RentRules.rentPriceAllowed(100, 100));
        assertFalse(RentRules.rentPriceAllowed(100.01, 100));
        assertTrue(RentRules.rentPriceAllowed(1_000_000, 0), "0 - без лимита");
    }
}
