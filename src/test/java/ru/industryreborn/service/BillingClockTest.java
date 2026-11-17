package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BillingClockTest {

    @Test
    void runningServerAccumulatesTime() {
        BillingClock c = new BillingClock(0, 1_000, 120_000);
        c.advance(21_000);
        c.advance(41_000);
        assertEquals(40, c.seconds());
    }

    @Test
    void downtimeIsNotBilled() {
        BillingClock c = new BillingClock(5_000, 0, 120_000);
        // сервер завис или был выключен на 10 часов, потом пришёл один тик
        c.advance(10 * 3_600_000L);
        assertEquals(5 + 120, c.seconds(), "за один шаг не больше 120 секунд");
    }

    @Test
    void restartStartsFromSavedValueWithoutCountingTheGap() {
        BillingClock before = new BillingClock(0, 0, 120_000);
        before.advance(60_000);
        long saved = before.millis();

        // перезапуск через сутки: часы создаются заново "сейчас"
        long restartAt = 86_400_000L;
        BillingClock after = new BillingClock(saved, restartAt, 120_000);
        after.advance(restartAt + 20_000);
        assertEquals(60 + 20, after.seconds());
    }

    @Test
    void wallClockGoingBackwardsIsIgnored() {
        BillingClock c = new BillingClock(0, 100_000, 120_000);
        c.advance(50_000);
        assertEquals(0, c.seconds());
        c.advance(70_000);
        assertEquals(20, c.seconds());
    }
}
