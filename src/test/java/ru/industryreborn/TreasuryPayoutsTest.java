package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.TreasuryPayouts;
import ru.industryreborn.support.PluginTestBase;

/** Субсидии и призы из казны: деньги не печатаются, проверки, журнал, лимиты, отчёт. */
class TreasuryPayoutsTest extends PluginTestBase {

    private PlayerMock boss;
    private PlayerMock steve;
    private PlayerMock alex;
    private Plot shop;
    private double fund;      // баланс казны после подготовки (с арендой магазина)

    @BeforeEach
    void setUp() {
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.shop", 0);
        boss = admin("Boss");
        steve = player("Steve", 10_000);
        alex = player("Alex", 100);
        shop = addPlot("s", Category.SHOP, 0, -1, 10);
        assertTrue(plugin.service().rent(steve, shop, 5));
        plugin.treasury().deposit(5_000, "Начальный фонд");
        fund = plugin.treasury().balance();
    }

    @Test
    void subsidyMovesMoneyFromTheTreasuryToThePlotAccount() {
        double treasury = plugin.treasury().balance();
        double account = shop.account.rawBalance();
        assertNull(plugin.payouts().subsidy("Boss", shop, 700, "развитие магазина"));
        assertEquals(treasury - 700, plugin.treasury().balance(), 1e-9);
        assertEquals(account + 700, shop.account.rawBalance(), 1e-6);
        var tx = plugin.treasury().log().get(plugin.treasury().log().size() - 1);
        assertEquals(-700, tx.amount(), 1e-9);
        assertTrue(tx.reason().startsWith("Субсидия участку s"));
        assertEquals("Boss", tx.actor());
        assertTrue(plugin.journal().forPlot("s").stream().anyMatch(e -> e.action() == Journal.Action.SUBSIDY));
    }

