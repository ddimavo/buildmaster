package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.DoorText;
import ru.buildmaster.support.PluginTestBase;

/** Электричество отелей: потребление занятых номеров по площади, оплата со счёта отеля, сеть и предупреждения. */
class HotelPowerTest extends PluginTestBase {

    private final AtomicLong wall = new AtomicLong(1_000_000);
    private PlayerMock steve;   // управляющий отеля
    private PlayerMock alex;
    private PlayerMock mia;
    private Plot hotel;
    private Plot r1;
    private Plot r2;

    private Plot room(String id, int minX, int sizeX, int sizeZ) {
        Plot r = new Plot(id, "world", new Box(minX, 1, 1, minX + sizeX - 1, 3, sizeZ));
        r.category = Category.ROOM;
        r.parentId = "hot";
        r.rentPerDay = 40;
        plugin.plots().add(r);
        return r;
    }

    private void pass(long seconds) {
        wall.addAndGet(seconds * 1000);
        plugin.power().tick();
    }

    @BeforeEach
    void setUp() {
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.hotel", 0);
        plugin.getConfig().set("limits.categories.room", 0);
        plugin.getConfig().set("electricity.max-clock-step-seconds", 10_000_000);
        plugin.power().setTimeSource(wall::get);
        server.getWorld("world").loadChunk(0, 0);
        steve = player("Steve", 1_000_000);
        alex = player("Alex", 1_000_000);
        mia = player("Mia", 1_000_000);
        hotel = addPlot("hot", Category.HOTEL, 20, -1, 10);
        assertTrue(plugin.service().rent(steve, hotel, 14));
        r1 = room("hot-1", 21, 3, 4);     // 12 блоков пола
        r2 = room("hot-2", 25, 4, 5);     // 20 блоков пола
        plugin.getConfig().set("hotel-electricity.base-units", 10);
        plugin.getConfig().set("hotel-electricity.per-area", 0.5);
        plugin.getConfig().set("electricity.base-rate-per-day", 2.0);
        hotel.account.withdraw(hotel.account.rawBalance());      // начальный бонус счёта не нужен
        plugin.power().recalc(hotel);
    }

    @Test
    void roomUsageGrowsWithTheFloorArea() {
        assertEquals(10 + 0.5 * 12, plugin.power().roomUnits(r1), 1e-9);
        assertEquals(10 + 0.5 * 20, plugin.power().roomUnits(r2), 1e-9);
        plugin.getConfig().set("hotel-electricity.base-units", 4);
        plugin.getConfig().set("hotel-electricity.per-area", 1);
        assertEquals(16, plugin.power().roomUnits(r1), 1e-9);
    }

    @Test
    void emptyHotelConsumesNothingOccupiedRoomsConsume() {
        assertTrue(plugin.power().isBilled(hotel));
        assertEquals(0, plugin.power().unitsFor(hotel), 1e-9);
        assertEquals(0, hotel.account.ratePerDay(), 1e-9);
        assertTrue(plugin.service().rent(alex, r1, 2));
        assertEquals(16, hotel.account.unitsPerDay(), 1e-9);
        assertEquals(16 * 2.0, hotel.account.ratePerDay(), 1e-9, "цена за V из electricity.base-rate-per-day");
        assertTrue(plugin.service().rent(mia, r2, 2));
        assertEquals(16 + 20, hotel.account.unitsPerDay(), 1e-9);
        plugin.service().release(alex, r1);
        assertEquals(20, hotel.account.unitsPerDay(), 1e-9, "выезд снижает потребление");
        assertEquals(40, hotel.account.ratePerDay(), 1e-9);
    }

    @Test
    void roomsAreNeverBilledThemselves() {
        plugin.service().rent(alex, r1, 2);
        assertFalse(plugin.power().isBilled(r1));
        assertEquals(0, r1.account.ratePerDay(), 1e-9);
    }

    @Test
    void theHotelPaysFromItsOwnAccountIntoTheTreasury() {
        plugin.service().rent(alex, r1, 2);
        hotel.account.withdraw(hotel.account.rawBalance());     // оплата номера не мешает проверке
        hotel.account.deposit(1000);
        plugin.power().recalc(hotel);
        double treasury = plugin.treasury().balance();
        pass(43_200);   // половина суток
        assertEquals(1000 - 16, hotel.account.balanceAt(plugin.power().nowSec()), 1.0);
        assertEquals(16, plugin.treasury().balance() - treasury, 1.0, "оплата ушла в казну");
    }

    @Test
    void emptyAccountMeansBlackoutShownAtTheDoor() {
        plugin.service().rent(alex, r1, 2);
        hotel.account.withdraw(hotel.account.rawBalance());
        plugin.power().recalc(hotel);
        assertFalse(plugin.power().isPowered(hotel), "на счёте ноль");
        hotel.account.deposit(100);
        plugin.power().recalc(hotel);
        assertTrue(plugin.power().isPowered(hotel));
        pass(86_400 * 4);
        assertFalse(plugin.power().isPowered(hotel));
        assertEquals(3, hotel.powerWarnLevel, "управляющему отправлено предупреждение");
        var lines = DoorText.lines(r1, hotel, d -> String.valueOf(d), System.currentTimeMillis());
        assertFalse(lines.isEmpty());
    }

    @Test
    void disabledInConfigHotelsAreNotBilled() {
        plugin.getConfig().set("hotel-electricity.enabled", false);
        assertFalse(plugin.power().isBilled(hotel));
        plugin.service().rent(alex, r1, 2);
        assertEquals(0, hotel.account.unitsPerDay(), 1e-9);
    }

    @Test
    void hotelCanBeConnectedToAPowerPlant() {
        Plot plant = addPlot("p", Category.POWER_PLANT, 60, -1, -1);
        plant.plantCapacity = 1000;
        plugin.service().rent(alex, r1, 2);
        assertNull(plugin.grid().connect(hotel, plant, "Boss"));
        assertEquals("p", hotel.plantId);
        assertEquals(16, plugin.grid().demand(plant), 1e-9);
        assertNotNull(plugin.grid().connect(r1, plant, "Boss"), "номер не подключается");
    }

    @Test
    void withoutAPlantConnectionAHotelOnAnActiveGridIsOff() {
        Plot plant = addPlot("p", Category.POWER_PLANT, 60, -1, -1);
        plant.plantCapacity = 1000;
        plugin.service().rent(alex, r1, 2);
        hotel.account.deposit(500);
        plugin.power().recalc(hotel);
        assertTrue(plugin.power().isOnGrid(hotel) == (hotel.plantId != null));
        if (hotel.plantId == null) {
            assertFalse(plugin.power().isPowered(hotel));
            assertNotNull(plugin.grid().offReason(hotel));
        }
    }

    @Test
    void hotelHologramShowsTheElectricityLine() {
        plugin.service().rent(alex, r1, 2);
        hotel.account.deposit(500);
        plugin.power().recalc(hotel);
        var lines = ru.buildmaster.service.HologramText.lines(hotel, d -> String.valueOf(d), plugin.power().nowSec(), false, null);
        assertTrue(lines.stream().anyMatch(l -> l.contains("Электричество")), lines.toString());
    }
}
