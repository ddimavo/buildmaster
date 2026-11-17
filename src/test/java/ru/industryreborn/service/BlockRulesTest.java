package ru.industryreborn.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Правила замены блоков. Нужен MockBukkit: свойства Material берутся из реестров сервера. */
class BlockRulesTest {

    private BlockRules rules;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("replace-banned", List.of("TNT", "*LAVA*", "*WATER*", "*TORCH*", "*_SAPLING", "BEDROCK"));
        cfg.set("place-banned", List.of("TNT", "*LAVA*"));
        rules = new BlockRules();
        rules.reload(cfg);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void ordinaryBuildingBlocksAreAllowed() {
        assertTrue(rules.canReplaceWith(Material.OAK_PLANKS));
        assertTrue(rules.canReplaceWith(Material.BRICKS));
        assertTrue(rules.canReplaceWith(Material.GLASS));
        assertTrue(rules.canReplaceWith(Material.DIAMOND_BLOCK));
    }

    @Test
    void destructiveBlocksAreBanned() {
        assertFalse(rules.canReplaceWith(Material.TNT));
        assertFalse(rules.canReplaceWith(Material.BEDROCK));
        assertFalse(rules.canReplaceWith(Material.TORCH));
        assertFalse(rules.canReplaceWith(Material.SOUL_TORCH));
        assertFalse(rules.canReplaceWith(Material.REDSTONE_TORCH));
        assertFalse(rules.canReplaceWith(Material.OAK_SAPLING));
        assertFalse(rules.canReplaceWith(Material.CHERRY_SAPLING));
    }

    @Test
    void liquidsAreBannedEvenThoughTheyAreNotItems() {
        assertFalse(rules.canReplaceWith(Material.WATER));
        assertFalse(rules.canReplaceWith(Material.LAVA));
    }

    @Test
    void airAndNonBlocksAreNotTargets() {
        assertFalse(rules.canReplaceWith(Material.AIR));
        assertFalse(rules.canReplaceWith(Material.DIAMOND_SWORD));
    }

    @Test
    void placementRulesAreSeparateFromReplacementRules() {
        assertFalse(rules.canPlace(Material.TNT));
        assertFalse(rules.canPlace(Material.LAVA_BUCKET), "маска *LAVA* ловит и ведро");
        assertTrue(rules.canPlace(Material.TORCH), "факелы строить можно, нельзя только ими заменять");
        assertTrue(rules.canPlace(Material.OAK_SAPLING));
    }

    @Test
    void targetListContainsNoBannedMaterials() {
        List<Material> targets = rules.replaceTargets();
        assertFalse(targets.isEmpty());
        assertTrue(targets.contains(Material.STONE));
        assertFalse(targets.contains(Material.TNT));
        assertFalse(targets.contains(Material.TORCH));
        for (Material m : targets) {
            assertTrue(m.isBlock() && m.isItem() && !m.isAir(), m.name());
        }
    }

    @Test
    void tileEntitiesCannotBeReplaced() {
        assertTrue(BlockRules.isTileEntity(Material.CHEST));
        assertTrue(BlockRules.isTileEntity(Material.OAK_SIGN));
        assertTrue(BlockRules.isTileEntity(Material.FURNACE));
        assertFalse(BlockRules.isTileEntity(Material.STONE));
        assertFalse(BlockRules.isTileEntity(Material.OAK_STAIRS));
    }
}
