package ru.buildmaster.service;

/** Расчёт выполнения заказов (чистая логика): время, число готовых единиц, остаток. */
public final class OrderProgress {

    private OrderProgress() { }

    /**
     * Время на одну единицу работы с учётом числа станков: несколько станков работают параллельно.
     * Без станков работа невозможна (бесконечность).
     */
    public static double secondsPerUnit(double baseSeconds, int stations, double timeFactor) {
        if (stations <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0, baseSeconds) * Math.max(0, timeFactor) / stations;
    }

    /** Сколько единиц готово за накопленное время (не больше общего числа). */
    public static int unitsReady(double progressSeconds, double secondsPerUnit, int total) {
        if (Double.isInfinite(secondsPerUnit)) {
            return 0;
        }
        if (secondsPerUnit <= 0) {
            return total;
        }
        return (int) Math.min(total, Math.floor(progressSeconds / secondsPerUnit + 1e-9));
    }

    /** Сколько секунд осталось до конца заказа. Бесконечность, если станков нет. */
    public static double remainingSeconds(int total, double progressSeconds, double secondsPerUnit) {
        if (Double.isInfinite(secondsPerUnit)) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0, total * secondsPerUnit - progressSeconds);
    }
}
