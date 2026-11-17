package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Request;
import ru.industryreborn.model.Station;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.support.PluginTestBase;

/** Зачарования для стола зачарований: предложение, одобрение администратором, стоимость, производство. */
class EnchantTest extends PluginTestBase {

    private final AtomicLong wall = new AtomicLong(1_000_000);
    private PlayerMock steve;
    private PlayerMock alex;
    private PlayerMock boss;
    private Plot workshop;
    private static final String ID = "enchant:DIAMOND_SWORD|sharpness|3";

    @BeforeEach
    void setUp() {
        plugin.recipes().setSource(() -> List.<org.bukkit.inventory.Recipe>of().iterator());
        plugin.recipes().setEnchantLookup(new RecipeBook.EnchantLookup() {
            @Override
            public Enchantment get(String key) {
                String k = key.replace("minecraft:", "");
                return k.equals("sharpness") ? Enchantment.SHARPNESS : k.equals("unbreaking") ? Enchantment.UNBREAKING : null;
            }

            @Override
            public List<Enchantment> all() {
                return List.of(Enchantment.SHARPNESS, Enchantment.UNBREAKING);
            }
        });
        plugin.getConfig().set("electricity.max-clock-step-seconds", 10_000_000);
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.workshop", 0);
        plugin.power().setTimeSource(wall::get);
        server.getWorld("world").loadChunk(0, 0);
        steve = player("Steve", 1_000_000);
        alex = player("Alex", 1_000_000);
        boss = admin("Boss");
        workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        assertTrue(plugin.service().rent(steve, workshop, 10));
        Block b = server.getWorld("world").getBlockAt(1, 1, 1);
        b.setType(Material.ENCHANTING_TABLE);
        plugin.stations().addMachine(workshop, 1, 1, 1, Station.fromName("ENCHANTING_TABLE"));
        plugin.stations().inc(workshop, Station.fromName("ENCHANTING_TABLE"));
        plugin.power().recalc(workshop);
        assertTrue(plugin.power().deposit(steve, workshop, 200_000));
        plugin.factory().tick();
    }

    private void pass(long seconds) {
        wall.addAndGet(seconds * 1000);
        plugin.power().tick();
        plugin.factory().tick();
    }

    @Test
    void recipeCostsTheItemAndLapisByTheConfigFormula() {
        RecipeBook.Recipe r = plugin.recipes().get(ID);
        assertNotNull(r);
        assertTrue(r.enchanting());
        assertEquals(Material.DIAMOND_SWORD, r.output());
        assertEquals(3, r.enchantLevel());
        assertEquals(1, r.inputs().get(0).count());
        assertEquals(Material.LAPIS_LAZULI, r.inputs().get(1).options().get(0));
        assertEquals(1 + 2 * 3, r.inputs().get(1).count(), "lapis-base + lapis-per-level × уровень");
        assertEquals(8 + 4 * 3, r.seconds(), 1e-9, "seconds-base + seconds-per-level × уровень");
        plugin.getConfig().set("enchanting.lapis-per-level", 5);
        assertEquals(16, plugin.recipes().get(ID).inputs().get(1).count());
    }

    @Test
    void unknownEnchantsAndGarbageIdsGiveNoRecipe() {
        assertNull(plugin.recipes().get("enchant:DIAMOND_SWORD|nonsense|1"));
        assertNull(plugin.recipes().get("enchant:NOPE|sharpness|1"));
        assertNull(plugin.recipes().get("enchant:DIAMOND_SWORD|sharpness|x"));
        assertNull(plugin.recipes().get("enchant:DIAMOND_SWORD|sharpness|0"));
        assertNull(plugin.recipes().get("enchant:DIAMOND_SWORD"));
    }

    @Test
    void stackCarriesTheEnchantment() {
        ItemStack s = plugin.recipes().stackOf(plugin.recipes().get(ID));
        assertEquals(Material.DIAMOND_SWORD, s.getType());
        assertEquals(3, s.getEnchantmentLevel(Enchantment.SHARPNESS));
    }

    @Test
    void managerProposalWaitsForTheAdminAndAdminApprovesIt() {
        assertNull(plugin.enchants().propose(steve, workshop, Material.DIAMOND_SWORD, "sharpness", 3));
        assertTrue(workshop.approvedEnchants.isEmpty());
        List<Request> pending = plugin.moderation().pendingEnchants(workshop.id);
        assertEquals(1, pending.size());
        assertEquals(ID, pending.get(0).text);
        assertNotNull(plugin.enchants().propose(steve, workshop, Material.DIAMOND_SWORD, "sharpness", 3), "повторная заявка");
        plugin.moderation().approve(pending.get(0), "Boss");
        assertTrue(workshop.approvedEnchants.contains(ID));
        assertTrue(plugin.moderation().pendingEnchants(workshop.id).isEmpty());
        assertNotNull(plugin.enchants().propose(steve, workshop, Material.DIAMOND_SWORD, "sharpness", 3), "уже одобрено");
    }

    @Test
    void rejectedProposalDoesNotApprove() {
        plugin.enchants().propose(steve, workshop, Material.DIAMOND_SWORD, "sharpness", 3);
        Request r = plugin.moderation().pendingEnchants(workshop.id).get(0);
        plugin.moderation().reject(r, "Boss", "слишком сильно");
        assertTrue(workshop.approvedEnchants.isEmpty());
        assertTrue(plugin.moderation().pendingEnchants(workshop.id).isEmpty());
    }

