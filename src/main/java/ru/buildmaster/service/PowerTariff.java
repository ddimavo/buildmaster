package ru.buildmaster.service;

import java.util.Map;
import java.util.function.Function;

/** Формула суточного тарифа на электричество. Чистая логика. */
public final class PowerTariff {

    private PowerTariff() { }

    /**
     * тариф = база × Σ по типам [ коэффициент × Σ по станкам этого типа (1 + прогрессия × k) ] × (1 − скидка),
     * где k = 0, 1, 2... - номер такого же станка по счёту.
     *
     * @param counts          тип станка (ключ тарифа) -> количество
     * @param coefficient     коэффициент типа; null считается 0
     * @param discountPercent скидка в процентах (0-100)
     */
    public static double dailyRate(double base, double progression, Map<String, Integer> counts,
                                   Function<String, Double> coefficient, double discountPercent) {
        double sum = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int n = e.getValue() == null ? 0 : e.getValue();
            if (n <= 0) {
                continue;
            }
            Double c = coefficient.apply(e.getKey());
            if (c == null) {
                continue;
            }
            sum += c * (n + progression * n * (n - 1) / 2.0);
        }
        double discount = Math.min(100, Math.max(0, discountPercent));
        return base * sum * (1 - discount / 100.0);
    }
}
