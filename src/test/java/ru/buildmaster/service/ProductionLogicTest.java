package ru.buildmaster.service;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.buildmaster.model.Plot;

class ProductionLogicTest {

    // ------------------------------------------------------------ расчёт партий

    @Test
    void batchTimeShrinksWithStationsAndSpeed() {
        assertEquals(10, ProductionMath.secondsPerBatch(10, 1, 1.0, 1.0), 1e-9);
        assertEquals(5, ProductionMath.secondsPerBatch(10, 2, 1.0, 1.0), 1e-9);
        assertEquals(4, ProductionMath.secondsPerBatch(10, 2, 1.25, 1.0), 1e-9);
        assertEquals(5, ProductionMath.secondsPerBatch(10, 1, 1.0, 0.5), 1e-9);
    }

    @Test
    void noStationOrZeroSpeedMeansNoProduction() {
        assertTrue(Double.isInfinite(ProductionMath.secondsPerBatch(10, 0, 1.0, 1.0)));
        assertTrue(Double.isInfinite(ProductionMath.secondsPerBatch(10, 2, 0, 1.0)));
        assertEquals(0, ProductionMath.batches(1000, Double.POSITIVE_INFINITY, 100, 1, 100, 1));
    }

    @Test
    void batchesAreLimitedByTimeInputAndSpace() {
        // времени хватит на 10 партий
        assertEquals(10, ProductionMath.batches(50, 5, 100, 1, 100, 1));
        // сырья только на 3
        assertEquals(3, ProductionMath.batches(50, 5, 3, 1, 100, 1));
        // место в буфере только на 4
        assertEquals(4, ProductionMath.batches(50, 5, 100, 1, 4, 1));
    }

    @Test
    void multiItemRecipesRoundDown() {
        // 9 слитков -> 1 блок: из 20 слитков две партии
        assertEquals(2, ProductionMath.batches(1000, 1, 20, 9, 100, 1));
        // выход 3 за партию, места на 7 предметов: две партии
        assertEquals(2, ProductionMath.batches(1000, 1, 100, 1, 7, 3));
    }

    @Test
    void nothingIsProducedFromNothing() {
        assertEquals(0, ProductionMath.batches(1000, 1, 0, 1, 100, 1));
        assertEquals(0, ProductionMath.batches(1000, 1, 8, 9, 100, 1), "сырья меньше одной партии");
        assertEquals(0, ProductionMath.batches(1000, 1, 100, 1, 0, 1), "буфер полон");
        assertEquals(0, ProductionMath.batches(1000, 1, 100, 1, -5, 1));
        assertEquals(0, ProductionMath.batches(0, 5, 100, 1, 100, 1));
    }

    @Test
    void zeroDurationIsOnlyLimitedByResources() {
        assertEquals(7, ProductionMath.batches(0, 0, 7, 1, 100, 1));
    }

    @Test
    void capacityScalesButNeverShrinks() {
        assertEquals(256, ProductionMath.capacity(256, 1.0));
        assertEquals(512, ProductionMath.capacity(256, 2.0));
        assertEquals(384, ProductionMath.capacity(256, 1.5));
        assertEquals(256, ProductionMath.capacity(256, 0.2), "улучшения не уменьшают вместимость");
    }

    // ------------------------------------------------------------ уровни улучшений

    private static UpgradeCatalog catalog(String yaml) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(yaml);
        } catch (Exception e) {
            fail(e);
        }
        return new UpgradeCatalog(() -> cfg);
    }

    private static final String SAMPLE = """
            upgrades:
              base: {max-lines: 1, speed: 1.0, stock: 1.0, buffer: 1.0, order-queue: 0}
              levels:
                1: {price: 3000, max-lines: 2, speed: 1.25, stock: 2.0, buffer: 2.0, order-queue: 10}
                3: {price: 20000, max-lines: 5, speed: 2.0}
                2: {price: 8000, max-lines: 3, speed: 1.5, stock: 4.0}
            """;

    @Test
    void levelsAreSortedAndInheritMissingValues() {
        UpgradeCatalog c = catalog(SAMPLE);
        var levels = c.levels();
        assertEquals(3, levels.size());
        assertEquals(1, levels.get(0).level());
        assertEquals(2, levels.get(1).level());
        assertEquals(3, levels.get(2).level());
        assertEquals(2.0, levels.get(0).buffer(), 1e-9);
        assertEquals(2.0, levels.get(1).buffer(), 1e-9, "буфер не задан - унаследован от уровня 1");
        assertEquals(4.0, levels.get(2).stock(), 1e-9, "склад унаследован от уровня 2");
        assertEquals(10, levels.get(2).orderQueue());
        assertEquals(3, c.maxLevel());
    }

    @Test
    void currentLevelEffectsAndTheNextLevel() {
        UpgradeCatalog c = catalog(SAMPLE);
        Plot plot = new Plot("w", "w", new ru.buildmaster.model.Box(0, 0, 0, 1, 1, 1));
        assertEquals(1, c.of(plot).maxLines());
        assertEquals(1.0, c.of(plot).speed(), 1e-9);
        assertEquals(1, c.next(plot).level());
        assertEquals(3000, c.next(plot).price(), 1e-9);

        plot.level = 2;
        assertEquals(3, c.of(plot).maxLines());
        assertEquals(1.5, c.of(plot).speed(), 1e-9);
        assertEquals(3, c.next(plot).level());

        plot.level = 3;
        assertNull(c.next(plot), "максимум достигнут");
        plot.level = 99;
        assertEquals(5, c.of(plot).maxLines(), "уровень выше максимального приравнивается к максимальному");
    }

    @Test
    void withoutConfigThereAreOnlyBaseEffects() {
        UpgradeCatalog c = catalog("other: 1");
        assertTrue(c.levels().isEmpty());
        assertEquals(0, c.maxLevel());
        assertEquals(1, c.base().maxLines());
        assertEquals(1.0, c.base().speed(), 1e-9);
        assertNull(c.next(new Plot("w", "w", new ru.buildmaster.model.Box(0, 0, 0, 1, 1, 1))));
    }

    @Test
    void productionLinesAreReadFromTheConfigAndBrokenOnesSkipped() {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString("""
                    production:
                      lines:
                        ok: {title: Железо, station: smelter, input: IRON_ORE, output: IRON_INGOT, seconds: 12, install-price: 100}
                        broken: {input: NOT_A_BLOCK, output: IRON_INGOT}
                        multi: {station: anvil, input: IRON_INGOT, input-amount: 9, output: IRON_BLOCK}
                    """);
        } catch (Exception e) {
            fail(e);
        }
        ProductionCatalog p = new ProductionCatalog(() -> cfg);
        assertEquals(2, p.lines().size());
        assertEquals(12, p.get("ok").seconds(), 1e-9);
        assertEquals(100, p.get("OK").installPrice(), 1e-9, "id без учёта регистра");
        assertEquals(9, p.get("multi").inputAmount());
        assertNull(p.get("broken"));
    }
}
