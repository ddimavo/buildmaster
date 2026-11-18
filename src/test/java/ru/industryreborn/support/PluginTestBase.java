package ru.industryreborn.support;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;

/** Общая подготовка: сервер MockBukkit, плагин и экономика в памяти. */
public abstract class PluginTestBase {

    protected ServerMock server;
    protected IndustryRebornPlugin plugin;
    protected FakeEconomy economy;

    @BeforeEach
    void startServer() {
        server = MockBukkit.mock();
        economy = new FakeEconomy();
        server.getServicesManager().register(Economy.class, economy, MockBukkit.createMockPlugin(), ServicePriority.Highest);
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(IndustryRebornPlugin.class);
        plugin.dialogs().setBackend(null);       // окна ввода Paper в MockBukkit недоступны: ввод через чат
        // установка станков в мастерской платная; общие тесты считают деньги без неё (цены проверяет StationInstallTest)
        for (String type : new String[] {"smelter", "anvil", "alchemy", "enchanting"}) {
            plugin.getConfig().set("station-install." + type, 0);
        }
    }

    @AfterEach
    void stopServer() {
        MockBukkit.unmock();
    }

    protected PlayerMock player(String name, double money) {
        PlayerMock p = server.addPlayer(name);
        economy.set(p, money);
        return p;
    }

    protected PlayerMock admin(String name) {
        PlayerMock p = server.addPlayer(name);
        p.addAttachment(plugin, "industryreborn.admin", true);
        p.addAttachment(plugin, "industryreborn.bypass", true);
        p.addAttachment(plugin, "industryreborn.bank", true);
        p.addAttachment(plugin, "industryreborn.grid", true);
        return p;
    }

    /** Добавляет свободный участок 10x10x10 с левым нижним углом в (x, 0, 0). */
    protected Plot addPlot(String id, Category category, int x, double price, double rent) {
        Plot plot = new Plot(id, "world", new Box(x, 0, 0, x + 9, 9, 9));
        plot.category = category;
        plot.price = price;
        plot.rentPerDay = rent;
        plugin.plots().add(plot);
        plugin.stations().register(plot);
        return plot;
    }
}
