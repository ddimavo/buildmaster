package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Request;
import ru.buildmaster.service.Journal;
import ru.buildmaster.support.PluginTestBase;

/** Позиция голограммы: игрок предлагает, администратор одобряет; проверки точки и устаревшие заявки. */
@SuppressWarnings("removal")
class HologramProposalTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock alex;
    private PlayerMock boss;
    private Plot house;

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    private Location at(double x, double y, double z) {
        return new Location(server.getWorld("world"), x, y, z);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDownSeams() {
        ru.buildmaster.util.ChunkJob.setLoader(null);
        ru.buildmaster.util.ChunkAccess.setSource(null);
    }

    @BeforeEach
    void setUp() {
        ru.buildmaster.util.ChunkJob.setLoader((world, cx, cz, cb) -> cb.accept(world.getChunkAt(cx, cz), null));
        ru.buildmaster.util.ChunkAccess.setSource(new ru.buildmaster.util.ChunkAccess.Source() {
            @Override
            public java.util.List<org.bukkit.block.BlockState> tileEntities(org.bukkit.Chunk chunk) {
                return java.util.List.of();
            }

            @Override
            public java.util.Collection<org.bukkit.entity.Entity> entities(org.bukkit.Chunk chunk) {
                return java.util.List.of();
            }
        });
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.house", 0);
        steve = player("Steve", 10_000);
        alex = player("Alex", 10_000);
        boss = admin("Boss");
        house = addPlot("h", Category.HOUSE, 0, 100, -1);
        assertTrue(plugin.service().buy(steve, house));
    }

    private Request pending() {
        assertEquals(1, plugin.moderation().pending().size());
        return plugin.moderation().pending().get(0);
    }

    @Test
    void aManagersProposalWaitsForApprovalAndTheOldPositionStays() {
        house.hasHolo = true;
        house.holoX = 1;
        house.holoY = 2;
        house.holoZ = 3;
        assertNull(plugin.moderation().submitHolo(steve, house, at(5.5, 2.25, 6.5)));
        assertEquals(Request.Type.HOLO, pending().type);
        assertEquals(1, house.holoX, 1e-9, "до одобрения позиция прежняя");
        assertTrue(drain(boss).contains("голограммы"), "администратору пришло уведомление");
    }

    @Test
    void approvalMovesTheHologram() {
        plugin.moderation().submitHolo(steve, house, at(5.5, 2.25, 6.5));
        plugin.moderation().approve(pending(), "Boss");
        assertTrue(house.hasHolo);
        assertEquals(5.5, house.holoX, 1e-9);
        assertEquals(2.25, house.holoY, 1e-9);
        assertEquals(6.5, house.holoZ, 1e-9);
        assertTrue(plugin.journal().forPlot("h").stream().anyMatch(e -> e.action() == Journal.Action.HOLO));
        assertTrue(plugin.moderation().pending().isEmpty());
    }

    @Test
    void rejectionKeepsThePositionAndExplainsWhy() {
        plugin.moderation().submitHolo(steve, house, at(5, 2, 6));
        plugin.moderation().reject(pending(), "Boss", "закрывает вход");
        assertFalse(house.hasHolo);
        assertTrue(drain(steve).contains("закрывает вход"));
        assertTrue(plugin.journal().forPlot("h").stream().anyMatch(e -> e.action() == Journal.Action.HOLO
                && e.reason().contains("закрывает вход")));
    }

    @Test
    void aNewProposalReplacesThePreviousPendingOne() {
        plugin.moderation().submitHolo(steve, house, at(1, 2, 3));
        plugin.moderation().submitHolo(steve, house, at(7, 2, 3));
        Request r = pending();
        plugin.moderation().approve(r, "Boss");
        assertEquals(7, house.holoX, 1e-9);
    }

    @Test
    void theSpotMustBeInsideTheOwnPlot() {
        assertNotNull(plugin.moderation().submitHolo(steve, house, at(50, 2, 2)), "вне участка");
        assertNotNull(plugin.moderation().submitHolo(steve, house, at(5, 50, 5)), "выше участка");
        assertNotNull(plugin.moderation().submitHolo(steve, house, new Location(null, 5, 2, 5)), "нет мира");
        assertTrue(plugin.moderation().pending().isEmpty());
    }

    @Test
    void aHotelRoomInteriorBelongsToTheGuestNotToTheHotelHologram() {
        Plot hotel = addPlot("hot", Category.HOTEL, 20, 100, -1);
        plugin.service().buy(steve, hotel);
        Plot room = new Plot("hot-1", "world", new Box(22, 1, 1, 25, 3, 4));
        room.category = Category.ROOM;
        room.parentId = "hot";
        plugin.plots().add(room);
        assertNotNull(plugin.moderation().submitHolo(steve, hotel, at(23, 2, 2)));
        assertNull(plugin.moderation().submitHolo(steve, hotel, at(21, 2, 8)), "коридор отеля - можно");
    }

    @Test
    void onlyTheManagerCanPropose() {
        assertNotNull(plugin.moderation().submitHolo(alex, house, at(5, 2, 5)));
        Plot free = addPlot("f", Category.SHOP, 20, 100, -1);
        assertNotNull(plugin.moderation().submitHolo(steve, free, at(25, 2, 5)), "свободный участок");
        assertTrue(plugin.moderation().pending().isEmpty());
    }

    @Test
    void anAdminsPositionAppliesImmediately() {
        assertNull(plugin.moderation().submitHolo(boss, house, at(4, 3, 4)));
        assertTrue(house.hasHolo);
        assertEquals(4, house.holoX, 1e-9);
        assertTrue(plugin.moderation().pending().isEmpty());
    }

    @Test
    void aStaleProposalIsRejectedWhenTheManagerChanged() {
        plugin.moderation().submitHolo(steve, house, at(5, 2, 5));
        plugin.service().evict(house, new ru.buildmaster.service.PlotService.Release("Boss", "тест",
                Journal.Action.EVICTED, false, Plot.RestoreMode.ITEMS_ONLY));
        server.getScheduler().performTicks(20);
        assertTrue(plugin.service().buy(alex, house));
        Request r = pending();
        plugin.moderation().approve(r, "Boss");
        assertFalse(house.hasHolo, "позиция от прежнего управляющего не применена");
        assertEquals(Request.Status.REJECTED, r.status);
        assertTrue(r.reason.contains("устарела") && r.reason.contains("сменил"), r.reason);
        assertTrue(drain(steve).contains("не применена"));
    }

    @Test
    void aProposalForADeletedPlotIsRejectedSafely() {
        plugin.moderation().submitHolo(steve, house, at(5, 2, 5));
        Request r = pending();
        plugin.plots().remove(house);
        plugin.moderation().approve(r, "Boss");
        assertEquals(Request.Status.REJECTED, r.status);
    }

    @Test
    void theLandlordOfAPlayerPlotAppliesTheirOwnPosition() {
        Plot pp = new Plot("pp", "world", new Box(40, 0, 0, 49, 9, 9));
        pp.kind = Plot.Kind.PLAYER;
        pp.landlord = alex.getUniqueId();
        pp.approval = Plot.Approval.APPROVED;
        plugin.plots().add(pp);
        assertNull(plugin.moderation().submitHolo(alex, pp, at(45, 2, 5)));
        assertEquals(Request.Type.HOLO, pending().type, "арендодателю тоже нужно одобрение");
    }

    @Test
    void theCommandAndTheMenuSubmitFromThePlayersPosition() {
        steve.teleport(at(5, 1, 5));
        steve.performCommand("district hologram h");
        assertEquals(1, plugin.moderation().pending().size());
        drain(steve);
        steve.performCommand("district hologram");           // без номера: участок под ногами
        assertEquals(1, plugin.moderation().pending().size(), "вытеснила прежнюю");
        steve.teleport(at(60, 1, 5));
        steve.performCommand("district hologram h");
        assertTrue(drain(steve).contains("внутри"));
        new ru.buildmaster.gui.PlotMenu(plugin, steve, house, null).open();
        assertEquals(Material.ENDER_EYE, steve.getOpenInventory().getTopInventory().getItem(17).getType());
    }

    @Test
    void theModerationMenuShowsTheProposalAndTeleportsToIt() {
        plugin.moderation().submitHolo(steve, house, at(5.5, 2, 6.5));
        new ru.buildmaster.gui.ModerationMenu(plugin, boss, 0).open();
        assertEquals(Material.ENDER_EYE, boss.getOpenInventory().getTopInventory().getItem(0).getType());
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryClickEvent(boss.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0,
                org.bukkit.event.inventory.ClickType.SHIFT_LEFT, org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY));
        assertEquals(5.5, boss.getLocation().getX(), 1e-6);
        assertEquals(6.5, boss.getLocation().getZ(), 1e-6);
    }

    @Test
    void theProposalSurvivesARestart() {
        plugin.moderation().submitHolo(steve, house, at(5.5, 2, 6.5));
        plugin.moderation().save();
        plugin.moderation().load();
        Request r = pending();
        assertEquals(Request.Type.HOLO, r.type);
        plugin.moderation().approve(r, "Boss");
        assertEquals(5.5, house.holoX, 1e-9);
    }
}
