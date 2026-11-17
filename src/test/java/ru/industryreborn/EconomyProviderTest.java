package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.support.FakeEconomy;
import ru.industryreborn.support.PluginTestBase;

/** Экономика через Vault: подойдёт любой провайдер (ExcellentEconomy, Essentials...), в том числе зарегистрированный позже или заменённый. */
class EconomyProviderTest extends PluginTestBase {

    @Test
    void theHookFollowsAReplacedProvider() {
        PlayerMock steve = player("Steve", 100);
        assertEquals(100, plugin.economy().balance(steve), 1e-9);
        FakeEconomy second = new FakeEconomy();
        second.set(steve, 777);
        Plugin excellent = MockBukkit.createMockPlugin("ExcellentEconomy");
        server.getServicesManager().register(Economy.class, second, excellent, ServicePriority.Highest);
        // как после /eco reload или смены валюты: провайдер новый, а старый кэш не должен остаться
        assertEquals(777, plugin.economy().balance(steve), 1e-9);
        assertEquals("ExcellentEconomy", plugin.economy().providerName());
        assertTrue(plugin.economy().withdraw(steve, 77));
        assertEquals(700, second.getBalance(steve), 1e-9);
        assertEquals(100, economy.getBalance(steve), 1e-9, "прежний провайдер не тронут");
    }

    @Test
    void withoutAProviderTheHookIsUnavailable() {
        server.getServicesManager().unregisterAll(server.getPluginManager().getPlugins()[0]);
        for (Plugin p : server.getPluginManager().getPlugins()) {
            server.getServicesManager().unregisterAll(p);
        }
        assertFalse(plugin.economy().available());
        assertNull(plugin.economy().providerName());
        assertEquals(0, plugin.economy().balance(player("Steve", 5)), 1e-9);
        assertFalse(plugin.economy().withdraw(player("Mia", 5), 1));
        assertEquals("1.50", plugin.economy().format(1.5));
    }
}
