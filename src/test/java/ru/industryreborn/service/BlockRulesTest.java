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
        cfg.set("place-banned", List.of("TNT", "*LAVA*"));
        rules = new BlockRules();
        rules.reload(cfg);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void placementRulesFollowTheConfig() {
        assertFalse(rules.canPlace(Material.TNT));
        assertFalse(rules.canPlace(Material.LAVA_BUCKET), "маска *LAVA* ловит и ведро");
        assertTrue(rules.canPlace(Material.TORCH));
        assertTrue(rules.canPlace(Material.OAK_SAPLING));
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
