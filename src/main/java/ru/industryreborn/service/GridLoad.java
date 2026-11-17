package ru.industryreborn.service;

/** Нагрузка на электростанцию и плавная надбавка к тарифу при перегрузке. Чистая логика. */
public final class GridLoad {

    private GridLoad() { }

    /** Нагрузка: потребление / мощность. Нулевая мощность при наличии потребителей - бесконечность. */
    public static double ratio(double demand, double capacity) {
        if (capacity <= 0) {
            return demand > 0 ? Double.POSITIVE_INFINITY : 0;
        }
        return demand / capacity;
    }

    public static boolean overloaded(double demand, double capacity) {
        return demand > capacity + 1e-9;
    }

    /**
     * Следующий множитель тарифа станции: при перегрузке растёт на step (не выше max),
     * иначе так же понемногу снижается до 1.0.
     */
    public static double nextMultiplier(double current, boolean overloaded, double step, double max) {
        double cap = Math.max(1.0, max);
        double next = overloaded ? current + step : current - step;
        next = Math.min(cap, Math.max(1.0, next));
        // убираем накопление погрешности: 1.0999999 -> 1.1
        return Math.round(next * 10_000.0) / 10_000.0;
    }
}
