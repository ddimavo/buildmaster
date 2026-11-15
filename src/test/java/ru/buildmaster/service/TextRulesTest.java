package ru.buildmaster.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextRulesTest {

    @Test
    void collapsesWhitespace() {
        assertEquals("Лавка древностей", TextRules.clean("  Лавка \t  древностей \n", 32, "Название"));
    }

    @Test
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> TextRules.clean("   ", 32, "Название"));
        assertThrows(IllegalArgumentException.class, () -> TextRules.clean(null, 32, "Название"));
    }

    @Test
    void rejectsTooLong() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TextRules.clean("а".repeat(33), 32, "Название"));
        assertTrue(e.getMessage().contains("32"));
        assertEquals(32, TextRules.clean("а".repeat(32), 32, "Название").length());
    }

    @Test
    void rejectsFormattingAndControlCharacters() {
        for (String bad : new String[] {"<red>Лавка", "Лавка>", "§cЛавка", "Лавка &c", "Лавка\u0007"}) {
            assertThrows(IllegalArgumentException.class, () -> TextRules.clean(bad, 32, "Название"), bad);
        }
    }

    @Test
    void wrapsOnWordBoundaries() {
        List<String> lines = TextRules.wrap("самые свежие овощи и фрукты прямо с грядки", 16);
        assertEquals(List.of("самые свежие", "овощи и фрукты", "прямо с грядки"), lines);
        for (String l : lines) {
            assertTrue(l.length() <= 16);
        }
    }

    @Test
    void wrapSplitsVeryLongWords() {
        List<String> lines = TextRules.wrap("аааааааааааааааааааа", 8);
        assertEquals(List.of("аааааааа", "аааааааа", "аааа"), lines);
    }
}
