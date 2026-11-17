package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import ru.industryreborn.model.Request;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.RegionAccess;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.util.ChunkAccess;
import ru.industryreborn.util.ChunkJob;

/** Участки игроков на регионах WorldGuard (WorldGuard подменён). */
class PlayerPlotsTest extends PluginTestBase {

    private static final class FakeRegions implements RegionAccess {
        boolean available = true;
        boolean failWrites = false;
        final Map<String, RegionInfo> regions = new HashMap<>();

        void add(String id, Box box, boolean cuboid, Set<UUID> owners, Set<UUID> members) {
            regions.put(id, new RegionInfo(id, cuboid, box, new HashSet<>(owners), new HashSet<>(members)));
        }

        RegionInfo get(String id) {
            return regions.get(id);
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public RegionInfo find(World world, String regionId) {
            return regions.get(regionId);
        }

        @Override
        public boolean setDomains(World world, String regionId, Set<UUID> owners, Set<UUID> members) {
            RegionInfo old = regions.get(regionId);
            if (failWrites || old == null) {
                return false;
            }
            regions.put(regionId, new RegionInfo(regionId, old.cuboid(), old.box(), new HashSet<>(owners), new HashSet<>(members)));
            return true;
        }

        @Override
        public List<String> regionsOwnedBy(World world, UUID player) {
            return List.of();
        }
    }

    private FakeRegions wg;
    private PlayerMock lord;    // арендодатель
    private PlayerMock tenant;
    private PlayerMock buyer;
    private PlayerMock partner; // совладелец региона
    private PlayerMock boss;

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
                            if (b.getType() == Material.CHEST) {
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
        wg = new FakeRegions();
        plugin.setRegions(wg);
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.house", 0);
        lord = player("Lord", 10_000);
        tenant = player("Tenant", 10_000);
        buyer = player("Buyer", 10_000);
        partner = player("Partner", 10_000);
        boss = admin("Boss");
    }

    @AfterEach
    void tearDownSeams() {
        ChunkJob.setLoader(null);
        ChunkAccess.setSource(null);
    }

    private void ticks() {
        server.getScheduler().performTicks(20);
    }

    private Block block(int x, int y, int z) {
        return server.getWorld("world").getBlockAt(x, y, z);
    }