    @Test
    void adminProposalAppliesImmediately() {
        assertNull(plugin.enchants().propose(boss, workshop, Material.DIAMOND_SWORD, "sharpness", 3));
        assertTrue(workshop.approvedEnchants.contains(ID));
    }

    @Test
    void validationRejectsBadInput() {
        assertNotNull(plugin.enchants().propose(alex, workshop, Material.DIAMOND_SWORD, "sharpness", 3), "чужой игрок");
        assertNotNull(plugin.enchants().propose(steve, workshop, Material.DIAMOND_SWORD, "nonsense", 3), "нет такого зачарования");
        assertNotNull(plugin.enchants().propose(steve, workshop, Material.DIAMOND_SWORD, "sharpness", 6), "уровень выше максимума");
        assertNotNull(plugin.enchants().propose(steve, workshop, Material.DIAMOND_SWORD, "sharpness", 0), "уровень ниже 1");
        assertNotNull(plugin.enchants().propose(steve, workshop, Material.STICK, "sharpness", 1), "нельзя на этот предмет");
        plugin.getConfig().set("enchanting.max-level-bonus", 2);
        assertNull(plugin.enchants().propose(steve, workshop, Material.DIAMOND_SWORD, "sharpness", 7), "бонус к максимуму из конфига");
    }

    @Test
    void workshopLimitCapsRecipes() {
        plugin.getConfig().set("enchanting.max-recipes-per-workshop", 2);
        assertNull(plugin.enchants().propose(boss, workshop, Material.DIAMOND_SWORD, "sharpness", 1));
        assertNull(plugin.enchants().propose(boss, workshop, Material.DIAMOND_SWORD, "sharpness", 2));
        assertNotNull(plugin.enchants().propose(boss, workshop, Material.DIAMOND_SWORD, "sharpness", 3));
    }

    @Test
    void unapprovedEnchantCannotBeAssigned() {
        assertNotNull(plugin.factory().assign(steve, workshop, "1,1,1", ID));
        plugin.enchants().propose(boss, workshop, Material.DIAMOND_SWORD, "sharpness", 3);
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", ID));
    }

    @Test
    void tableEnchantsFromTheStockAndOutputsTheEnchantedItem() {
        plugin.enchants().propose(boss, workshop, Material.DIAMOND_SWORD, "sharpness", 3);
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", ID));
        workshop.stock.merge(Material.DIAMOND_SWORD, 2, Integer::sum);
        workshop.stock.merge(Material.LAPIS_LAZULI, 20, Integer::sum);
        Block chest = server.getWorld("world").getBlockAt(5, 1, 5);
        chest.setType(Material.CHEST);
        workshop.productChests.add("5,1,5");
        pass(1);
        pass(100);
        int swords = 0;
        for (ItemStack s : ((org.bukkit.block.Chest) chest.getState()).getInventory().getContents()) {
            if (s != null && s.getType() == Material.DIAMOND_SWORD) {
                assertEquals(3, s.getEnchantmentLevel(Enchantment.SHARPNESS));
                swords += s.getAmount();
            }
        }
        assertEquals(2, swords);
        assertEquals(20 - 2 * 7, workshop.stock.getOrDefault(Material.LAPIS_LAZULI, 0), "лазурит списан");
        assertEquals(0, workshop.stock.getOrDefault(Material.DIAMOND_SWORD, 0));
    }

    @Test
    void removingAnEnchantUnassignsTheTable() {
        plugin.enchants().propose(boss, workshop, Material.DIAMOND_SWORD, "sharpness", 3);
        assertNull(plugin.factory().assign(steve, workshop, "1,1,1", ID));
        assertNull(plugin.enchants().remove(steve, workshop, ID));
        assertFalse(workshop.approvedEnchants.contains(ID));
        assertNull(workshop.machineStates.get("1,1,1").recipe);
        assertNotNull(plugin.enchants().remove(steve, workshop, ID));
        assertNotNull(plugin.enchants().remove(alex, workshop, ID));
    }

    @Test
    void recipesForTableListsOnlyApprovedEnchants() {
        assertTrue(plugin.factory().recipesFor(workshop, Material.ENCHANTING_TABLE).isEmpty());
        plugin.enchants().propose(boss, workshop, Material.DIAMOND_SWORD, "sharpness", 3);
        var list = plugin.factory().recipesFor(workshop, Material.ENCHANTING_TABLE);
        assertEquals(1, list.size());
        assertEquals(ID, list.get(0).id());
    }

    @Test
    void approvedEnchantsSurviveRestart() {
        plugin.enchants().propose(boss, workshop, Material.DIAMOND_SWORD, "sharpness", 3);
        plugin.plots().save();
        plugin.plots().load();
        assertTrue(plugin.plots().get("w").approvedEnchants.contains(ID));
    }

    @Test
    void commandProposesAndListsAndTypes() {
        steve.performCommand("district enchant propose DIAMOND_SWORD sharpness 3 w");
        assertEquals(1, plugin.moderation().pendingEnchants("w").size());
        steve.performCommand("district enchant types DIAMOND_SWORD");
        steve.performCommand("district enchant list w");
        boss.performCommand("district enchant allow DIAMOND_SWORD unbreaking 2 w");
        assertTrue(workshop.approvedEnchants.contains("enchant:DIAMOND_SWORD|unbreaking|2"));
    }
}
