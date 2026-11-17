package ru.industryreborn.service;

/** Предел цены аренды участка игрока за сутки: растёт с размером региона, но ограничен снизу и сверху. Чистая логика. */
public final class RentCap {

    private RentCap() { }

    /**
     * @param volume       объём региона в блоках
     * @param perThousand  допустимая цена за каждые 1000 блоков в сутки
     * @param minCap       минимальный предел (чтобы маленькие участки можно было сдавать)
     * @param maxCap       абсолютный предел; 0 - без верхнего предела
     */
    public static double compute(long volume, double perThousand, double minCap, double maxCap) {
        double cap = Math.max(0, volume) / 1000.0 * Math.max(0, perThousand);
        cap = Math.max(cap, Math.max(0, minCap));
        if (maxCap > 0) {
            cap = Math.min(cap, maxCap);
        }
        return Math.round(cap * 100.0) / 100.0;
    }
}
