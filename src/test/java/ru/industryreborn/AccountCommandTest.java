package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.support.PluginTestBase;

/** Команды /district account, power, warnings. */
class AccountCommandTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock boss;
    private Plot shop;

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
        steve = player("Steve", 10_000);
        boss = admin("Boss");
        shop = addPlot("s", Category.SHOP, 0, 100, -1);
        plugin.service().buy(steve, shop);
    }

    @Test
    void accountDepositAndWithdraw() {
        steve.performCommand("district account deposit 500 s");
        assertEquals(500, plugin.power().balance(shop), 1e-9);
        steve.performCommand("district account withdraw 200 s");
        assertEquals(300, plugin.power().balance(shop), 1e-9);
        assertEquals(10_000 - 100 - 500 + 200, economy.getBalance(steve), 1e-9);
    }

    @Test
    void powerDepositIsAnAliasOfAccountDeposit() {
        steve.performCommand("district power deposit 250 s");
        assertEquals(250, plugin.power().balance(shop), 1e-9);
    }

    @Test
    void accountInfoShowsBalance() {
        steve.performCommand("district account deposit 500 s");
        drain(steve);
        steve.performCommand("district account info s");
        assertTrue(drain(steve).contains("500"));
    }

    @Test
    void badAmountsAreRejected() {
        steve.performCommand("district account deposit abc s");
        assertTrue(drain(steve).contains("числом"));
        steve.performCommand("district account deposit -5 s");
        assertEquals(0, plugin.power().balance(shop), 1e-9);
    }

    @Test
    void powerCalcRequiresAdmin() {
        steve.performCommand("district power calc smelter:2 anvil:1");
        assertTrue(drain(steve).contains("Недостаточно прав"));
    }

    @Test
    void powerCalcPrintsBreakdownAndTotal() {
        boss.performCommand("district power calc smelter:2 anvil:1");
        String out = drain(boss);
        assertTrue(out.contains("smelter × 2"), out);
        assertTrue(out.contains("anvil × 1"), out);
        assertTrue(out.contains("360"), "итого 360 в сутки: " + out);
        assertTrue(out.contains("10800"), "и 10800 за 30 суток: " + out);
    }

    @Test
    void powerCalcRejectsUnknownStation() {
        boss.performCommand("district power calc laser:2");
        assertTrue(drain(boss).contains("Неизвестный станок"));
    }

    @Test
    void powerCalcFollowsTheConfig() {
        plugin.getConfig().set("electricity.base-rate-per-day", 200.0);
        plugin.getConfig().set("electricity.progression-per-extra-same-type", 0.0);
        boss.performCommand("district power calc smelter:3");
        assertTrue(drain(boss).contains("600"));
    }

    @Test
    void warningsCommandOpensTheMenuAndMarksEverythingRead() {
        plugin.service().warn(steve.getUniqueId(), "s", "<yellow>Тестовое предупреждение");
        assertEquals(1, plugin.warnings().unread(steve.getUniqueId()));

        steve.performCommand("district warnings");

        assertEquals(0, plugin.warnings().unread(steve.getUniqueId()));
        assertEquals(1, plugin.warnings().forPlayer(steve.getUniqueId()).size());
    }
}
