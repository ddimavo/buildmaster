package ru.buildmaster.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PowerAccountTest {

    private static final long DAY = 86_400;

    private static PowerAccount account(double balance, double rate) {
        PowerAccount a = new PowerAccount();
        a.restore(balance, rate, 0);
        return a;
    }

    @Test
    void consumesProportionallyToTime() {
        PowerAccount a = account(360, 360);
        assertEquals(360, a.balanceAt(0), 1e-9);
        assertEquals(180, a.balanceAt(DAY / 2), 1e-9);
        assertEquals(0, a.balanceAt(DAY), 1e-9);
        assertEquals(0, a.balanceAt(5 * DAY), 1e-9, "в минус не уходит");
    }

    @Test
    void zeroTariffNeverConsumes() {
        PowerAccount a = account(100, 0);
        assertEquals(100, a.balanceAt(100 * DAY), 1e-9);
        assertTrue(a.powered(100 * DAY));
        assertEquals(Long.MAX_VALUE, a.secondsLeft(0));
    }

    @Test
    void secondsLeft() {
        PowerAccount a = account(720, 360);
        assertEquals(2 * DAY, a.secondsLeft(0));
        assertEquals(DAY, a.secondsLeft(DAY));
    }

    @Test
    void poweredNeedsPositiveBalanceWhenTariffIsSet() {
        PowerAccount a = account(0, 100);
        assertFalse(a.powered(0));
        a.deposit(10);
        assertTrue(a.powered(0));
    }

    @Test
    void settleReturnsConsumedAndFixesTheState() {
        PowerAccount a = account(360, 360);
        double consumed = a.settle(DAY / 4);
        assertEquals(90, consumed, 1e-9);
        assertEquals(270, a.rawBalance(), 1e-9);
        assertEquals(DAY / 4, a.updatedAtSec());
        // повторный расчёт на тот же момент ничего не списывает
        assertEquals(0, a.settle(DAY / 4), 1e-9);
    }

    @Test
    void settleCannotConsumeMoreThanTheBalance() {
        PowerAccount a = account(50, 360);
        assertEquals(50, a.settle(10 * DAY), 1e-9);
        assertEquals(0, a.rawBalance(), 1e-9);
    }

    @Test
    void rateChangeAppliesFromTheMomentOfChange() {
        PowerAccount a = account(1000, 360);
        a.settle(DAY / 2);            // 180 списано по старому тарифу
        a.setRate(720);
        assertEquals(820, a.rawBalance(), 1e-9);
        assertEquals(820 - 720.0 / 4, a.balanceAt(DAY / 2 + DAY / 4), 1e-9);
    }

    @Test
    void depositAndWithdraw() {
        PowerAccount a = account(100, 0);
        a.deposit(50);
        a.deposit(-5);
        a.deposit(0);
        assertEquals(150, a.rawBalance(), 1e-9);
        assertTrue(a.withdraw(150));
        assertFalse(a.withdraw(0.01));
        assertFalse(a.withdraw(-1));
        assertEquals(0, a.rawBalance(), 1e-9);
    }

    @Test
    void clockGoingBackwardsDoesNotCreateMoney() {
        PowerAccount a = account(100, 100);
        a.settle(1000);
        assertEquals(0, a.settle(500), 1e-9);
        assertTrue(a.balanceAt(200) <= 100);
    }

    @Test
    void restoreClampsNegativeValues() {
        PowerAccount a = new PowerAccount();
        a.restore(-5, -3, 7);
        assertEquals(0, a.rawBalance(), 1e-9);
        assertEquals(0, a.ratePerDay(), 1e-9);
    }
}
