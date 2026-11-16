package ru.buildmaster.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import ru.buildmaster.service.StationLimits;

class StationAndLimitsTest {

    @Test
    void recognisesStationsByMaterialName() {
        assertEquals(Station.CRAFTING, Station.fromName("CRAFTING_TABLE"));
        assertEquals(Station.FURNACE, Station.fromName("FURNACE"));
        assertEquals(Station.FURNACE, Station.fromName("BLAST_FURNACE"));
        assertEquals(Station.FURNACE, Station.fromName("SMOKER"));
        assertEquals(Station.ANVIL, Station.fromName("CHIPPED_ANVIL"));
        assertEquals(Station.ANVIL, Station.fromName("DAMAGED_ANVIL"));
        assertEquals(Station.ALCHEMY, Station.fromName("BREWING_STAND"));
        assertEquals(Station.ENCHANTING, Station.fromName("ENCHANTING_TABLE"));
        assertEquals(Station.OTHER, Station.fromName("GRINDSTONE"));
        assertEquals(Station.OTHER, Station.fromName("LOOM"));
        assertNull(Station.fromName("STONE"));
        assertNull(Station.fromName("CHEST"));
    }

    @Test
    void allFurnacesShareOneLimitGroupAndOtherStationsAnother() {
        assertEquals("furnace", Station.fromName("SMOKER").limitKey());
        assertEquals(Station.FURNACE.limitKey(), Station.fromName("BLAST_FURNACE").limitKey());
        assertEquals(Station.ALCHEMY.limitKey(), Station.ENCHANTING.limitKey());
        assertEquals(Station.OTHER.limitKey(), Station.ALCHEMY.limitKey());
    }

    @Test
    void onlyPaidStationsHaveTariffKeys() {
        assertEquals("smelter", Station.FURNACE.tariffKey());
        assertEquals("anvil", Station.ANVIL.tariffKey());
        assertEquals("alchemy", Station.ALCHEMY.tariffKey());
        assertEquals("enchanting", Station.ENCHANTING.tariffKey());
        assertNull(Station.CRAFTING.tariffKey());
        assertNull(Station.OTHER.tariffKey());
    }

    @Test
    void unlimitedAllowsAnything() {
        assertTrue(StationLimits.check(Category.HOUSE, Station.ANVIL, 99, -1).allowed());
    }

    @Test
    void zeroLimitForbidsAndSuggestsWorkshop() {
        var r = StationLimits.check(Category.SHOP, Station.ENCHANTING, 0, 0);
        assertFalse(r.allowed());
        assertTrue(r.message().contains("Магазин"));
        assertTrue(r.message().contains("стол зачарований"));
        assertTrue(r.message().contains("мастерская"));
    }

    @Test
    void limitOfOneAllowsFirstAndRefusesSecond() {
        assertTrue(StationLimits.check(Category.SHOP, Station.ANVIL, 0, 1).allowed());
        var r = StationLimits.check(Category.SHOP, Station.ANVIL, 1, 1);
        assertFalse(r.allowed());
        assertTrue(r.message().contains("не больше 1"));
    }

    @Test
    void permParsing() {
        assertEquals(Plot.Perm.BUILD, Plot.Perm.parse("build"));
        assertEquals(Plot.Perm.CONTAINERS, Plot.Perm.parse("Контейнеры"));
        assertEquals(Plot.Perm.STATIONS, Plot.Perm.parse(" STATIONS "));
        assertNull(Plot.Perm.parse("fly"));
        assertNull(Plot.Perm.parse(null));
    }
}
