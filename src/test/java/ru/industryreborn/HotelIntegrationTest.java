package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.PlotService;
import ru.industryreborn.service.SnapshotStore;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.util.ChunkAccess;
import ru.industryreborn.util.ChunkJob;

/** Отели: номера как вложенные участки, двери, доходы управляющего, выезд и сброс. */
class HotelIntegrationTest extends PluginTestBase {

    /** StructureManager нет в MockBukkit: снимки подменяются заглушкой. */
    private static final class FakeSnapshots extends SnapshotStore {
        final List<String> placed = new ArrayList<>();

        @Override
        public void capture(File dir, World world, int cx, int cz, Box clip) { }

        @Override
        public void saveMeta(File dir, Box box, Set<UUID> originalEntities) { }

        @Override
        public Set<UUID> originalEntities(World world, Box box) {
            return Set.of();
        }

        @Override
        public boolean exists(File dir) {
            return true;
        }

        @Override
        public Set<UUID> loadOriginalEntities(File dir) {
            return Set.of();
        }

        @Override
        public boolean place(File dir, World world, int cx, int cz, Box clip) {
            placed.add(dir.getName() + ":" + clip.serialize());
            return true;
        }
    }

    private FakeSnapshots snapshots;
    private PlayerMock steve;     // управляющий отеля
    private PlayerMock alex;      // гость
    private PlayerMock mia;       // гость
    private PlayerMock boss;
    private Plot hotel;

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    @BeforeEach
    void setUp() {
        ChunkJob.setLoader((world, cx, cz, cb) -> cb.accept(world.getChunkAt(cx, cz), null));
        ChunkAccess.setSource(new ChunkAccess.Source() {
            @Override
            public List<org.bukkit.block.BlockState> tileEntities(org.bukkit.Chunk chunk) {
                List<org.bukkit.block.BlockState> result = new ArrayList<>();
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 12; y++) {
                        for (int z = 0; z < 16; z++) {
                            Block b = chunk.getBlock(x, y, z);
                            if (b.getType() == Material.CHEST || b.getType() == Material.BARREL) {
                                result.add(b.getState());
                            }
                        }
                    }
                }
                return result;
            }

            @Override
            public java.util.Collection<org.bukkit.entity.Entity> entities(org.bukkit.Chunk chunk) {
                return List.of();
            }
        });
        snapshots = new FakeSnapshots();
        plugin.setSnapshots(snapshots);
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.room", 1);
        steve = player("Steve", 1_000_000);
        alex = player("Alex", 1_000_000);
        mia = player("Mia", 1_000_000);
        boss = admin("Boss");
        // отель 50x10x10, комнаты внутри
        hotel = addPlot("h", Category.HOTEL, 0, 1000, -1);
    }

    @AfterEach
    void tearDownSeams() {
        ChunkJob.setLoader(null);
        ChunkAccess.setSource(null);
    }

    private void ticks() {
        server.getScheduler().performTicks(20);
    }

    /** Отель в тестах шире стандартных 10 блоков: пересоздаём участок. */
    private Plot wideHotel() {
        plugin.plots().remove(hotel);
        Plot wide = new Plot("h", "world", new Box(0, 0, 0, 49, 9, 9));
        wide.category = Category.HOTEL;
        wide.price = 1000;
        plugin.plots().add(wide);
        plugin.stations().register(wide);
        hotel = wide;
        return wide;
    }

    private Plot room(String roomId, int minX, double rate) {
        wideHotelIfNeeded();
        AtomicInteger ok = new AtomicInteger();
        plugin.service().createRoom(boss, hotel, roomId, new Box(minX, 1, 1, minX + 3, 3, 4), rate, new ArrayList<>(),
                r -> ok.set(r ? 1 : -1));
        ticks();
        assertEquals(1, ok.get(), "номер не создан");
        return plugin.plots().get(hotel.id + "-" + roomId);
    }

    private void wideHotelIfNeeded() {
        if (hotel.box.sizeX() < 50) {
            wideHotel();
        }
    }

    private Block block(int x, int y, int z) {
        return server.getWorld("world").getBlockAt(x, y, z);
    }

    private BlockPlaceEvent place(PlayerMock who, Block b, Material m) {
        b.setType(m);
        BlockPlaceEvent e = new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0), new ItemStack(m), who, true,
                EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        return e;
    }

    private BlockBreakEvent breakBlock(PlayerMock who, Block b) {
        BlockBreakEvent e = new BlockBreakEvent(b, who);
        server.getPluginManager().callEvent(e);
        return e;
    }

    private PlayerInteractEvent click(PlayerMock who, Block b) {
        PlayerInteractEvent e = new PlayerInteractEvent(who, Action.RIGHT_CLICK_BLOCK, null, b, BlockFace.NORTH,
                EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        return e;
    }

    private static int count(PlayerMock p, Material m) {
        int n = 0;
        for (ItemStack s : p.getInventory().getContents()) {
            if (s != null && s.getType() == m) {
                n += s.getAmount();
            }
        }
        return n;
    }

    private int stored(UUID id, Material m) {
        int n = 0;
        for (StorageEntry e : plugin.storage().entries(id)) {
            for (byte[] b : e.stacks) {
                ItemStack s = ItemStack.deserializeBytes(b);
                if (s.getType() == m) {
                    n += s.getAmount();
                }
            }
        }
        return n;
    }

    private void rentHotel() {
        wideHotelIfNeeded();
        hotel.rentPerDay = 100;
        assertTrue(plugin.service().rent(steve, hotel, 14));
    }

    // ------------------------------------------------------------ создание номеров

    @Test
    void aRoomIsANestedPlotOfTheRoomCategory() {
        Plot r = room("101", 1, 20);
        assertEquals(Category.ROOM, r.category);
        assertEquals("h", r.parentId);
        assertEquals(-1, r.price, 1e-9);
        assertEquals(20, r.rentPerDay, 1e-9);
        assertTrue(r.resetOnRelease);
        assertEquals("h-101", r.id);
        assertEquals(1, plugin.hotels().rooms(hotel).size());
        assertEquals(Journal.Action.HOTEL, plugin.journal().forPlot("h-101").get(0).action());
    }

    @Test
    void roomInteriorBelongsToTheRoomAndTheRestToTheHotel() {
        Plot r = room("101", 1, 20);
        assertSame(r, plugin.plots().at("world", 2, 2, 2));
        assertSame(hotel, plugin.plots().at("world", 0, 2, 2), "стена принадлежит отелю");
        assertSame(hotel, plugin.plots().at("world", 20, 2, 2), "коридор принадлежит отелю");
    }

    @Test
    void roomBoundsAreValidated() {
        wideHotel();
        room("101", 1, 20);
        assertNotNull(plugin.hotels().validateRoomBox(hotel, new Box(60, 1, 1, 63, 3, 4), "999"), "вне отеля");
        assertNotNull(plugin.hotels().validateRoomBox(hotel, new Box(2, 1, 1, 5, 3, 4), "102"), "пересекается с 101");
        assertNotNull(plugin.hotels().validateRoomBox(hotel, new Box(10, 1, 1, 13, 3, 4), "101"), "номер уже есть");
        assertNotNull(plugin.hotels().validateRoomBox(hotel, new Box(10, 1, 1, 13, 3, 4), "bad id!"));
        assertNull(plugin.hotels().validateRoomBox(hotel, new Box(10, 1, 1, 13, 3, 4), "102"));
        Plot shop = addPlot("s", Category.SHOP, 100, 100, -1);
        assertNotNull(plugin.hotels().validateRoomBox(shop, new Box(100, 1, 1, 103, 3, 4), "1"), "это не отель");
    }

    @Test
    void createCommandNeedsAHotelAndASelection() {
        wideHotel();
        boss.performCommand("district room create h 101 20");
        assertTrue(drain(boss).contains("выделите"));
        addPlot("s", Category.SHOP, 100, 100, -1);
        boss.performCommand("district room create s 101 20");
        assertTrue(drain(boss).contains("не отель"));
        steve.performCommand("district room create h 101 20");
        assertTrue(drain(steve).contains("Недостаточно прав"));
    }

    @Test
    void templateCreatesShiftedRoomsWithDoors() {
        wideHotel();
        AtomicInteger created = new AtomicInteger(-1);
        // первый номер 4x3x4, шаг 6 по оси x, три номера, дверь на (0, 0, -1) от угла номера -> у стены
        String error = plugin.hotels().template(boss, hotel, "n", new Box(1, 1, 1, 4, 3, 4), true, 6, 3, 15,
                new int[] {0, 0, -1}, created::set);
        assertNull(error);
        ticks();
        assertEquals(3, created.get());

        Plot n1 = plugin.plots().get("h-n1");
        Plot n2 = plugin.plots().get("h-n2");
        Plot n3 = plugin.plots().get("h-n3");
        assertEquals(new Box(1, 1, 1, 4, 3, 4), n1.box);
        assertEquals(new Box(7, 1, 1, 10, 3, 4), n2.box);
        assertEquals(new Box(13, 1, 1, 16, 3, 4), n3.box);
        assertArrayEquals(new int[] {7, 1, 0}, n2.doors.get(0));
        assertEquals(15, n3.rentPerDay, 1e-9);
    }

    @Test
    void templateRefusesOverlapsAndRoomsOutsideTheHotel() {
        wideHotel();
        assertNotNull(plugin.hotels().template(boss, hotel, "n", new Box(1, 1, 1, 4, 3, 4), true, 3, 3, 15, null, c -> { }),
                "шаг меньше размера - номера пересекаются");
        assertNotNull(plugin.hotels().template(boss, hotel, "n", new Box(1, 1, 1, 4, 3, 4), true, 20, 5, 15, null, c -> { }),
                "последний номер выходит за отель");
        assertNotNull(plugin.hotels().template(boss, hotel, "n", new Box(1, 1, 1, 4, 3, 4), true, 6, 0, 15, null, c -> { }));
        assertNotNull(plugin.hotels().template(boss, hotel, "n", new Box(1, 1, 1, 4, 3, 4), true, 6, 101, 15, null, c -> { }));
        assertTrue(plugin.hotels().rooms(hotel).isEmpty(), "при ошибке ничего не создаётся");
    }

    @Test
    void templateCommandReportsTheCreatedRooms() {
        wideHotel();
        var sel = plugin.selection();
        sel.setPoint(boss, 1, 1, 1, 1);
        sel.setPoint(boss, 2, 4, 3, 4);
        boss.performCommand("district room template h n x 6 2 15 0 0 -1");
        ticks();
        assertNotNull(plugin.plots().get("h-n1"));
        assertNotNull(plugin.plots().get("h-n2"));
    }

    // ------------------------------------------------------------ двери

    @Test
    void doorsMustBeInTheWallNotInsideTheRoom() {
        Plot r = room("101", 1, 20);
        assertNotNull(plugin.hotels().addDoor(r, block(2, 2, 2)), "внутри номера");
        assertNotNull(plugin.hotels().addDoor(r, block(200, 2, 2)), "вне отеля");
        assertNull(plugin.hotels().addDoor(r, block(0, 2, 2)));
        assertNotNull(plugin.hotels().addDoor(r, block(0, 2, 2)), "уже добавлена");
    }

    @Test
    void aRoomHasAtMostFourDoors() {
        Plot r = room("101", 1, 20);
        for (int i = 0; i < 4; i++) {
            assertNull(plugin.hotels().addDoor(r, block(0, 1 + i % 3, i)));
        }
        assertNotNull(plugin.hotels().addDoor(r, block(0, 3, 9)));
    }

    @Test
    void aFreeRoomDoorOpensForEveryone() {
        Plot r = room("101", 1, 20);
        plugin.hotels().addDoor(r, block(0, 2, 2));
        block(0, 2, 2).setType(Material.OAK_DOOR);
        assertFalse(click(alex, block(0, 2, 2)).isCancelled());
    }

    @Test
    void anOccupiedRoomDoorOpensOnlyForTheGuestTheirTrustedAndTheHotelManager() {
        rentHotel();
        Plot r = room("101", 1, 20);
        plugin.hotels().addDoor(r, block(0, 2, 2));
        block(0, 2, 2).setType(Material.OAK_DOOR);
        assertTrue(plugin.service().rent(alex, r, 3));
        r.grantAll(mia.getUniqueId());
        PlayerMock stranger = player("Zed", 0);

        assertFalse(click(alex, block(0, 2, 2)).isCancelled(), "гость");
        assertFalse(click(mia, block(0, 2, 2)).isCancelled(), "доверенный гостя");
        assertFalse(click(steve, block(0, 2, 2)).isCancelled(), "управляющий отеля");
        assertFalse(click(boss, block(0, 2, 2)).isCancelled(), "администратор");
        assertTrue(click(stranger, block(0, 2, 2)).isCancelled(), "посторонний");
    }

    @Test
    void theTopHalfOfADoorIsLockedToo() {
        Plot r = room("101", 1, 20);
        plugin.hotels().addDoor(r, block(0, 1, 2));          // нижняя половина
        block(0, 1, 2).setType(Material.OAK_DOOR);
        block(0, 2, 2).setType(Material.OAK_DOOR);
        plugin.service().rent(alex, r, 3);
        assertTrue(click(mia, block(0, 2, 2)).isCancelled(), "верхняя половина двери тоже заперта");
        assertTrue(click(mia, block(0, 1, 2)).isCancelled());
        assertFalse(click(alex, block(0, 2, 2)).isCancelled());
    }

    @Test
    void doorCommandsWork() {
        Plot r = room("101", 1, 20);
        assertNull(plugin.hotels().addDoor(r, block(0, 2, 2)));
        boss.performCommand("district room door clear h-101");
        assertTrue(r.doors.isEmpty());
        steve.performCommand("district room door h-101");
        assertTrue(drain(steve).contains("Недостаточно прав"));
    }

    // ------------------------------------------------------------ аренда и деньги

    @Test
    void roomsAreRentedNotBought() {
        Plot r = room("101", 1, 20);
        r.price = 500;
        assertFalse(plugin.service().buy(alex, r));
        assertTrue(r.isFree());
        assertTrue(plugin.service().rent(alex, r, 3));
        assertEquals(Plot.Status.RENTED, r.status);
        assertTrue(plugin.journal().forPlayer(alex.getUniqueId()).stream().anyMatch(e -> e.action() == Journal.Action.RENTED));
    }

    @Test
    void roomRentFollowsTheFourteenDayRules() {
        Plot r = room("101", 1, 20);
        assertFalse(plugin.service().rent(alex, r, 15));
        assertTrue(plugin.service().rent(alex, r, 14));
        assertFalse(plugin.service().extend(alex, r, 1), "уже 14 суток");
        r.rentedUntil = System.currentTimeMillis() + 12 * plugin.service().dayMillis();
        assertFalse(plugin.service().extend(alex, r, 3));
        assertTrue(plugin.service().extend(alex, r, 2));
    }

    @Test
    void aGuestCanRentOnlyOneRoomByDefault() {
        Plot r1 = room("101", 1, 20);
        Plot r2 = room("102", 7, 20);
        assertTrue(plugin.service().rent(alex, r1, 3));
        assertFalse(plugin.service().rent(alex, r2, 3), "лимит номеров на гостя");
        assertTrue(plugin.service().rent(mia, r2, 3));
    }

    @Test
    void rentGoesToTheHotelManagerAccountMinusTheRentTax() {
        rentHotel();
        boss.performCommand("district bank admin tax rent 10");
        Plot r = room("101", 1, 20);
        double account = hotel.account.rawBalance();
        double treasury = plugin.treasury().balance();
        double wallet = economy.getBalance(alex);

        assertTrue(plugin.service().rent(alex, r, 5));      // 100

        assertEquals(wallet - 100, economy.getBalance(alex), 1e-9);
        assertEquals(account + 90, hotel.account.rawBalance(), 1e-6, "управляющему 90%");
        assertEquals(treasury + 10, plugin.treasury().balance(), 1e-6, "налог на аренду - в казну");
        assertTrue(plugin.journal().forPlot("h").stream().anyMatch(e -> e.action() == Journal.Action.HOTEL
                && e.reason().contains("налог на аренду")));
        assertTrue(plugin.journal().forPlot("h").stream().anyMatch(e -> e.action() == Journal.Action.TAX));
        assertTrue(plugin.warnings().forPlayer(steve.getUniqueId()).get(0).text.contains("Поступила оплата"));
    }

    @Test
    void withoutATaxTheManagerGetsEverything() {
        rentHotel();
        Plot r = room("101", 1, 20);
        double account = hotel.account.rawBalance();
        plugin.service().rent(alex, r, 5);
        assertEquals(account + 100, hotel.account.rawBalance(), 1e-6);
    }

    @Test
    void anUnmanagedHotelPaysTheTreasury() {
        Plot r = room("101", 1, 20);
        double treasury = plugin.treasury().balance();
        assertTrue(plugin.service().rent(alex, r, 5));
        assertEquals(treasury + 100, plugin.treasury().balance(), 1e-6);
    }

    @Test
    void extensionsAndAutoRenewAlsoPayTheManager() {
        rentHotel();
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 2);
        double account = hotel.account.rawBalance();
        assertTrue(plugin.service().extend(alex, r, 2));
        assertEquals(account + 40, hotel.account.rawBalance(), 1e-6);

        plugin.service().setAutoRenew(r, true);
        r.rentedUntil = System.currentTimeMillis() - 1000;
        plugin.service().tick();
        assertEquals(account + 40 + 140, hotel.account.rawBalance(), 1.0, "автопродление на 7 суток");
    }

    @Test
    void roomsAreNotChargedPropertyTax() {
        Plot r = room("101", 1, 20);
        plugin.bank().settings().setPropertyTax("room", 999.0);
        plugin.service().rent(alex, r, 5);
        r.nextTaxAt = System.currentTimeMillis() - 1000;
        double wallet = economy.getBalance(alex);
        plugin.bank().tick();
        assertEquals(wallet, economy.getBalance(alex), 1e-9);
    }

    // ------------------------------------------------------------ защита

    @Test
    void aGuestBuildsInsideTheRoomButNotInTheWallsOrTheCorridor() {
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 3);

        assertFalse(place(alex, block(2, 2, 2), Material.OAK_PLANKS).isCancelled(), "внутри номера");
        assertTrue(place(alex, block(0, 2, 2), Material.OAK_PLANKS).isCancelled(), "стена");
        assertTrue(place(alex, block(20, 2, 2), Material.OAK_PLANKS).isCancelled(), "коридор");
        block(0, 2, 2).setType(Material.STONE);
        assertTrue(breakBlock(alex, block(0, 2, 2)).isCancelled(), "ломать стену нельзя");
    }

    @Test
    void theHotelManagerCannotBuildInsideARentedRoomNorOpenGuestChests() {
        rentHotel();
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 3);
        block(3, 2, 3).setType(Material.CHEST);

        assertTrue(place(steve, block(2, 2, 2), Material.STONE).isCancelled());
        assertTrue(click(steve, block(3, 2, 3)).isCancelled(), "чужой сундук гостя");
        assertFalse(click(alex, block(3, 2, 3)).isCancelled());
    }

    @Test
    void theHotelManagerBuildsInTheCorridors() {
        rentHotel();
        room("101", 1, 20);
        assertFalse(place(steve, block(20, 2, 2), Material.OAK_PLANKS).isCancelled());
        assertTrue(place(alex, block(21, 2, 2), Material.OAK_PLANKS).isCancelled());
    }

    @Test
    void aFreeRoomIsClosedToEveryoneButAdmins() {
        room("101", 1, 20);
        assertTrue(place(alex, block(2, 2, 2), Material.STONE).isCancelled());
        assertFalse(place(boss, block(2, 2, 3), Material.STONE).isCancelled());
    }

    @Test
    void originalFurnitureDropsNothingSoThereIsNoDuplicationAtCheckout() {
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 3);
        block(2, 2, 2).setType(Material.SEA_LANTERN);          // исходная лампа комнаты
        BlockBreakEvent e = breakBlock(alex, block(2, 2, 2));
        assertFalse(e.isCancelled());
        assertFalse(e.isDropItems(), "лампа не выпадает: при выезде она всё равно вернётся на место");
    }

    @Test
    void trustedGuestsCanBuildInTheRoom() {
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 3);
        r.grantAll(mia.getUniqueId());
        assertFalse(place(mia, block(2, 2, 2), Material.OAK_PLANKS).isCancelled());
    }

    // ------------------------------------------------------------ выезд

    @Test
    void checkoutReturnsTheGuestsItemsAndResetsOnlyTheirRoom() {
        Plot r1 = room("101", 1, 20);
        Plot r2 = room("102", 7, 20);
        plugin.service().rent(alex, r1, 3);
        plugin.service().rent(mia, r2, 3);
        place(alex, block(2, 2, 2), Material.FURNACE);
        place(mia, block(8, 2, 2), Material.ANVIL);
        block(3, 2, 3).setType(Material.CHEST);
        ((Chest) block(3, 2, 3).getState()).getBlockInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        plugin.ledgers().get(r1.id).place(3, 2, 3, alex.getUniqueId(), "CHEST", false);

        plugin.service().release(alex, r1);
        ticks();

        assertTrue(r1.isFree());
        assertEquals(Plot.Status.RENTED, r2.status, "соседний номер не тронут");
        assertEquals(1, count(alex, Material.FURNACE) + stored(alex.getUniqueId(), Material.FURNACE));
        assertEquals(5, count(alex, Material.DIAMOND) + stored(alex.getUniqueId(), Material.DIAMOND));
        assertEquals(0, count(mia, Material.ANVIL) + stored(mia.getUniqueId(), Material.ANVIL), "блок Мии остаётся у неё в номере");
        assertTrue(snapshots.placed.stream().anyMatch(s -> s.startsWith("h-101:")), "номер сброшен к исходному виду");
        assertTrue(snapshots.placed.stream().noneMatch(s -> s.startsWith("h-102:")));
    }

    @Test
    void checkoutIsAlwaysAFullResetEvenIfTheGuestDisabledIt() {
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 3);
        plugin.service().setResetOnRelease(r, false);
        plugin.service().release(alex, r);
        ticks();
        assertFalse(snapshots.placed.isEmpty(), "номер всё равно сброшен");
    }

    @Test
    void anExpiredRentEvictsTheGuestAndResetsTheRoom() {
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 1);
        place(alex, block(2, 2, 2), Material.FURNACE);
        r.rentedUntil = System.currentTimeMillis() - 1000;

        plugin.service().tick();
        ticks();

        assertTrue(r.isFree());
        assertEquals(1, count(alex, Material.FURNACE) + stored(alex.getUniqueId(), Material.FURNACE));
        assertFalse(snapshots.placed.isEmpty());
    }

    @Test
    void theGuestsRightsAreDroppedOnCheckout() {
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 3);
        r.grantAll(mia.getUniqueId());
        plugin.service().release(alex, r);
        ticks();
        assertTrue(r.grants.isEmpty());
        plugin.service().rent(mia, r, 2);
        assertTrue(r.isOwner(mia.getUniqueId()));
    }

    // ------------------------------------------------------------ гости в отеле

    @Test
    void aHotelWithGuestsCannotBeResetAsAWhole() {
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 3);
        String error = plugin.service().reset(hotel, "Boss", "ремонт фасада");
        assertNotNull(error);
        assertFalse(hotel.restorePending);
        assertTrue(snapshots.placed.isEmpty());
        boss.performCommand("district reset h ремонт фасада");
        assertTrue(drain(boss).contains("есть гости"));
    }

    @Test
    void anEmptyHotelCanBeReset() {
        room("101", 1, 20);
        assertNull(plugin.service().reset(hotel, "Boss", "ремонт"));
        ticks();
        assertFalse(snapshots.placed.isEmpty());
    }

    @Test
    void theManagerLeavingNeverTouchesTheGuestsRooms() {
        rentHotel();
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 3);
        block(3, 2, 3).setType(Material.CHEST);
        ((Chest) block(3, 2, 3).getState()).getBlockInventory().addItem(new ItemStack(Material.EMERALD, 9));
        steve.getInventory().clear();

        plugin.service().release(steve, hotel);        // по умолчанию сброс здания включён
        ticks();

        assertTrue(hotel.isFree());
        assertEquals(Plot.Status.RENTED, r.status, "гость остался в номере");
        assertTrue(snapshots.placed.isEmpty(), "здание целиком не сбрасывалось: в нём живут гости");
        assertEquals(0, count(steve, Material.EMERALD) + stored(steve.getUniqueId(), Material.EMERALD),
                "сундук гостя не достался управляющему");
        assertEquals(9, ((Chest) block(3, 2, 3).getState()).getBlockInventory().getItem(0).getAmount());
    }

    @Test
    void afterTheManagerLeavesRoomRentGoesToTheTreasury() {
        rentHotel();
        Plot r = room("101", 1, 20);
        plugin.service().release(steve, hotel);
        ticks();
        double treasury = plugin.treasury().balance();
        plugin.service().rent(alex, r, 5);
        assertEquals(treasury + 100, plugin.treasury().balance(), 1e-6);
    }

    @Test
    void evictingTheHotelKeepsGuestsDoorsWorking() {
        rentHotel();
        Plot r = room("101", 1, 20);
        plugin.hotels().addDoor(r, block(0, 2, 2));
        block(0, 2, 2).setType(Material.OAK_DOOR);
        plugin.service().rent(alex, r, 3);
        plugin.service().release(steve, hotel);
        ticks();
        assertFalse(click(alex, block(0, 2, 2)).isCancelled());
        assertTrue(click(mia, block(0, 2, 2)).isCancelled());
    }

    // ------------------------------------------------------------ цены

    @Test
    void theAdministratorSetsAnyRoomRate() {
        Plot r = room("101", 1, 20);
        boss.performCommand("district room rate h-101 55");
        assertEquals(55, r.rentPerDay, 1e-9);
        assertEquals(Journal.Action.HOTEL, plugin.journal().forPlot("h-101").get(0).action());
    }

    @Test
    void theHotelManagerSetsRatesOnlyUpToTheLimitTheAdministratorAllowed() {
        rentHotel();
        Plot r = room("101", 1, 20);
        assertNotNull(plugin.hotels().setRate(steve, r, 30), "администратор не разрешил");

        boss.performCommand("district room maxrate h 60");
        assertEquals(60, hotel.maxRoomRate, 1e-9);
        assertNull(plugin.hotels().setRate(steve, r, 60));
        assertEquals(60, r.rentPerDay, 1e-9);
        assertNotNull(plugin.hotels().setRate(steve, r, 61));
        assertEquals(60, r.rentPerDay, 1e-9);
    }

    @Test
    void guestsAndStrangersCannotSetRates() {
        rentHotel();
        Plot r = room("101", 1, 20);
        boss.performCommand("district room maxrate h 100");
        plugin.service().rent(alex, r, 3);
        assertNotNull(plugin.hotels().setRate(alex, r, 1));
        assertNotNull(plugin.hotels().setRate(mia, r, 1));
        assertEquals(20, r.rentPerDay, 1e-9);
    }

    @Test
    void invalidRatesAreRejected() {
        Plot r = room("101", 1, 20);
        assertNotNull(plugin.hotels().setRate(boss, r, -1));
        assertNotNull(plugin.hotels().setRate(boss, r, Double.NaN));
        assertEquals(20, r.rentPerDay, 1e-9);
    }

    @Test
    void aRateChangeAppliesToNewRentalsOnly() {
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 2);
        long until = r.rentedUntil;
        plugin.hotels().setRate(boss, r, 100);
        assertEquals(until, r.rentedUntil);
        double wallet = economy.getBalance(alex);
        plugin.service().extend(alex, r, 1);
        assertEquals(wallet - 100, economy.getBalance(alex), 1e-9);
    }

    // ------------------------------------------------------------ удаление и сохранение

    @Test
    void onlyFreeRoomsAndEmptyHotelsCanBeDeleted() {
        Plot r = room("101", 1, 20);
        plugin.service().rent(alex, r, 3);
        assertNotNull(plugin.hotels().deleteRoom(r));
        boss.performCommand("district delete h");
        assertNotNull(plugin.plots().get("h"), "в отеле есть номера");

        plugin.service().release(alex, r);
        ticks();
        assertNull(plugin.hotels().deleteRoom(r));
        assertNull(plugin.plots().get("h-101"));
        assertSame(hotel, plugin.plots().at("world", 2, 2, 2), "после удаления номера область снова принадлежит отелю");
    }

    @Test
    void roomsDoorsAndLimitsSurviveARestart() {
        Plot r = room("101", 1, 20);
        plugin.hotels().addDoor(r, block(0, 2, 2));
        hotel.maxRoomRate = 77;
        plugin.plots().save();

        plugin.plots().load();

        Plot loaded = plugin.plots().get("h-101");
        assertEquals("h", loaded.parentId);
        assertEquals(1, loaded.doors.size());
        assertArrayEquals(new int[] {0, 2, 2}, loaded.doors.get(0));
        assertEquals(77, plugin.plots().get("h").maxRoomRate, 1e-9);
        block(0, 2, 2).setType(Material.OAK_DOOR);
        assertSame(loaded, plugin.hotels().roomAtDoor(block(0, 2, 2)), "индекс дверей перестроен после загрузки");
        assertSame(loaded, plugin.plots().at("world", 2, 2, 2));
    }

    // ------------------------------------------------------------ список и меню

    @Test
    void listCommandShowsFreeAndOccupiedRooms() {
        Plot r1 = room("101", 1, 20);
        room("102", 7, 25);
        plugin.service().rent(alex, r1, 3);
        drain(mia);
        mia.performCommand("district room list h");
        String out = drain(mia);
        assertTrue(out.contains("свободно 1 из 2"), out);
        assertTrue(out.contains("h-101"), out);
        assertTrue(out.contains("занят"), out);
        assertTrue(out.contains("свободен"), out);
    }

    @Test
    void freeRoomsAreCounted() {
        Plot r1 = room("101", 1, 20);
        room("102", 7, 25);
        assertEquals(2, plugin.hotels().freeRooms(hotel));
        plugin.service().rent(alex, r1, 3);
        assertEquals(1, plugin.hotels().freeRooms(hotel));
        assertTrue(plugin.hotels().hasOccupiedRooms(hotel));
    }

    @Test
    void theRoomsMenuRentsARoomWithClicks() {
        Plot r = room("101", 1, 20);
        new ru.industryreborn.gui.PlotMenu(plugin, alex, hotel, null).open();
        assertEquals(Material.RED_BED, alex.getOpenInventory().getTopInventory().getItem(20).getType());

        new ru.industryreborn.gui.RoomsMenu(plugin, alex, hotel, null, 0).open();
        assertEquals(Material.LIME_BED, alex.getOpenInventory().getTopInventory().getItem(0).getType());
        alex.simulateInventoryClick(0);                     // открывает карточку номера
        assertTrue(alex.getOpenInventory().getTopInventory().getHolder() instanceof ru.industryreborn.gui.PlotMenu);
        assertTrue(r.isFree());
    }

    @Test
    void freePlotsListIncludesFreeRoomsButNotTheirHotelAsAnAdminBuilding() {
        Plot r = room("101", 1, 20);
        var visible = new ru.industryreborn.service.PlotFilter(ru.industryreborn.service.PlotFilter.Mode.FREE)
                .apply(plugin.plots().all(), alex.getUniqueId());
        assertTrue(visible.contains(r));
        assertTrue(visible.contains(hotel), "отель можно арендовать или купить как предприятие");
    }

    @Test
    void roomRentIsPaidEvenWhenTheGuestsWalletIsTight() {
        Plot r = room("101", 1, 20);
        economy.set(alex, 99);
        assertFalse(plugin.service().rent(alex, r, 5));
        assertTrue(r.isFree());
        assertEquals(99, economy.getBalance(alex), 1e-9);
    }
}
