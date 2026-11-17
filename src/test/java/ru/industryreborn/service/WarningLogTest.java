package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WarningLogTest {

    private static final UUID ME = UUID.randomUUID();

    @Test
    void newWarningsAreUnreadAndNewestFirst(@TempDir File dir) {
        WarningLog log = new WarningLog(dir);
        log.add(ME, "a", "первое");
        log.add(ME, "b", "второе");
        assertEquals(2, log.unread(ME));
        assertEquals("второе", log.forPlayer(ME).get(0).text);
        assertEquals("первое", log.forPlayer(ME).get(1).text);
    }

    @Test
    void markAllReadClearsCounter(@TempDir File dir) {
        WarningLog log = new WarningLog(dir);
        log.add(ME, "a", "x");
        log.markAllRead(ME);
        assertEquals(0, log.unread(ME));
        assertTrue(log.forPlayer(ME).get(0).read);
    }

    @Test
    void persistsIncludingSeparatorsAndReadState(@TempDir File dir) {
        WarningLog log = new WarningLog(dir);
        log.add(ME, "a|b", "текст | с разделителем <red>цвет");
        log.markAllRead(ME);
        log.add(ME, "c", "ещё");

        WarningLog fresh = new WarningLog(dir);
        assertEquals(2, fresh.forPlayer(ME).size());
        assertEquals(1, fresh.unread(ME));
        var old = fresh.forPlayer(ME).get(1);
        assertEquals("a|b", old.plotId);
        assertEquals("текст | с разделителем <red>цвет", old.text);
        assertTrue(old.read);
    }

    @Test
    void keepsOnlyTheLastHundred(@TempDir File dir) {
        WarningLog log = new WarningLog(dir);
        for (int i = 0; i < 130; i++) {
            log.add(ME, "p", "w" + i);
        }
        assertEquals(100, log.forPlayer(ME).size());
        assertEquals("w129", log.forPlayer(ME).get(0).text);
        assertEquals("w30", log.forPlayer(ME).get(99).text);
    }

    @Test
    void playersAreIndependent(@TempDir File dir) {
        WarningLog log = new WarningLog(dir);
        log.add(ME, "a", "x");
        assertEquals(0, log.unread(UUID.randomUUID()));
    }
}
