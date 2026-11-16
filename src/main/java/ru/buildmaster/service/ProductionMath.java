package ru.buildmaster.service;

/** Расчёт производственной линии (чистая логика). */
public final class ProductionMath {

    private ProductionMath() { }

    /** Время одной партии на линии: время / число станков / скорость улучшений; бесконечность без станков. */
    public static double secondsPerBatch(double baseSeconds, int stations, double speed, double timeFactor) {
        if (stations <= 0 || speed <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0, baseSeconds) * Math.max(0, timeFactor) / stations / speed;
    }

    /**
     * Сколько партий можно сделать сейчас: ограничено накопленным временем, запасом сырья и свободным местом в буфере.
     */
    public static int batches(double progressSeconds, double secondsPerBatch, int inputStock, int inputPerBatch,
                              int bufferFree, int outputPerBatch) {
        if (Double.isInfinite(secondsPerBatch) || inputPerBatch <= 0 || outputPerBatch <= 0) {
            return 0;
        }
        int byTime = secondsPerBatch <= 0 ? Integer.MAX_VALUE : (int) Math.floor(progressSeconds / secondsPerBatch + 1e-9);
        int byInput = inputStock / inputPerBatch;
        int bySpace = Math.max(0, bufferFree) / outputPerBatch;
        return Math.max(0, Math.min(byTime, Math.min(byInput, bySpace)));
    }

    /** Вместимость запаса или буфера с учётом множителя улучшений (округление вниз, не меньше базы). */
    public static int capacity(int base, double multiplier) {
        return (int) Math.max(base, Math.floor(base * Math.max(1.0, multiplier) + 1e-9));
    }
}
