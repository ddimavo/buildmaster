package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PowerOverridesAndStatsTest {

    // ------------------------------------------------------------ переопределения

    @Test
    void nothingIsOverriddenByDefault() {
        PowerOverrides o = new PowerOverrides();
        assertNull(o.base());
        assertNull(o.progression());
        assertNull(o.usageBase());
        assertNull(o.mode());
        assertNull(o.coefficient("smelter"));
        assertTrue(o.coefficients().isEmpty());
    }

    @Test
    void coefficientCanBeSetAndReset() {
        PowerOverrides o = new PowerOverrides();
        o.setCoefficient("smelter", 2.5);
        assertEquals(2.5, o.coefficient("smelter"), 1e-9);
        assertTrue(o.resetCoefficient("smelter"));
        assertNull(o.coefficient("smelter"));
        assertFalse(o.resetCoefficient("smelter"), "повторный сброс - переопределения уже нет");
    }

    @Test
    void persistsEverythingIncludingResets(@TempDir File dir) throws IOException {
        File file = new File(dir, "overrides.yml");
        PowerOverrides o = new PowerOverrides();
        o.setBase(250.0);
        o.setProgression(0.0);
        o.setUsageBase(2.0);
        o.setMode("USAGE");
        o.setCoefficient("anvil", 3.0);
        o.setCoefficient("alchemy", 0.0);
        o.save(file);

        PowerOverrides loaded = PowerOverrides.load(file);
        assertEquals(250.0, loaded.base(), 1e-9);
        assertEquals(0.0, loaded.progression(), 1e-9, "ноль - это значение, а не отсутствие");
        assertEquals(2.0, loaded.usageBase(), 1e-9);
        assertEquals("USAGE", loaded.mode());
        assertEquals(3.0, loaded.coefficient("anvil"), 1e-9);
        assertEquals(0.0, loaded.coefficient("alchemy"), 1e-9);

        loaded.setBase(null);
        loaded.setMode(null);
        loaded.resetCoefficient("anvil");
        loaded.save(file);
        PowerOverrides again = PowerOverrides.load(file);
        assertNull(again.base());
        assertNull(again.mode());
        assertNull(again.coefficient("anvil"));
        assertEquals(0.0, again.coefficient("alchemy"), 1e-9);
    }

    @Test
    void missingFileMeansNoOverrides(@TempDir File dir) {
        PowerOverrides o = PowerOverrides.load(new File(dir, "nope.yml"));
        assertNull(o.base());
    }

    // ------------------------------------------------------------ статистика

    @Test
    void countsOperationsAndMoneyByPlotAndType() {
        StationStats s = new StationStats();
        s.record("w1", "smelter", 1.0);
        s.record("w1", "smelter", 1.0);
        s.record("w1", "anvil", 1.5);
        s.record("W1", "anvil", 0);   // id без учёта регистра

        Map<String, StationStats.Entry> byType = s.byType("w1");
        assertEquals(2, byType.get("smelter").operations());
        assertEquals(2.0, byType.get("smelter").spent(), 1e-9);
        assertEquals(2, byType.get("anvil").operations());
        assertEquals(1.5, byType.get("anvil").spent(), 1e-9);
        assertEquals(4, s.total("w1").operations());
        assertEquals(3.5, s.total("w1").spent(), 1e-9);
        assertEquals(0, s.total("other").operations());
    }

    @Test
    void totalsAndTopAcrossPlots() {
        StationStats s = new StationStats();
        for (int i = 0; i < 5; i++) {
            s.record("busy", "smelter", 1);
        }
        s.record("quiet", "smelter", 1);
        s.record("quiet", "enchanting", 4);

        assertEquals(6, s.totalsByType().get("smelter").operations());
        assertEquals(1, s.totalsByType().get("enchanting").operations());
        List<Map.Entry<String, StationStats.Entry>> top = s.top(1);
        assertEquals(1, top.size());
        assertEquals("busy", top.get(0).getKey());
        assertEquals(2, s.top(10).size());
    }

    @Test
    void dirtyFlagAndPersistence(@TempDir File dir) throws IOException {
        File file = new File(dir, "stats.yml");
        StationStats s = new StationStats();
        assertFalse(s.isDirty());
        s.record("w1", "smelter", 2.5);
        assertTrue(s.isDirty());
        s.save(file);
        assertFalse(s.isDirty());

        StationStats loaded = StationStats.load(file);
        assertEquals(1, loaded.byType("w1").get("smelter").operations());
        assertEquals(2.5, loaded.byType("w1").get("smelter").spent(), 1e-9);
    }

    @Test
    void clearForgetsAPlot() {
        StationStats s = new StationStats();
        s.record("w1", "anvil", 1);
        s.clear("w1");
        assertEquals(0, s.total("w1").operations());
        assertTrue(s.byType("w1").isEmpty());
    }
}