    private static Box box(int x) {
        return new Box(x, 0, 0, x + 9, 9, 9);   // 1000 блоков
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

    /** Регион Lord'а привязан, одобрен и свободен. */
    private Plot listed(String id, int x) {
        wg.add(id, box(x), true, Set.of(lord.getUniqueId()), Set.of());
        assertNull(plugin.playerPlots().bind(lord, id, Category.HOUSE));
        Plot plot = plugin.plots().get(id);
        plugin.playerPlots().approve(plot);
        return plot;
    }

    // ------------------------------------------------------------ привязка

    @Test
    void bindCreatesAPendingPlayerPlot() {
        wg.add("home", box(0), true, Set.of(lord.getUniqueId()), Set.of());
        assertNull(plugin.playerPlots().bind(lord, "home", Category.HOUSE));
        Plot p = plugin.plots().get("home");
        assertNotNull(p);
        assertTrue(p.isPlayerPlot());
        assertEquals(Plot.Approval.PENDING, p.approval);
        assertEquals(lord.getUniqueId(), p.landlord);
        assertTrue(p.isFree());
        assertEquals(-1, p.price, 1e-9);
        assertEquals(-1, p.rentPerDay, 1e-9);
        assertEquals(1, plugin.moderation().pending().size());
        assertEquals(Request.Type.PLOT, plugin.moderation().pending().get(0).type);
    }

    @Test
    void bindValidatesTheRegion() {
        assertNotNull(plugin.playerPlots().bind(lord, "none", Category.HOUSE), "региона нет");
        wg.add("c", box(0), false, Set.of(lord.getUniqueId()), Set.of());
        assertNotNull(plugin.playerPlots().bind(lord, "c", Category.HOUSE), "не cuboid");
        wg.add("o", box(20), true, Set.of(tenant.getUniqueId()), Set.of(lord.getUniqueId()));
        assertNotNull(plugin.playerPlots().bind(lord, "o", Category.HOUSE), "игрок только участник, не владелец");
        wg.add("bad id", box(40), true, Set.of(lord.getUniqueId()), Set.of());
        assertNotNull(plugin.playerPlots().bind(lord, "bad id", Category.HOUSE), "недопустимое имя");
        wg.add("tiny", new Box(60, 0, 0, 61, 1, 1), true, Set.of(lord.getUniqueId()), Set.of());
        assertNotNull(plugin.playerPlots().bind(lord, "tiny", Category.HOUSE), "слишком маленький");
        wg.add("ok", box(80), true, Set.of(lord.getUniqueId()), Set.of());
        assertNotNull(plugin.playerPlots().bind(lord, "ok", Category.SHOP), "магазины создаёт администрация");
        assertNotNull(plugin.playerPlots().bind(lord, "ok", Category.WORKSHOP));
        assertNotNull(plugin.playerPlots().bind(lord, "ok", null));
        assertNull(plugin.playerPlots().bind(lord, "ok", Category.WAREHOUSE));
        assertNotNull(plugin.playerPlots().bind(lord, "ok", Category.HOUSE), "уже привязан");
    }

    @Test
    void bindRefusesWithoutWorldGuardOrWhenDisabled() {
        wg.add("home", box(0), true, Set.of(lord.getUniqueId()), Set.of());
        wg.available = false;
        assertNotNull(plugin.playerPlots().bind(lord, "home", Category.HOUSE));
        wg.available = true;
        plugin.getConfig().set("player-plots.enabled", false);
        assertNotNull(plugin.playerPlots().bind(lord, "home", Category.HOUSE));
    }

    @Test
    void bindRefusesOverlapWithAnAdministrativePlot() {
        addPlot("a1", Category.SHOP, 0, 100, -1);
        wg.add("home", box(5), true, Set.of(lord.getUniqueId()), Set.of());
        String error = plugin.playerPlots().bind(lord, "home", Category.HOUSE);
        assertNotNull(error);
        assertTrue(error.contains("a1"));
    }

    @Test
    void perPlayerLimitIsEnforced() {
        plugin.getConfig().set("player-plots.max-per-player", 1);
        wg.add("a", box(0), true, Set.of(lord.getUniqueId()), Set.of());
        wg.add("b", box(20), true, Set.of(lord.getUniqueId()), Set.of());
        assertNull(plugin.playerPlots().bind(lord, "a", Category.HOUSE));
        assertNotNull(plugin.playerPlots().bind(lord, "b", Category.HOUSE));
    }

    @Test
    void anAdministratorsPlotNeedsNoApproval() {
        wg.add("adm", box(0), true, Set.of(boss.getUniqueId()), Set.of());
        assertNull(plugin.playerPlots().bind(boss, "adm", Category.HOUSE));
        assertEquals(Plot.Approval.APPROVED, plugin.plots().get("adm").approval);
        assertTrue(plugin.moderation().pending().isEmpty());
    }

    // ------------------------------------------------------------ одобрение

    @Test
    void approvalPublishesThePlot() {
        wg.add("home", box(0), true, Set.of(lord.getUniqueId()), Set.of());
        plugin.playerPlots().bind(lord, "home", Category.HOUSE);
        plugin.moderation().approve(plugin.moderation().pending().get(0), "Boss");
        assertEquals(Plot.Approval.APPROVED, plugin.plots().get("home").approval);
    }

    @Test
    void rejectionRemovesThePlotAndExplainsWhy() {
        wg.add("home", box(0), true, Set.of(lord.getUniqueId()), Set.of());
        plugin.playerPlots().bind(lord, "home", Category.HOUSE);
        plugin.moderation().reject(plugin.moderation().pending().get(0), "Boss", "не по правилам");
        assertNull(plugin.plots().get("home"));
        assertTrue(plugin.journal().forPlot("home").stream().anyMatch(e -> e.reason().contains("не по правилам")));
        assertTrue(drain(lord).contains("не по правилам"));
    }

    @Test
    void anUnapprovedPlotCannotBeListedOrRented() {
        wg.add("home", box(0), true, Set.of(lord.getUniqueId()), Set.of());
        plugin.playerPlots().bind(lord, "home", Category.HOUSE);
        Plot p = plugin.plots().get("home");
        assertNotNull(plugin.playerPlots().setListing(lord, p, 100.0, 10.0));
        p.rentPerDay = 10;
        assertFalse(plugin.service().rent(tenant, p, 1));
        p.price = 10;
        assertFalse(plugin.service().buy(tenant, p));
        assertEquals(10_000, economy.getBalance(tenant), 1e-9);
    }

    // ------------------------------------------------------------ согласия и цены

    @Test
    void coOwnersMustConsentBeforeAListing() {
        wg.add("home", box(0), true, Set.of(lord.getUniqueId(), partner.getUniqueId()), Set.of());
        plugin.playerPlots().bind(lord, "home", Category.HOUSE);
        Plot p = plugin.plots().get("home");
        plugin.playerPlots().approve(p);
        assertEquals(Set.of(partner.getUniqueId()), p.requiredConsents);
        assertNotNull(plugin.playerPlots().setListing(lord, p, 500.0, null));
        assertNotNull(plugin.playerPlots().consent(tenant, p), "посторонний");
        assertNull(plugin.playerPlots().consent(partner, p));
        assertNotNull(plugin.playerPlots().consent(partner, p), "повторно");
        assertNull(plugin.playerPlots().setListing(lord, p, 500.0, null));
        assertEquals(500, p.price, 1e-9);
    }

    @Test
    void rentIsCappedBySize() {
        Plot p = listed("home", 0);          // 1000 блоков: предел 20
        assertEquals(20, plugin.playerPlots().rentCap(p), 1e-9);
        assertNotNull(plugin.playerPlots().setListing(lord, p, null, 21.0));
        assertNull(plugin.playerPlots().setListing(lord, p, null, 20.0));
        assertEquals(20, p.rentPerDay, 1e-9);
        assertNull(plugin.playerPlots().setListing(lord, p, null, -1.0), "снять с аренды");
        assertEquals(-1, p.rentPerDay, 1e-9);
    }

    @Test
    void onlyTheLandlordManagesTheListing() {
        Plot p = listed("home", 0);
        assertNotNull(plugin.playerPlots().setListing(tenant, p, 100.0, null));
        assertNull(plugin.playerPlots().setListing(boss, p, 100.0, null), "администратор может");
    }

    @Test
    void sharedAccessNeedsABigPlot() {
        Plot p = listed("home", 0);           // 1000 < 2000
        assertNotNull(plugin.playerPlots().setSharedAccess(lord, p, true));
        plugin.getConfig().set("player-plots.shared-access-min-volume", 500);
        assertNull(plugin.playerPlots().setSharedAccess(lord, p, true));
        assertTrue(p.sharedAccess);
    }

    @Test
    void commandsWork() {
        wg.add("home", box(0), true, Set.of(lord.getUniqueId()), Set.of());
        lord.performCommand("district region bind home house");
        Plot p = plugin.plots().get("home");
        assertNotNull(p);
        plugin.playerPlots().approve(p);
        lord.performCommand("district region rent home 15");
        lord.performCommand("district region sale home 700");
        assertEquals(15, p.rentPerDay, 1e-9);
        assertEquals(700, p.price, 1e-9);
        drain(lord);
        lord.performCommand("district region rent home 9999");
        assertEquals(15, p.rentPerDay, 1e-9);
        lord.performCommand("district region list");
        assertTrue(drain(lord).contains("home"));
        lord.performCommand("district region unbind home");
        assertNull(plugin.plots().get("home"));
    }

    // ------------------------------------------------------------ продажа

    @Test
    void saleMovesMoneyAndTheRegion() {
        Plot p = listed("home", 0);
        plugin.playerPlots().setListing(lord, p, 700.0, null);
        assertTrue(plugin.service().buy(buyer, p));
        assertEquals(10_700, economy.getBalance(lord), 1e-9);
        assertEquals(9_300, economy.getBalance(buyer), 1e-9);
        assertEquals(Set.of(buyer.getUniqueId()), wg.get("home").owners());
        assertTrue(wg.get("home").members().isEmpty());
        assertNull(plugin.plots().get("home"), "после продажи участок больше не в районе");
    }

    @Test
    void saleFailsCleanlyWhenTheRegionCannotBeTransferred() {
        Plot p = listed("home", 0);
        plugin.playerPlots().setListing(lord, p, 700.0, null);
        wg.failWrites = true;
        assertFalse(plugin.service().buy(buyer, p));
        assertEquals(10_000, economy.getBalance(buyer), 1e-9);
        assertEquals(10_000, economy.getBalance(lord), 1e-9);
        assertNotNull(plugin.plots().get("home"));
    }

    @Test
    void youCannotBuyWithoutMoneyOrYourOwnPlot() {
        Plot p = listed("home", 0);
        plugin.playerPlots().setListing(lord, p, 70_000.0, null);
        assertFalse(plugin.service().buy(buyer, p));
        plugin.playerPlots().setListing(lord, p, 100.0, null);
        assertFalse(plugin.service().buy(lord, p));
        assertEquals(Set.of(lord.getUniqueId()), wg.get("home").owners());
    }

    // ------------------------------------------------------------ аренда

    private Plot rentable() {
        Plot p = listed("home", 0);
        assertNull(plugin.playerPlots().setListing(lord, p, null, 20.0));
        return p;
    }

    @Test
    void rentHandsTheRegionToTheTenantAndPaysTheLandlord() {
        Plot p = rentable();
        assertTrue(plugin.service().rent(tenant, p, 5));
        assertEquals(10_100, economy.getBalance(lord), 1e-9);
        assertEquals(9_900, economy.getBalance(tenant), 1e-9);
        assertEquals(Set.of(tenant.getUniqueId()), wg.get("home").owners());
        assertTrue(wg.get("home").members().isEmpty());
        assertEquals(0, plugin.treasury().balance(), 1e-9, "в казну аренда не идёт");
    }

    @Test
    void rentTaxIsTakenFromTheLandlordsIncome() {
        plugin.getConfig().set("bank.rent-tax-percent", 10);
        Plot p = rentable();
        plugin.service().rent(tenant, p, 5);
        assertEquals(10_090, economy.getBalance(lord), 1e-6);
    }

    @Test
    void sharedAccessKeepsTheLandlordAsAMember() {
        plugin.getConfig().set("player-plots.shared-access-min-volume", 500);
        Plot p = rentable();
        assertNull(plugin.playerPlots().setSharedAccess(lord, p, true));
        plugin.service().rent(tenant, p, 2);
        assertEquals(Set.of(lord.getUniqueId()), wg.get("home").members());
    }

    @Test
    void rentFailsWithoutMoneyAndLeavesTheRegionAlone() {
        Plot p = rentable();
        economy.set(tenant, 5);
        assertFalse(plugin.service().rent(tenant, p, 5));
        assertEquals(Set.of(lord.getUniqueId()), wg.get("home").owners());
        assertTrue(p.isFree());
    }

    @Test
    void rentFailsWhenWorldGuardRefusesAndTakesNoMoney() {
        Plot p = rentable();
        wg.failWrites = true;
        assertFalse(plugin.service().rent(tenant, p, 5));
        assertEquals(10_000, economy.getBalance(tenant), 1e-9);
        assertEquals(10_000, economy.getBalance(lord), 1e-9);
        assertTrue(p.isFree());
    }

    @Test
    void rentIsLimitedToFourteenDays() {
        Plot p = rentable();
        assertFalse(plugin.service().rent(tenant, p, 15));
        assertTrue(plugin.service().rent(tenant, p, 14));
    }

    @Test
    void endOfRentReturnsTheRegionAndKeepsCoOwnersAndMembers() {
        wg.add("home", box(0), true, Set.of(lord.getUniqueId(), partner.getUniqueId()), Set.of(buyer.getUniqueId()));
        plugin.playerPlots().bind(lord, "home", Category.HOUSE);
        Plot p = plugin.plots().get("home");
        plugin.playerPlots().approve(p);
        plugin.playerPlots().consent(partner, p);
        plugin.playerPlots().setListing(lord, p, null, 20.0);
        plugin.service().rent(tenant, p, 2);
        assertEquals(Set.of(tenant.getUniqueId()), wg.get("home").owners());

        plugin.service().release(tenant, p);
        ticks();

        assertTrue(p.isFree());
        assertEquals(Set.of(lord.getUniqueId(), partner.getUniqueId()), wg.get("home").owners());
        assertEquals(Set.of(buyer.getUniqueId()), wg.get("home").members());
    }

    @Test
    void anExpiredRentReturnsTheRegion() {
        Plot p = rentable();
        plugin.service().rent(tenant, p, 1);
        p.rentedUntil = System.currentTimeMillis() - 1000;
        p.graceUntil = 0;
        plugin.service().tick();
        ticks();
        assertTrue(p.isFree());
        assertEquals(Set.of(lord.getUniqueId()), wg.get("home").owners());
    }

    @Test
    void autoRenewPaysTheLandlordAgain() {
        Plot p = rentable();
        plugin.service().rent(tenant, p, 1);
        plugin.service().setAutoRenew(p, true);
        p.rentedUntil = System.currentTimeMillis() - 1000;
        plugin.service().tick();
        ticks();
        assertEquals(10_000 + 20 + 20 * 7, economy.getBalance(lord), 1e-6);
    }

    // ------------------------------------------------------------ вещи

    @Test
    void checkoutReturnsOnlyTheTenantsBuildsAndNeverTouchesTheLandlordsThings() {
        Plot p = rentable();
        // до аренды арендодатель строит и ставит сундук
        assertFalse(place(lord, block(1, 1, 1), Material.OAK_PLANKS).isCancelled());
        block(5, 1, 5).setType(Material.CHEST);
        ((Chest) block(5, 1, 5).getState()).getBlockInventory().addItem(new ItemStack(Material.GOLD_INGOT, 7));
        assertTrue(plugin.ledgers().get("home").entries().isEmpty(), "арендодателю книга учёта не нужна");

        plugin.service().rent(tenant, p, 3);
        assertFalse(place(tenant, block(2, 1, 2), Material.FURNACE).isCancelled());
        block(3, 1, 3).setType(Material.CHEST);
        ((Chest) block(3, 1, 3).getState()).getBlockInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        plugin.ledgers().get("home").place(3, 1, 3, tenant.getUniqueId(), "CHEST", false);

        plugin.service().release(tenant, p);
        ticks();

        assertEquals(Material.AIR, block(2, 1, 2).getType(), "печь арендатора убрана");
        assertEquals(Material.AIR, block(3, 1, 3).getType(), "сундук арендатора убран");
        assertEquals(1, stored(tenant.getUniqueId(), Material.FURNACE));
        assertEquals(1, stored(tenant.getUniqueId(), Material.CHEST));
        assertEquals(5, stored(tenant.getUniqueId(), Material.DIAMOND));
        assertEquals(Material.OAK_PLANKS, block(1, 1, 1).getType(), "постройка арендодателя цела");
        assertEquals(Material.CHEST, block(5, 1, 5).getType());
        assertEquals(7, ((Chest) block(5, 1, 5).getState()).getBlockInventory().getItem(0).getAmount());
        assertEquals(0, stored(lord.getUniqueId(), Material.GOLD_INGOT) + stored(lord.getUniqueId(), Material.OAK_PLANKS));
        assertTrue(plugin.ledgers().get("home").entries().isEmpty());
    }

    @Test
    void aPlayerPlotCannotBeFullyReset() {
        Plot p = rentable();
        plugin.service().rent(tenant, p, 3);
        assertNotNull(plugin.service().reset(p, "Boss", "тест"));
    }

    @Test
    void adminEvictionAlsoReturnsTheRegion() {
        Plot p = rentable();
        plugin.service().rent(tenant, p, 3);
        plugin.service().evict(p, new ru.industryreborn.service.PlotService.Release("Boss", "нарушение",
                ru.industryreborn.service.Journal.Action.SEIZED, false, Plot.RestoreMode.FULL));
        ticks();
        assertTrue(p.isFree());
        assertEquals(Set.of(lord.getUniqueId()), wg.get("home").owners());
    }

    // ------------------------------------------------------------ защита

    @Test
    void aTenantBreaksOnlyWhatTheyBuilt() {
        Plot p = rentable();
        place(lord, block(1, 1, 1), Material.OAK_PLANKS);
        plugin.service().rent(tenant, p, 3);
        assertTrue(breakBlock(tenant, block(1, 1, 1)).isCancelled(), "блок арендодателя");
        assertFalse(place(tenant, block(2, 1, 2), Material.STONE).isCancelled());
        assertFalse(breakBlock(tenant, block(2, 1, 2)).isCancelled(), "своё можно");
    }

    @Test
    void tenantBreakRestrictionCanBeDisabled() {
        plugin.getConfig().set("player-plots.tenant-break-own-only", false);
        Plot p = rentable();
        place(lord, block(1, 1, 1), Material.OAK_PLANKS);
        plugin.service().rent(tenant, p, 3);
        assertFalse(breakBlock(tenant, block(1, 1, 1)).isCancelled());
    }

    @Test
    void aTenantCannotOpenTheLandlordsChestsAndViceVersa() {
        plugin.getConfig().set("player-plots.shared-access-min-volume", 500);
        Plot p = rentable();
        plugin.playerPlots().setSharedAccess(lord, p, true);
        block(5, 1, 5).setType(Material.CHEST);     // сундук арендодателя
        plugin.service().rent(tenant, p, 3);
        block(3, 1, 3).setType(Material.CHEST);
        plugin.ledgers().get("home").place(3, 1, 3, tenant.getUniqueId(), "CHEST", false);

        assertTrue(click(tenant, block(5, 1, 5)).useInteractedBlock() == org.bukkit.event.Event.Result.DENY
                || click(tenant, block(5, 1, 5)).isCancelled(), "арендатор - не в чужой сундук");
        assertTrue(click(lord, block(3, 1, 3)).isCancelled(), "арендодатель - не в сундук арендатора");
        assertFalse(click(tenant, block(3, 1, 3)).isCancelled());
    }

    @Test
    void theLandlordCannotBuildWhileTheTenantLivesThereUnlessAccessIsShared() {
        Plot p = rentable();
        plugin.service().rent(tenant, p, 3);
        assertTrue(place(lord, block(4, 1, 4), Material.STONE).isCancelled());
        plugin.playerPlots();
        p.sharedAccess = true;
        assertFalse(place(lord, block(4, 1, 5), Material.STONE).isCancelled());
        assertTrue(plugin.ledgers().get("home").entries().isEmpty() || plugin.ledgers().get("home").get(4, 1, 5) == null,
                "постройки арендодателя в книгу арендатора не пишутся");
    }

    @Test
    void theLandlordBuildsFreelyOnAFreePlot() {
        rentable();
        assertFalse(place(lord, block(4, 1, 4), Material.STONE).isCancelled());
        assertFalse(breakBlock(lord, block(4, 1, 4)).isCancelled());
        assertTrue(place(tenant, block(6, 1, 4), Material.STONE).isCancelled(), "чужому нельзя");
    }

    @Test
    void breakingALandlordsBlockStillDropsItBecausePlayerPlotsHaveNoAntiDupSnapshot() {
        Plot p = rentable();
        place(lord, block(1, 1, 1), Material.OAK_PLANKS);
        BlockBreakEvent e = breakBlock(lord, block(1, 1, 1));
        assertTrue(e.isDropItems());
    }

    // ------------------------------------------------------------ меню и отображение

    @Test
    void landlordSeesTheirPendingPlotButOthersDoNot() {
        wg.add("home", box(0), true, Set.of(lord.getUniqueId()), Set.of());
        plugin.playerPlots().bind(lord, "home", Category.HOUSE);
        drain(tenant);
        tenant.performCommand("district list");
        assertFalse(drain(tenant).contains("home"));
        drain(lord);
        lord.performCommand("district list");
        assertTrue(drain(lord).contains("home"));
    }

    // ------------------------------------------------------------ сохранение

    @Test
    void playerPlotFieldsSurviveAReload() {
        wg.add("home", box(0), true, Set.of(lord.getUniqueId(), partner.getUniqueId()), Set.of(buyer.getUniqueId()));
        plugin.playerPlots().bind(lord, "home", Category.HOUSE);
        Plot p = plugin.plots().get("home");
        plugin.playerPlots().approve(p);
        plugin.playerPlots().consent(partner, p);
        p.sharedAccess = true;
        plugin.plots().save();
        plugin.plots().load();
        Plot q = plugin.plots().get("home");
        assertTrue(q.isPlayerPlot());
        assertEquals(Plot.Approval.APPROVED, q.approval);
        assertEquals(lord.getUniqueId(), q.landlord);
        assertEquals("home", q.regionId);
        assertTrue(q.sharedAccess);
        assertEquals(Set.of(partner.getUniqueId()), q.requiredConsents);
        assertEquals(Set.of(partner.getUniqueId()), q.consents);
        assertEquals(Set.of(buyer.getUniqueId()), q.regionMembers);
    }

    @Test
    void hotelsOfPlayersCanBeBound() {
        wg.add("inn", box(0), true, Set.of(lord.getUniqueId()), Set.of());
        assertNull(plugin.playerPlots().bind(lord, "inn", Category.HOTEL));
        assertEquals(Category.HOTEL, plugin.plots().get("inn").category);
    }
}
