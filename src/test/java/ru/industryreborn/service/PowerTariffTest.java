package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PowerTariffTest {

    private static final Map<String, Double> COEF = Map.of("smelter", 1.0, "anvil", 1.5, "alchemy", 2.5, "enchanting", 4.0);

    private static double rate(double base, double prog, Map<String, Integer> counts, double discount) {
        return PowerTariff.dailyRate(base, prog, counts, COEF::get, discount);
    }

    @Test
    void noStationsMeansZeroTariff() {
        assertEquals(0, rate(100, 0.1, Map.of(), 0), 1e-9);
    }

    @Test
    void exampleFromThePlan() {
        // база 100, прогрессия 0.1, две плавильни и одна наковальня = 100 × (1.0 + 1.1 + 1.5) = 360
        assertEquals(360, rate(100, 0.1, Map.of("smelter", 2, "anvil", 1), 0), 1e-9);
    }

    @Test
    void singleStationCostsBaseTimesCoefficient() {
        assertEquals(100, rate(100, 0.1, Map.of("smelter", 1), 0), 1e-9);
        assertEquals(400, rate(100, 0.1, Map.of("enchanting", 1), 0), 1e-9);
    }

    @Test
    void progressionMakesMoreStationsOfOneTypeExpensive() {
        // 3 печи: 1.0 + 1.1 + 1.2 = 3.3
        assertEquals(330, rate(100, 0.1, Map.of("smelter", 3), 0), 1e-9);
        // без прогрессии десять печей стоят ровно в десять раз больше
        assertEquals(1000, rate(100, 0, Map.of("smelter", 10), 0), 1e-9);
        assertTrue(rate(100, 0.1, Map.of("smelter", 10), 0) > 1000);
    }

    @Test
    void discountReducesTariff() {
        assertEquals(180, rate(100, 0.1, Map.of("smelter", 2, "anvil", 1), 50), 1e-9);
        assertEquals(0, rate(100, 0.1, Map.of("smelter", 1), 100), 1e-9);
        assertEquals(0, rate(100, 0.1, Map.of("smelter", 1), 150), 1e-9, "скидка не больше 100%");
        assertEquals(100, rate(100, 0.1, Map.of("smelter", 1), -20), 1e-9, "отрицательная скидка игнорируется");
    }

    @Test
    void unknownTypesAndNonPositiveCountsAreIgnored() {
        assertEquals(100, rate(100, 0.1, Map.of("smelter", 1, "laser", 5, "anvil", 0), 0), 1e-9);
    }
}
