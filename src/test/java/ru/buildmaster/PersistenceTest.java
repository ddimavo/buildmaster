package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.support.PluginTestBase;

/** Сохранение участков и поиск по координатам. */
class PersistenceTest extends PluginTestBase {

    @Test
    void everyFieldSurvivesSaveAndLoad() {
        Plot p = addPlot("shop-1", Category.RESTAURANT, 0, 5000, 150);
        UUID owner = UUID.randomUUID();
        UUID helper = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        p.createdBy = creator;
        p.status = Plot.Status.RENTED;
        p.owner = owner;
        p.ownerName = "Steve";
        p.rentedUntil = 123456789L;
        p.autoRenew = true;
        p.trusted.add(helper);
        p.name = "Трактир";
        p.description = "Горячие обеды";
        p.hasHolo = true;
        p.holoX = 1.5;
        p.holoY = 70.25;
        p.holoZ = -3;
        p.resetOnRelease = false;
        p.graceUntil = 999L;
        p.hasSpawn = true;
        p.spawnX = 4;
        p.spawnY = 5;
        p.spawnZ = 6;
        p.zones.put("фасад", new Plot.Zone("фасад", new Box(0, 0, 0, 9, 3, 0), "BRICKS"));
        p.restorePending = true;
        p.restoreFor = owner;
        p.restoreMode = Plot.RestoreMode.ITEMS_ONLY;
        p.restoreArrest = true;
        p.restoreReason = "нарушение";
        p.restoreBatch = "batch-1";
        plugin.plots().save();

        plugin.plots().load();
        Plot q = plugin.plots().get("shop-1");

        assertNotNull(q);
        assertEquals(Category.RESTAURANT, q.category);
        assertEquals(new Box(0, 0, 0, 9, 9, 9), q.box);
        assertEquals(5000, q.price, 1e-9);
        assertEquals(150, q.rentPerDay, 1e-9);
        assertEquals(creator, q.createdBy);
        assertEquals(Plot.Status.RENTED, q.status);
        assertEquals(owner, q.owner);
        assertEquals("Steve", q.ownerName);
        assertEquals(123456789L, q.rentedUntil);
        assertTrue(q.autoRenew);
        assertTrue(q.trusted.contains(helper));
        assertEquals("Трактир", q.name);
        assertEquals("Горячие обеды", q.description);
        assertTrue(q.hasHolo);
        assertEquals(70.25, q.holoY, 1e-9);
        assertFalse(q.resetOnRelease);
        assertEquals(999L, q.graceUntil);
        assertTrue(q.hasSpawn);
        assertEquals("BRICKS", q.zones.get("фасад").icon());
        assertTrue(q.restorePending);
        assertEquals(owner, q.restoreFor);
        assertEquals(Plot.RestoreMode.ITEMS_ONLY, q.restoreMode);
        assertTrue(q.restoreArrest);
        assertEquals("нарушение", q.restoreReason);
        assertEquals("batch-1", q.restoreBatch);
    }

    @Test
    void lookupByCoordinatesAcrossChunksAndEdges() {
        Plot big = new Plot("big", "world", new Box(-40, 0, -40, 40, 20, 40));
        plugin.plots().add(big);
        assertSame(big, plugin.plots().at("world", -40, 0, -40));
        assertSame(big, plugin.plots().at("world", 40, 20, 40));
        assertSame(big, plugin.plots().at("world", 17, 5, -33));
        assertNull(plugin.plots().at("world", 41, 5, 0));
        assertNull(plugin.plots().at("world", 0, 21, 0));
        assertNull(plugin.plots().at("nether", 0, 5, 0), "другой мир");
    }

    @Test
    void lookupByLocation() {
        Plot p = addPlot("a", Category.SHOP, 0, 1, 1);
        assertSame(p, plugin.plots().at(new Location(server.getWorld("world"), 5, 5, 5)));
        assertNull(plugin.plots().at(new Location(server.getWorld("world"), 50, 5, 5)));
    }

    @Test
    void removedPlotDisappearsFromIndex() {
        Plot p = addPlot("a", Category.SHOP, 0, 1, 1);
        plugin.plots().remove(p);
        assertNull(plugin.plots().at("world", 5, 5, 5));
        assertNull(plugin.plots().get("a"));
    }

    @Test
    void overlapDetection() {
        addPlot("a", Category.SHOP, 0, 1, 1);
        assertNotNull(plugin.plots().overlapping("world", new Box(5, 5, 5, 15, 15, 15)));
        assertNull(plugin.plots().overlapping("world", new Box(10, 0, 0, 20, 9, 9)));
        assertNull(plugin.plots().overlapping("other", new Box(0, 0, 0, 9, 9, 9)));
    }

    @Test
    void idLookupIgnoresCase() {
        Plot p = addPlot("Shop-A", Category.SHOP, 0, 1, 1);
        assertSame(p, plugin.plots().get("shop-a"));
        assertSame(p, plugin.plots().get("SHOP-A"));
    }

    @Test
    void ledgerIsPersistedBetweenFlushes() {
        plugin.ledgers().get("a").place(1, 2, 3, UUID.randomUUID(), "CHEST", false);
        plugin.ledgers().markDirty("a");
        plugin.ledgers().flush();
        // новая книга того же участка читается из файла
        var fresh = new ru.buildmaster.service.LedgerStore(plugin);
        assertEquals(1, fresh.get("a").size());
        assertEquals("CHEST", fresh.get("a").get(1, 2, 3).material());
    }
}
