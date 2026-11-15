package ru.buildmaster.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreasuryTest {

    @Test
    void depositAndWithdraw() {
        Treasury t = new Treasury();
        t.deposit(500, "аренда");
        t.deposit(250.5, "покупка");
        assertEquals(750.5, t.balance(), 1e-9);
        assertTrue(t.withdraw(300, "admin", "приз"));
        assertEquals(450.5, t.balance(), 1e-9);
    }

    @Test
    void cannotOverdraw() {
        Treasury t = new Treasury();
        t.deposit(100, "x");
        assertFalse(t.withdraw(100.01, "admin", "y"));
        assertEquals(100, t.balance(), 1e-9);
        assertTrue(t.withdraw(100, "admin", "всё"));
        assertEquals(0, t.balance(), 1e-9);
    }

    @Test
    void ignoresNonPositiveAmounts() {
        Treasury t = new Treasury();
        t.deposit(0, "x");
        t.deposit(-5, "x");
        assertEquals(0, t.balance(), 1e-9);
        assertFalse(t.withdraw(0, "a", "b"));
        assertFalse(t.withdraw(-1, "a", "b"));
        assertTrue(t.log().isEmpty());
    }

    @Test
    void logKeepsLimitedHistory() {
        Treasury t = new Treasury();
        for (int i = 0; i < 700; i++) {
            t.deposit(1, "платёж " + i);
        }
        assertEquals(500, t.log().size());
        assertEquals("платёж 699", t.log().get(499).reason());
        assertEquals(700, t.balance(), 1e-9);
    }

    @Test
    void saveAndLoad(@TempDir File dir) throws IOException {
        File file = new File(dir, "treasury.yml");
        Treasury t = new Treasury();
        t.deposit(1234.5, "Аренда | участок shop-1");
        t.withdraw(34.5, "Админ", "Приз: конкурс\\строителей");
        t.save(file);

        Treasury loaded = Treasury.load(file);
        assertEquals(1200.0, loaded.balance(), 1e-9);
        assertEquals(2, loaded.log().size());
        assertEquals("Аренда | участок shop-1", loaded.log().get(0).reason());
        assertEquals("Приз: конкурс\\строителей", loaded.log().get(1).reason());
        assertEquals(-34.5, loaded.log().get(1).amount(), 1e-9);
    }

    @Test
    void loadMissingFileGivesEmptyTreasury(@TempDir File dir) {
        Treasury t = Treasury.load(new File(dir, "nope.yml"));
        assertEquals(0, t.balance(), 1e-9);
    }
}
