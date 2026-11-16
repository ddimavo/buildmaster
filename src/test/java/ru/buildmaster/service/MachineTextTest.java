package ru.buildmaster.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MachineTextTest {

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    void barShowsTheRatioInTenSteps() {
        assertTrue(MachineText.bar(0.5).contains("▮▮▮▮▮") && MachineText.bar(0.5).contains("50%"));
        assertTrue(MachineText.bar(0).contains("0%"));
        assertTrue(MachineText.bar(1).contains("100%"));
        assertTrue(MachineText.bar(5).contains("100%"), "значение ограничено");
        assertTrue(MachineText.bar(-1).contains("0%"));
    }

    @Test
    void aWorkingFurnaceShowsProgressMaterialsAndPower() {
        String text = join(MachineText.furnace("Печь", true, null, true, 100, 200, "железо", 12, "уголь", 3, "слиток", 5,
                "Мощность: 1.0 V/сут"));
        assertTrue(text.contains("Печь"));
        assertTrue(text.contains("работает"));
        assertTrue(text.contains("50%"));
        assertTrue(text.contains("железо × 12"));
        assertTrue(text.contains("уголь × 3"));
        assertTrue(text.contains("слиток × 5"));
        assertTrue(text.contains("Мощность"));
    }

    @Test
    void anIdleFurnaceShowsEmptySlotsAndNoProgress() {
        String text = join(MachineText.furnace("Печь", true, null, false, 0, 0, null, 0, null, 0, null, 0, null));
        assertTrue(text.contains("простаивает"));
        assertFalse(text.contains("%"));
        assertEquals(3, text.split("пусто", -1).length - 1);
    }

    @Test
    void anUnpoweredMachineSaysWhy() {
        String text = join(MachineText.furnace("Печь", false, "не подключено к электростанции", false, 0, 0, null, 0,
                null, 0, null, 0, null));
        assertTrue(text.contains("обесточен"));
        assertTrue(text.contains("не подключено к электростанции"));
    }

    @Test
    void brewingShowsProgressAndFuel() {
        String text = join(MachineText.brewing("Варочная стойка", true, null, 100, 400, "бородавка", 2, 15, "Операция: 2.5"));
        assertTrue(text.contains("работает"));
        assertTrue(text.contains("75%"));
        assertTrue(text.contains("бородавка × 2"));
        assertTrue(text.contains("15"));
    }

    @Test
    void anAnvilShowsOperationsAndSpent() {
        String text = join(MachineText.simple("Наковальня", true, null, 14, "70", "Мощность: 1.5 V/сут"));
        assertTrue(text.contains("готов к работе"));
        assertTrue(text.contains("14"));
        assertTrue(text.contains("70"));
    }
}
