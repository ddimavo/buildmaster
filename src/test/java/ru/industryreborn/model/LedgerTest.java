package ru.industryreborn.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Test
    void packUnpackHandlesNegativeCoordinates() {
        int[][] samples = {{0, 0, 0}, {-1, -64, -1}, {1234, 319, -5678}, {-29_999_999, -64, 29_999_999}, {30_000_000, 320, -30_000_000}};
        for (int[] s : samples) {
            long key = Ledger.pack(s[0], s[1], s[2]);
            assertEquals(s[0], Ledger.unpackX(key), "x " + s[0]);
            assertEquals(s[1], Ledger.unpackY(key), "y " + s[1]);
            assertEquals(s[2], Ledger.unpackZ(key), "z " + s[2]);
        }
    }

    @Test
    void differentPositionsHaveDifferentKeys() {
        assertNotEquals(Ledger.pack(1, 2, 3), Ledger.pack(3, 2, 1));
        assertNotEquals(Ledger.pack(0, 0, 0), Ledger.pack(0, 1, 0));
    }

    @Test
    void remembersWhoPlacedWhat() {
        Ledger l = new Ledger();
        l.place(1, 64, 1, ALICE, "FURNACE", false);
        l.place(2, 64, 1, BOB, "CHEST", false);
        assertEquals(ALICE, l.get(1, 64, 1).player());
        assertEquals("CHEST", l.get(2, 64, 1).material());
        assertNull(l.get(3, 64, 1));
        assertEquals(2, l.size());
    }

    @Test
    void removeReturnsEntryAndForgetsIt() {
        Ledger l = new Ledger();
        l.place(1, 1, 1, ALICE, "STONE", false);
        assertNotNull(l.remove(1, 1, 1));
        assertFalse(l.contains(1, 1, 1));
        assertNull(l.remove(1, 1, 1));
    }

    @Test
    void stackingCountsAmountOnlyForSamePlayerAndMaterial() {
        Ledger l = new Ledger();
        l.place(0, 0, 0, ALICE, "OAK_SLAB", false);
        l.place(0, 0, 0, ALICE, "OAK_SLAB", true);
        assertEquals(2, l.get(0, 0, 0).amount());
        // другой игрок или другой материал начинают запись заново
        l.place(0, 0, 0, BOB, "OAK_SLAB", true);
        assertEquals(1, l.get(0, 0, 0).amount());
        l.place(0, 0, 0, BOB, "STONE", true);
        assertEquals(1, l.get(0, 0, 0).amount());
    }

    @Test
    void staleEntryIsReplacedWhenNotStacking() {
        Ledger l = new Ledger();
        l.place(0, 0, 0, ALICE, "CANDLE", false);
        l.place(0, 0, 0, ALICE, "CANDLE", false); // блок был сломан без события, поставлен заново
        assertEquals(1, l.get(0, 0, 0).amount());
    }

    @Test
    void writeReadRoundTrip() throws IOException {
        Ledger l = new Ledger();
        l.place(-5, 70, 9, ALICE, "CHEST", false);
        l.place(6, -10, -3, BOB, "OAK_SLAB", false);
        l.place(6, -10, -3, BOB, "OAK_SLAB", true);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        l.write(new DataOutputStream(bytes));
        Ledger copy = Ledger.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertEquals(2, copy.size());
        assertEquals(new Ledger.Entry(ALICE, "CHEST", 1), copy.get(-5, 70, 9));
        assertEquals(new Ledger.Entry(BOB, "OAK_SLAB", 2), copy.get(6, -10, -3));
    }
}
