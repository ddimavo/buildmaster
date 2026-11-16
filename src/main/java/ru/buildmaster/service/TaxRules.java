package ru.buildmaster.service;

/** Правила комиссий и налогов. Чистая логика. */
public final class TaxRules {

    private TaxRules() { }

    private static double clamp(double percent) {
        return Math.min(100, Math.max(0, percent));
    }

    /** Размер комиссии или налога от суммы в процентах. */
    public static double percentOf(double amount, double percent) {
        if (!(amount > 0)) {
            return 0;
        }
        return amount * clamp(percent) / 100.0;
    }

    /** Сколько налоговых периодов наступило (не больше cap, чтобы после долгого простоя не списать всё разом). */
    public static int periodsDue(long now, long nextTaxAt, long periodMillis, int cap) {
        if (nextTaxAt <= 0 || now < nextTaxAt || periodMillis <= 0) {
            return 0;
        }
        long n = 1 + (now - nextTaxAt) / periodMillis;
        return (int) Math.min(n, Math.max(1, cap));
    }

    /** Следующее начисление после того, как закрыто n периодов. */
    public static long advance(long nextTaxAt, long periodMillis, int periods) {
        return nextTaxAt + periodMillis * periods;
    }
}
