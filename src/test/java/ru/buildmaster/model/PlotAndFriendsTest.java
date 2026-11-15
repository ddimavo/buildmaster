package ru.buildmaster.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlotAndFriendsTest {

    private static Plot plot() {
        return new Plot("shop-1", "world", new Box(0, 0, 0, 9, 9, 9));
    }

    @Test
    void displayNameFallsBackToCategoryAndId() {
        Plot p = plot();
        p.category = Category.WORKSHOP;
        assertEquals("Мастерская shop-1", p.displayName());
        p.name = "Кузница Гефеста";
        assertEquals("Кузница Гефеста", p.displayName());
        p.name = "  ";
        assertEquals("Мастерская shop-1", p.displayName());
    }

    @Test
    void frozenOnlyDuringGrace() {
        Plot p = plot();
        assertFalse(p.isFrozen());
        p.graceUntil = System.currentTimeMillis() + 60_000;
        assertTrue(p.isFrozen());
        p.graceUntil = System.currentTimeMillis() - 1;
        assertFalse(p.isFrozen());
    }

    @Test
    void membersAreOwnerAndTrusted() {
        Plot p = plot();
        UUID owner = UUID.randomUUID();
        UUID helper = UUID.randomUUID();
        p.owner = owner;
        p.trusted.add(helper);
        assertTrue(p.isOwner(owner));
        assertFalse(p.isOwner(helper));
        assertTrue(p.isMember(helper));
        assertFalse(p.isMember(UUID.randomUUID()));
    }

    @Test
    void categoryParsesKeyAndRussianTitle() {
        assertEquals(Category.SHOP, Category.parse("shop"));
        assertEquals(Category.SHOP, Category.parse("МАГАЗИН"));
        assertEquals(Category.WAREHOUSE, Category.parse(" warehouse "));
        assertNull(Category.parse("castle"));
        assertNull(Category.parse(null));
    }

    @Test
    void storageEntryExpiresOnlyWhenAvailable() {
        long day = 86_400_000L;
        long now = 100 * day;
        StorageEntry old = new StorageEntry(UUID.randomUUID(), "p", now - 61 * day, "b", StorageEntry.State.AVAILABLE, null);
        StorageEntry fresh = new StorageEntry(UUID.randomUUID(), "p", now - 59 * day, "b", StorageEntry.State.AVAILABLE, null);
        StorageEntry arrested = new StorageEntry(UUID.randomUUID(), "p", now - 200 * day, "b", StorageEntry.State.ARRESTED, "reason");
        StorageEntry auction = new StorageEntry(UUID.randomUUID(), "p", now - 200 * day, "b", StorageEntry.State.AUCTION, "reason");
        assertTrue(old.expired(now, 60));
        assertFalse(fresh.expired(now, 60));
        assertFalse(arrested.expired(now, 60), "арестованное не истекает само");
        assertFalse(auction.expired(now, 60));
        assertFalse(old.expired(now, 0), "0 - хранить бессрочно");
    }
}
