package ru.industryreborn.model;

/**
 * Денежный счёт предприятия с ленивым списанием электричества: тиков нет,
 * расход вычисляется по прошедшему «биллинговому» времени.
 * <p>
 * Порядок работы: {@link #settle(long)} - затем изменение (внесение, снятие, смена тарифа).
 */
public final class PowerAccount {

    private static final double DAY_SECONDS = 86_400.0;

    private double balance;
    private double ratePerDay;
    private long updatedAtSec;
    /** Потребление в единицах V в сутки (сумма коэффициентов станков с учётом прогрессии). */
    private double unitsPerDay;

    public double rawBalance() {
        return balance;
    }

    public double ratePerDay() {
        return ratePerDay;
    }

    public long updatedAtSec() {
        return updatedAtSec;
    }

    public double unitsPerDay() {
        return unitsPerDay;
    }

    public void setUnitsPerDay(double units) {
        this.unitsPerDay = Math.max(0, units);
    }

    /** Восстановление из файла. */
    public void restore(double balance, double ratePerDay, long updatedAtSec) {
        this.balance = Math.max(0, balance);
        this.ratePerDay = Math.max(0, ratePerDay);
        this.updatedAtSec = updatedAtSec;
    }

    /** Баланс на момент nowSec без изменения состояния. */
    public double balanceAt(long nowSec) {
        if (ratePerDay <= 0) {
            return balance;
        }
        long elapsed = Math.max(0, nowSec - updatedAtSec);
        return Math.max(0, balance - ratePerDay * elapsed / DAY_SECONDS);
    }

    /** Фиксирует расход до nowSec. Возвращает потраченную сумму. */
    public double settle(long nowSec) {
        double before = balance;
        balance = balanceAt(nowSec);
        updatedAtSec = Math.max(updatedAtSec, nowSec);
        return before - balance;
    }

    /** Новый тариф (в сутки). Вызывать после {@link #settle(long)}. */
    public void setRate(double newRatePerDay) {
        ratePerDay = Math.max(0, newRatePerDay);
    }

    public void deposit(double amount) {
        if (amount > 0) {
            balance += amount;
        }
    }

    /** false - на счёте недостаточно средств. */
    public boolean withdraw(double amount) {
        if (amount <= 0 || amount > balance + 1e-9) {
            return false;
        }
        balance = Math.max(0, balance - amount);
        return true;
    }

    /** Через сколько секунд счёт опустеет; Long.MAX_VALUE - тариф нулевой. */
    public long secondsLeft(long nowSec) {
        if (ratePerDay <= 0) {
            return Long.MAX_VALUE;
        }
        return (long) (balanceAt(nowSec) / ratePerDay * DAY_SECONDS);
    }

    /** Есть ли электричество: нулевой тариф или положительный остаток. */
    public boolean powered(long nowSec) {
        return ratePerDay <= 0 || balanceAt(nowSec) > 1e-9;
    }
}
