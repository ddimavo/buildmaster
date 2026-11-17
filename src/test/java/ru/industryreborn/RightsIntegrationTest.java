package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.PlotService;
import ru.industryreborn.support.PluginTestBase;

/** Права: строительство, контейнеры, станки, публичный режим. */
class RightsIntegrationTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock alex;
    private Plot plot;

    @BeforeEach
    void setUp() {
        steve = player("Steve", 100_000);
        alex = player("Alex", 100_000);
        plot = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        plugin.service().rent(steve, plot, 5);
    }

    private boolean canUse(PlayerMock who, Material m) {
        Block b = server.getWorld("world").getBlockAt(4, 4, 4);
        b.setType(m);
        PlayerInteractEvent e = new PlayerInteractEvent(who, Action.RIGHT_CLICK_BLOCK, null, b, BlockFace.NORTH,
                EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        return e.useInteractedBlock() != Event.Result.DENY && !e.isCancelled();
    }

    private boolean canBuild(PlayerMock who) {
        Block b = server.getWorld("world").getBlockAt(3, 3, 3);
        b.setType(Material.OAK_PLANKS);
        BlockPlaceEvent e = new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0),
                new ItemStack(Material.OAK_PLANKS), who, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        return !e.isCancelled();
    }

    @Test
    void ownerCanDoEverything() {
        assertTrue(canUse(steve, Material.CHEST));
        assertTrue(canUse(steve, Material.ANVIL));
        assertTrue(canUse(steve, Material.FURNACE));
        assertTrue(canBuild(steve));
    }

    @Test
    void strangerCanDoNothingExceptPublicBlocks() {
        assertFalse(canUse(alex, Material.CHEST));
        assertFalse(canUse(alex, Material.ANVIL));
        assertFalse(canUse(alex, Material.FURNACE));
        assertFalse(canBuild(alex));
        assertTrue(canUse(alex, Material.OAK_DOOR));
        assertTrue(canUse(alex, Material.CRAFTING_TABLE));
    }

    @Test
    void containerRightOpensChestsButNotStationsOrBuilding() {
        plot.grant(alex.getUniqueId(), Plot.Perm.CONTAINERS);
        assertTrue(canUse(alex, Material.CHEST));
        assertTrue(canUse(alex, Material.BARREL));
        assertFalse(canUse(alex, Material.FURNACE));
        assertFalse(canUse(alex, Material.ANVIL));
        assertFalse(canBuild(alex));
    }

    @Test
    void stationRightUsesStationsButNotChests() {
        plot.grant(alex.getUniqueId(), Plot.Perm.STATIONS);
        assertTrue(canUse(alex, Material.FURNACE));
        assertTrue(canUse(alex, Material.ANVIL));
        assertTrue(canUse(alex, Material.ENCHANTING_TABLE));
        assertTrue(canUse(alex, Material.BREWING_STAND));
        assertFalse(canUse(alex, Material.CHEST));
        assertFalse(canBuild(alex));
    }

    @Test
    void buildRightDoesNotImplyOtherRights() {
        plot.grant(alex.getUniqueId(), Plot.Perm.BUILD);
        assertTrue(canBuild(alex));
        assertFalse(canUse(alex, Material.CHEST));
        assertFalse(canUse(alex, Material.ANVIL));
    }

    @Test
    void trustCommandGivesAllThree() {
        steve.performCommand("district trust Alex w");
        assertTrue(canBuild(alex));
        assertTrue(canUse(alex, Material.CHEST));
        assertTrue(canUse(alex, Material.ANVIL));
    }

    @Test
    void permCommandTogglesIndividualRights() {
        steve.performCommand("district perm Alex stations on w");
        assertTrue(plot.can(alex.getUniqueId(), Plot.Perm.STATIONS));
        assertFalse(plot.can(alex.getUniqueId(), Plot.Perm.CONTAINERS));

        steve.performCommand("district perm Alex containers on w");
        steve.performCommand("district perm Alex stations off w");
        assertTrue(plot.can(alex.getUniqueId(), Plot.Perm.CONTAINERS));
        assertFalse(plot.can(alex.getUniqueId(), Plot.Perm.STATIONS));
        assertEquals(Journal.Action.RIGHTS, plugin.journal().forPlayer(alex.getUniqueId()).get(0).action());
    }

    @Test
    void onlyTheManagerCanGrantRights() {
        PlayerMock mallory = player("Mallory", 0);
        mallory.performCommand("district perm Mallory build on w");
        assertFalse(plot.can(mallory.getUniqueId(), Plot.Perm.BUILD));
        alex.performCommand("district trust Alex w");
        assertFalse(plot.can(alex.getUniqueId(), Plot.Perm.BUILD));
    }

    @Test
    void helperCannotHandOutRightsToOthers() {
        steve.performCommand("district trust Alex w");
        PlayerMock mallory = player("Mallory", 0);
        alex.performCommand("district perm Mallory build on w");
        assertFalse(plot.can(mallory.getUniqueId(), Plot.Perm.BUILD));
    }

    // ------------------------------------------------------------ публичный режим

    @Test
    void publicStationsAreAllowedInWorkshopsByDefault() {
        assertNull(plugin.service().setPublic(plot, Plot.Perm.STATIONS, true));
        assertTrue(canUse(alex, Material.ANVIL));
        assertTrue(canUse(alex, Material.FURNACE));
        assertFalse(canUse(alex, Material.CHEST), "контейнеры остались закрыты");
        assertFalse(canBuild(alex));
    }

    @Test
    void publicModeIsForbiddenForTypesWhereAdminDidNotAllowIt() {
        Plot shop = addPlot("s", Category.SHOP, 20, 100, -1);
        plugin.service().buy(steve, shop);
        String error = plugin.service().setPublic(shop, Plot.Perm.CONTAINERS, true);
        assertNotNull(error);
        assertTrue(error.contains("запрещён администратором"));
        assertTrue(shop.publicPerms.isEmpty());
    }

    @Test
    void adminCanAllowPublicModeForAType() {
        plugin.getConfig().set("public-permissions.shop", List.of("containers"));
        Plot shop = addPlot("s", Category.SHOP, 20, 100, -1);
        plugin.service().buy(steve, shop);
        assertNull(plugin.service().setPublic(shop, Plot.Perm.CONTAINERS, true));
        assertTrue(shop.can(alex.getUniqueId(), Plot.Perm.CONTAINERS));
        assertNotNull(plugin.service().setPublic(shop, Plot.Perm.STATIONS, true), "станки не разрешены");
    }

    @Test
    void buildCanNeverBePublic() {
        plugin.getConfig().set("public-permissions.workshop", List.of("build", "stations"));
        assertNotNull(plugin.service().setPublic(plot, Plot.Perm.BUILD, true));
        assertFalse(plot.can(alex.getUniqueId(), Plot.Perm.BUILD));
    }

    @Test
    void closingPublicModeTakesAccessBack() {
        plugin.service().setPublic(plot, Plot.Perm.STATIONS, true);
        plugin.service().setPublic(plot, Plot.Perm.STATIONS, false);
        assertFalse(canUse(alex, Material.ANVIL));
    }

    @Test
    void publicCommand() {
        steve.performCommand("district perm public stations on w");
        assertTrue(plot.publicPerms.contains(Plot.Perm.STATIONS));
        steve.performCommand("district perm public containers on w");
        assertFalse(plot.publicPerms.contains(Plot.Perm.CONTAINERS), "для мастерской контейнеры публичными быть не могут");
    }

    // ------------------------------------------------------------ жизненный цикл

    @Test
    void rightsAreDroppedWhenTheManagerChanges() {
        plot.grantAll(alex.getUniqueId());
        plot.publicPerms.add(Plot.Perm.STATIONS);

        plugin.service().evict(plot, new PlotService.Release("Boss", "причина", Journal.Action.EVICTED, false,
                Plot.RestoreMode.ITEMS_ONLY));

        assertTrue(plot.grants.isEmpty());
        assertTrue(plot.publicPerms.isEmpty());
    }

    @Test
    void rightsSurviveRestart() {
        plot.grant(alex.getUniqueId(), Plot.Perm.CONTAINERS, Plot.Perm.STATIONS);
        plot.publicPerms.add(Plot.Perm.STATIONS);
        plugin.plots().save();

        plugin.plots().load();
        Plot loaded = plugin.plots().get("w");

        assertTrue(loaded.can(alex.getUniqueId(), Plot.Perm.CONTAINERS));
        assertTrue(loaded.can(alex.getUniqueId(), Plot.Perm.STATIONS));
        assertFalse(loaded.can(alex.getUniqueId(), Plot.Perm.BUILD));
        assertTrue(loaded.publicPerms.contains(Plot.Perm.STATIONS));
    }

    @Test
    void oldTrustedListIsMigratedToFullRights() {
        var yml = new org.bukkit.configuration.file.YamlConfiguration();
        yml.set("plots.old.world", "world");
        yml.set("plots.old.box", "100,0,0,109,9,9");
        yml.set("plots.old.trusted", List.of(alex.getUniqueId().toString()));
        try {
            yml.save(new java.io.File(plugin.getDataFolder(), "plots.yml"));
        } catch (java.io.IOException e) {
            fail(e);
        }
        plugin.plots().load();
        Plot old = plugin.plots().get("old");
        assertTrue(old.can(alex.getUniqueId(), Plot.Perm.BUILD));
        assertTrue(old.can(alex.getUniqueId(), Plot.Perm.CONTAINERS));
        assertTrue(old.can(alex.getUniqueId(), Plot.Perm.STATIONS));
    }

    @Test
    void myPlotsListIncludesPlotsWhereIHaveAnyRight() {
        plot.grant(alex.getUniqueId(), Plot.Perm.CONTAINERS);
        var filter = new ru.industryreborn.service.PlotFilter(ru.industryreborn.service.PlotFilter.Mode.MINE);
        assertEquals(1, filter.apply(plugin.plots().all(), alex.getUniqueId()).size());
    }

    @Test
    void helperBlocksStillReturnToTheirPlacer() {
        plot.grant(alex.getUniqueId(), Plot.Perm.BUILD);
        assertTrue(canBuild(alex));
        assertEquals(alex.getUniqueId(), plugin.ledgers().get("w").get(3, 3, 3).player());
    }
}
