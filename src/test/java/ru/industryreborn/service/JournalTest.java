package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Test
    void recordsAndFiltersByPlayerNewestFirst(@TempDir File dir) {
        Journal j = new Journal(new File(dir, "journal.log"));
        j.add("shop-1", ALICE, "Alice", Journal.Action.RENTED, "7 сут.");
        j.add("shop-2", BOB, "Bob", Journal.Action.BOUGHT, "");
        j.add("shop-1", ALICE, "Админ", Journal.Action.EVICTED, "нарушение правил");

        List<Journal.Entry> a = j.forPlayer(ALICE);
        assertEquals(2, a.size());
        assertEquals(Journal.Action.EVICTED, a.get(0).action());
        assertEquals("нарушение правил", a.get(0).reason());
        assertEquals(1, j.forPlayer(BOB).size());
        assertEquals(2, j.forPlot("SHOP-1").size(), "id участка без учёта регистра");
    }

    @Test
    void survivesRestartAndKeepsReasonsIntact(@TempDir File dir) throws IOException {
        File file = new File(dir, "journal.log");
        Journal j = new Journal(file);
        String tricky = "Причина с\tтабуляцией\nи переносом, а также \\ слэшем";
        j.add("p1", ALICE, "Админ", Journal.Action.SEIZED, tricky);
        j.add("p1", null, "система", Journal.Action.CREATED, "Магазин");

        Journal reloaded = new Journal(file);
        reloaded.load();
        List<Journal.Entry> entries = reloaded.forPlot("p1");
        assertEquals(2, entries.size());
        assertEquals("Магазин", entries.get(0).reason());
        assertNull(entries.get(0).subject());
        assertEquals(tricky, entries.get(1).reason());
        assertEquals(ALICE, entries.get(1).subject());
    }

    @Test
    void corruptedLinesAreSkipped(@TempDir File dir) throws IOException {
        File file = new File(dir, "journal.log");
        Journal j = new Journal(file);
        j.add("p1", ALICE, "Админ", Journal.Action.RESET, "ок");
        Files.writeString(file.toPath(), "мусор без табов\nещё\tмусор\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        Journal reloaded = new Journal(file);
        reloaded.load();
        assertEquals(1, reloaded.forPlayer(ALICE).size());
    }

    @Test
    void nullReasonBecomesEmpty(@TempDir File dir) {
        Journal j = new Journal(new File(dir, "journal.log"));
        Journal.Entry e = j.add("p", ALICE, "x", Journal.Action.BOUGHT, null);
        assertEquals("", e.reason());
    }
}