    @Test
    void theOwnerIsNotified() {
        plugin.payouts().subsidy("Boss", shop, 100, "бонус");
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = steve.nextMessage()) != null) {
            sb.append(m);
        }
        assertTrue(sb.toString().contains("субсидию"), sb.toString());
    }

    @Test
    void cannotSpendMoreThanTheTreasuryHas() {
        assertNotNull(plugin.payouts().subsidy("Boss", shop, fund + 0.01, "много"));
        assertEquals(fund, plugin.treasury().balance(), 1e-9, "казна не изменилась");
        assertNull(plugin.payouts().subsidy("Boss", shop, fund, "всё"));
        assertEquals(0, plugin.treasury().balance(), 1e-9);
        assertNotNull(plugin.payouts().prize("Boss", alex, 1, "пусто"));
    }

    @Test
    void validatesAmountReasonAndFreePlots() {
        assertNotNull(plugin.payouts().subsidy("Boss", shop, 0, "ноль"));
        assertNotNull(plugin.payouts().subsidy("Boss", shop, -5, "минус"));
        assertNotNull(plugin.payouts().subsidy("Boss", shop, Double.NaN, "nan"));
        assertNotNull(plugin.payouts().subsidy("Boss", shop, 10, ""), "причина обязательна");
        assertNotNull(plugin.payouts().subsidy("Boss", shop, 10, "  "), "причина обязательна");
        Plot free = addPlot("f", Category.SHOP, 30, -1, 10);
        assertNotNull(plugin.payouts().subsidy("Boss", free, 10, "свободному"), "у свободного участка нет счёта");
        assertNotNull(plugin.payouts().subsidy("Boss", null, 10, "нет участка"));
        assertEquals(fund, plugin.treasury().balance(), 1e-9);
    }

    @Test
    void prizeGoesToThePlayersWalletAndIsLogged() {
        double before = economy.getBalance(alex);
        assertNull(plugin.payouts().prize("Boss", alex, 250, "Турнир по строительству"));
        assertEquals(before + 250, economy.getBalance(alex), 1e-9);
        assertEquals(fund - 250, plugin.treasury().balance(), 1e-9);
        assertTrue(plugin.treasury().log().get(plugin.treasury().log().size() - 1).reason().startsWith("Приз Alex"));
        assertTrue(plugin.journal().forPlayer(alex.getUniqueId()).stream().anyMatch(e -> e.action() == Journal.Action.PRIZE));
    }

    @Test
    void singleAndDailyLimitsApply() {
        plugin.getConfig().set("treasury.max-single-payout", 300);
        assertNotNull(plugin.payouts().prize("Boss", alex, 301, "слишком много"));
        assertNull(plugin.payouts().prize("Boss", alex, 300, "ровно предел"));
        plugin.getConfig().set("treasury.max-single-payout", 0);
        plugin.getConfig().set("treasury.daily-limit", 500);
        assertEquals(300, plugin.payouts().spentToday(), 1e-9);
        assertNotNull(plugin.payouts().prize("Boss", alex, 201, "выше лимита"));
        assertNull(plugin.payouts().prize("Boss", alex, 200, "в лимит"));
    }

    @Test
    void reportGroupsIncomeAndExpenseByArticle() {
        plugin.treasury().deposit(100, "Электричество участка s");
        plugin.treasury().deposit(40, "Комиссия банка за пополнение, участок s");
        plugin.treasury().deposit(7, "что-то ещё");
        plugin.payouts().subsidy("Boss", shop, 300, "развитие");
        plugin.payouts().prize("Boss", alex, 150, "Турнир");
        TreasuryPayouts.Report r = plugin.payouts().report(7);
        assertEquals(100, r.income().get("Электричество"), 1e-9);
        assertEquals(40, r.income().get("Комиссия"), 1e-9);
        assertEquals(50, r.income().get("Аренда"), 1e-9);
        assertEquals(7 + 5_000, r.income().get("Прочее"), 1e-9);   // и «Начальный фонд»
        assertEquals(300, r.expense().get("Субсидия"), 1e-9);
        assertEquals(150, r.expense().get("Приз"), 1e-9);
        assertEquals(450, r.totalExpense(), 1e-9);
        assertEquals(fund + 147, r.totalIncome(), 1e-9);
    }

    @Test
    void commandsWork() {
        boss.performCommand("district treasury subsidy s 200 на развитие");
        assertEquals(fund - 200, plugin.treasury().balance(), 1e-9);
        boss.performCommand("district treasury prize Alex 100 День города");
        assertEquals(fund - 300, plugin.treasury().balance(), 1e-9);
        boss.performCommand("district treasury pay Alex 50 компенсация");
        assertEquals(fund - 350, plugin.treasury().balance(), 1e-9);
        boss.performCommand("district treasury subsidy s 999999 слишком много");
        assertEquals(fund - 350, plugin.treasury().balance(), 1e-9);
        boss.performCommand("district treasury report 3");
        boss.performCommand("district treasury");
    }

    @Test
    void playersWithoutTheBankPermissionCannotSpendTheTreasury() {
        steve.performCommand("district treasury subsidy s 200 себе");
        assertEquals(fund, plugin.treasury().balance(), 1e-9);
    }

    @Test
    void treasuryMenuOpensForBankStaffOnly() {
        boss.performCommand("district treasury menu");
        assertTrue(boss.getOpenInventory().getTopInventory().getHolder() instanceof ru.industryreborn.gui.TreasuryMenu);
        steve.performCommand("district treasury menu");
        var top = steve.getOpenInventory().getTopInventory();
        assertFalse(top != null && top.getHolder() instanceof ru.industryreborn.gui.TreasuryMenu);
    }

    @Test
    void menuPromptsPayAPrize() {
        new ru.industryreborn.gui.TreasuryMenu(plugin, boss).open();
        boss.simulateInventoryClick(12);
        assertTrue(plugin.chatPrompt().isWaiting(boss));
        plugin.chatPrompt().deliver(boss, "Alex");
        server.getScheduler().performOneTick();
        plugin.chatPrompt().deliver(boss, "60");
        server.getScheduler().performOneTick();
        plugin.chatPrompt().deliver(boss, "Конкурс скинов");
        server.getScheduler().performOneTick();
        assertEquals(fund - 60, plugin.treasury().balance(), 1e-9);
    }
}
