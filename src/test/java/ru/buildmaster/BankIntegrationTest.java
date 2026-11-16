package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.bukkit.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.BankSettings;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.PlotService;
import ru.buildmaster.support.PluginTestBase;

/** Банк: комиссии, режим «только в банке», штраф, налоги, проверка счетов. */
class BankIntegrationTest extends PluginTestBase {

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
        steve = player("Steve", 100_000);
        boss = admin("Boss");
        shop = addPlot("s", Category.SHOP, 0, 100, -1);
        plugin.service().buy(steve, shop);
    }

    // ------------------------------------------------------------ комиссии

    @Test
    void depositCommissionGoesToTheTreasury() {
        boss.performCommand("district bank admin commission deposit 10");
        double treasury = plugin.treasury().balance();
        double wallet = economy.getBalance(steve);

        assertTrue(plugin.power().deposit(steve, shop, 1000));

        assertEquals(900, plugin.power().balance(shop), 1e-9);
        assertEquals(wallet - 1000, economy.getBalance(steve), 1e-9);
        assertEquals(treasury + 100, plugin.treasury().balance(), 1e-9);
        assertTrue(plugin.journal().forPlot("s").get(0).reason().contains("комиссия"));
    }

    @Test
    void withdrawCommissionIsTakenFromTheAmountPaidOut() {
        plugin.power().deposit(steve, shop, 1000);
        boss.performCommand("district bank admin commission withdraw 5");
        double treasury = plugin.treasury().balance();
        double wallet = economy.getBalance(steve);

        assertTrue(plugin.power().withdraw(steve, shop, 400));

        assertEquals(600, plugin.power().balance(shop), 1e-9, "со счёта списано 400");
        assertEquals(wallet + 380, economy.getBalance(steve), 1e-9, "игрок получил 400 минус 5%");
        assertEquals(treasury + 20, plugin.treasury().balance(), 1e-9);
    }

    @Test
    void noCommissionByDefault() {
        double treasury = plugin.treasury().balance();
        plugin.power().deposit(steve, shop, 1000);
        assertEquals(1000, plugin.power().balance(shop), 1e-9);
        assertEquals(treasury, plugin.treasury().balance(), 1e-9);
    }

    @Test
    void commissionResetReturnsToConfig() {
        boss.performCommand("district bank admin commission deposit 10");
        assertEquals(10, plugin.bank().depositCommission(), 1e-9);
        boss.performCommand("district bank admin commission deposit reset");
        assertEquals(0, plugin.bank().depositCommission(), 1e-9);
        plugin.getConfig().set("bank.deposit-commission-percent", 3.0);
        assertEquals(3, plugin.bank().depositCommission(), 1e-9, "без переопределения берётся конфиг");
    }

    @Test
    void invalidPercentIsRejected() {
        boss.performCommand("district bank admin commission deposit 150");
        boss.performCommand("district bank admin commission withdraw abc");
        boss.performCommand("district bank admin penalty -5");
        assertNull(plugin.bank().settings().depositCommission());
        assertNull(plugin.bank().settings().withdrawCommission());
        assertNull(plugin.bank().settings().seizurePenalty());
    }

    // ------------------------------------------------------------ только в банке

    @Test
    void operationsWorkAnywhereByDefault() {
        assertTrue(plugin.power().deposit(steve, shop, 100));
    }

    @Test
    void onlyInBankModeRefusesOperationsOutsideTheBank() {
        boss.performCommand("district bank admin only-in-bank on");
        steve.teleport(new Location(server.getWorld("world"), 5, 5, 5));
        double wallet = economy.getBalance(steve);

        assertFalse(plugin.power().deposit(steve, shop, 100));
        assertFalse(plugin.power().withdraw(steve, shop, 10));

        assertEquals(wallet, economy.getBalance(steve), 1e-9);
        assertTrue(drain(steve).contains("только в здании банка"));
    }

    @Test
    void onlyInBankModeAllowsOperationsInsideTheBankBuilding() {
        Plot bank = addPlot("bank", Category.BANK, 100, -1, -1);
        boss.performCommand("district bank admin only-in-bank on");
        steve.teleport(new Location(server.getWorld("world"), 105, 5, 5));

        assertTrue(plugin.bank().inBank(steve));
        assertTrue(plugin.power().deposit(steve, shop, 100));
        assertTrue(plugin.power().withdraw(steve, shop, 50));
        assertNotNull(bank);
    }

    @Test
    void bankStaffIsNotBoundToTheBuilding() {
        boss.performCommand("district bank admin only-in-bank on");
        assertNull(plugin.bank().checkOperation(boss));
    }

    @Test
    void onlyInBankCanBeSwitchedOffAgain() {
        boss.performCommand("district bank admin only-in-bank on");
        boss.performCommand("district bank admin only-in-bank off");
        steve.teleport(new Location(server.getWorld("world"), 5, 5, 5));
        assertTrue(plugin.power().deposit(steve, shop, 100));
    }

    // ------------------------------------------------------------ штраф

    @Test
    void seizurePenaltyIsSetByTheBank() {
        boss.performCommand("district bank admin penalty 25");
        plugin.power().deposit(steve, shop, 1000);
        double wallet = economy.getBalance(steve);
        double treasury = plugin.treasury().balance();

        plugin.service().evict(shop, new PlotService.Release("Boss", "нарушение", Journal.Action.SEIZED, true,
                Plot.RestoreMode.ITEMS_ONLY));

        assertEquals(wallet + 750, economy.getBalance(steve), 1e-6);
        assertEquals(treasury + 250, plugin.treasury().balance(), 1e-6);
    }

    @Test
    void penaltyOfZeroMeansFullRefund() {
        boss.performCommand("district bank admin penalty 0");
        plugin.power().deposit(steve, shop, 1000);
        double wallet = economy.getBalance(steve);

        plugin.service().evict(shop, new PlotService.Release("Boss", "нарушение", Journal.Action.SEIZED, true,
                Plot.RestoreMode.ITEMS_ONLY));

        assertEquals(wallet + 1000, economy.getBalance(steve), 1e-6);
    }

    // ------------------------------------------------------------ оповещения и журнал

    @Test
    void changingTermsNotifiesEveryManagerAndIsJournalled() {
        PlayerMock alex = player("Alex", 100_000);
        Plot house = addPlot("h", Category.HOUSE, 20, 100, -1);
        plugin.service().buy(alex, house);
        int before = plugin.warnings().forPlayer(alex.getUniqueId()).size();

        boss.performCommand("district bank admin commission deposit 7");

        assertEquals(before + 1, plugin.warnings().forPlayer(alex.getUniqueId()).size());
        assertEquals(1, plugin.warnings().forPlayer(steve.getUniqueId()).stream()
                .filter(w -> w.text.contains("Банк изменил условия")).count());
        assertTrue(plugin.journal().forPlot("-").stream().anyMatch(e -> e.action() == Journal.Action.BANK_SETTINGS
                && e.actor().equals("Boss") && e.reason().contains("7.0%")));
    }

    @Test
    void settingsSurviveRestartViaBankYml() {
        boss.performCommand("district bank admin commission withdraw 4");
        boss.performCommand("district bank admin tax property shop 250");
        File file = new File(plugin.getDataFolder(), "bank.yml");
        assertTrue(file.exists());
        BankSettings loaded = BankSettings.load(file);
        assertEquals(4, loaded.withdrawCommission(), 1e-9);
        assertEquals(250, loaded.propertyTax("shop"), 1e-9);
    }

    // ------------------------------------------------------------ налог на аренду

    @Test
    void rentTaxIsWithheldFromTheLandlordsIncome() {
        boss.performCommand("district bank admin tax rent 10");
        double treasury = plugin.treasury().balance();

        double net = plugin.bank().applyRentTax("s", steve.getUniqueId(), 1000);

        assertEquals(900, net, 1e-9);
        assertEquals(treasury + 100, plugin.treasury().balance(), 1e-9);
        assertTrue(plugin.journal().forPlot("s").stream().anyMatch(e -> e.action() == Journal.Action.TAX));
    }

    @Test
    void noRentTaxMeansFullIncome() {
        assertEquals(1000, plugin.bank().applyRentTax("s", steve.getUniqueId(), 1000), 1e-9);
    }

    // ------------------------------------------------------------ налог на имущество

    private void dueNow(Plot plot) {
        plot.nextTaxAt = System.currentTimeMillis() - 1000;
    }

    @Test
    void newManagerGetsAFirstTaxDateOnePeriodAhead() {
        long expected = System.currentTimeMillis() + plugin.bank().taxPeriodMillis();
        assertTrue(Math.abs(shop.nextTaxAt - expected) < 5_000);
        assertEquals(7 * 86_400_000L, plugin.bank().taxPeriodMillis());
    }

    @Test
    void propertyTaxIsTakenFromTheAccountFirst() {
        boss.performCommand("district bank admin tax property shop 100");
        plugin.power().deposit(steve, shop, 60);
        double wallet = economy.getBalance(steve);
        double treasury = plugin.treasury().balance();
        dueNow(shop);

        plugin.bank().tick();

        assertEquals(0, plugin.power().balance(shop), 1e-9, "со счёта списано всё, что было");
        assertEquals(wallet - 40, economy.getBalance(steve), 1e-9, "остальное - с кошелька управляющего");
        assertEquals(treasury + 100, plugin.treasury().balance(), 1e-9);
        assertEquals(0, shop.taxDebt, 1e-9);
        assertTrue(shop.nextTaxAt > System.currentTimeMillis(), "следующий срок назначен");
    }

    @Test
    void taxIsPaidFromTheAccountWhenItCoversIt() {
        boss.performCommand("district bank admin tax property shop 100");
        plugin.power().deposit(steve, shop, 500);
        double wallet = economy.getBalance(steve);
        dueNow(shop);

        plugin.bank().tick();

        assertEquals(400, plugin.power().balance(shop), 1e-9);
        assertEquals(wallet, economy.getBalance(steve), 1e-9);
    }

    @Test
    void unpaidTaxBecomesADebtAndTheManagerIsWarned() {
        boss.performCommand("district bank admin tax property shop 100");
        economy.set(steve, 0);
        dueNow(shop);

        plugin.bank().tick();

        assertEquals(100, shop.taxDebt, 1e-9);
        var warning = plugin.warnings().forPlayer(steve.getUniqueId()).get(0);
        assertTrue(warning.text.contains("долг"), warning.text);
    }

    @Test
    void debtIsCollectedWhenMoneyAppears() {
        boss.performCommand("district bank admin tax property shop 100");
        economy.set(steve, 0);
        dueNow(shop);
        plugin.bank().tick();
        assertEquals(100, shop.taxDebt, 1e-9);
        double treasury = plugin.treasury().balance();

        economy.set(steve, 500);
        plugin.bank().tick();

        assertEquals(0, shop.taxDebt, 1e-9);
        assertEquals(400, economy.getBalance(steve), 1e-9);
        assertEquals(treasury + 100, plugin.treasury().balance(), 1e-9);
    }

    @Test
    void missedPeriodsAreChargedButCapped() {
        boss.performCommand("district bank admin tax property shop 100");
        economy.set(steve, 100_000);
        shop.nextTaxAt = System.currentTimeMillis() - 30 * plugin.bank().taxPeriodMillis();

        plugin.bank().tick();

        assertEquals(100_000 - 400, economy.getBalance(steve), 1e-9, "не больше четырёх периодов за раз");
    }

    @Test
    void zeroTaxChargesNothingButStillMovesTheDate() {
        dueNow(shop);
        double wallet = economy.getBalance(steve);
        plugin.bank().tick();
        assertEquals(wallet, economy.getBalance(steve), 1e-9);
        assertTrue(shop.nextTaxAt > System.currentTimeMillis());
    }

    @Test
    void taxDependsOnPlotCategory() {
        boss.performCommand("district bank admin tax property shop 100");
        PlayerMock alex = player("Alex", 1000);
        Plot house = addPlot("h", Category.HOUSE, 20, 50, -1);
        plugin.service().buy(alex, house);
        dueNow(house);
        double wallet = economy.getBalance(alex);

        plugin.bank().tick();

        assertEquals(wallet, economy.getBalance(alex), 1e-9, "для домов налог не назначен");
    }

    @Test
    void debtIsPaidFromTheRefundWhenTheManagerLeaves() {
        plugin.power().deposit(steve, shop, 200);
        shop.taxDebt = 50;
        double wallet = economy.getBalance(steve);
        double treasury = plugin.treasury().balance();

        plugin.service().release(steve, shop);

        assertEquals(wallet + 150, economy.getBalance(steve), 1e-6, "из остатка сначала погашен долг");
        assertEquals(treasury + 50, plugin.treasury().balance(), 1e-6);
        assertEquals(0, shop.taxDebt, 1e-9);
    }

    @Test
    void forgivingADebtRequiresAReasonAndNotifies() {
        shop.taxDebt = 80;
        boss.performCommand("district bank admin forgive s");
        assertEquals(80, shop.taxDebt, 1e-9, "без причины - ничего");

        boss.performCommand("district bank admin forgive s ошибка начисления");
        assertEquals(0, shop.taxDebt, 1e-9);
        assertTrue(plugin.journal().forPlot("s").get(0).reason().contains("ошибка начисления"));
        assertTrue(plugin.warnings().forPlayer(steve.getUniqueId()).get(0).text.contains("ошибка начисления"));
    }

    @Test
    void administrativeBuildingsAreNeverTaxed() {
        Plot bank = addPlot("bank", Category.BANK, 100, -1, -1);
        bank.status = Plot.Status.OWNED;
        bank.owner = boss.getUniqueId();
        bank.nextTaxAt = System.currentTimeMillis() - 1000;
        plugin.bank().settings().setPropertyTax("bank", 999.0);
        plugin.bank().tick();
        assertEquals(0, bank.taxDebt, 1e-9);
    }

    @Test
    void taxDateIsClearedWhenThePlotIsFreed() {
        plugin.service().release(steve, shop);
        assertEquals(0, shop.nextTaxAt);
        assertEquals(0, shop.taxDebt, 1e-9);
    }

    // ------------------------------------------------------------ проверка счетов и права

    @Test
    void bankStaffSeesAllAccountsSortedByBalance() {
        PlayerMock alex = player("Alex", 100_000);
        Plot house = addPlot("h", Category.HOUSE, 20, 50, -1);
        plugin.service().buy(alex, house);
        plugin.power().deposit(steve, shop, 100);
        plugin.power().deposit(alex, house, 900);

        var accounts = plugin.bank().accounts();

        assertEquals(2, accounts.size());
        assertEquals("h", accounts.get(0).id);
        assertEquals("s", accounts.get(1).id);
    }

    @Test
    void accountsCommandListsThemForBankStaffOnly() {
        plugin.power().deposit(steve, shop, 100);
        boss.performCommand("district bank accounts");
        assertTrue(drain(boss).contains("s ("));

        steve.performCommand("district bank accounts");
        assertTrue(drain(steve).contains("Недостаточно прав"));
    }

    @Test
    void ordinaryPlayersCannotChangeBankSettings() {
        steve.performCommand("district bank admin commission deposit 50");
        steve.performCommand("district bank admin penalty 90");
        steve.performCommand("district bank admin only-in-bank on");
        assertNull(plugin.bank().settings().depositCommission());
        assertNull(plugin.bank().settings().seizurePenalty());
        assertNull(plugin.bank().settings().onlyInBank());
    }

    @Test
    void anyoneCanReadTheTerms() {
        boss.performCommand("district bank admin commission deposit 3");
        drain(steve);
        steve.performCommand("district bank info");
        String out = drain(steve);
        assertTrue(out.contains("Условия банка"), out);
        assertTrue(out.contains("3.0%"), out);
    }

    @Test
    void managerSeesTheirStatementButNotSomeoneElses() {
        plugin.power().deposit(steve, shop, 100);
        PlayerMock alex = player("Alex", 1000);

        drain(steve);
        steve.performCommand("account statement s");
        steve.performCommand("district account statement s");
        assertTrue(drain(steve).contains("Выписка"));

        alex.performCommand("district account statement s");
        assertTrue(drain(alex).contains("только по своему счёту"));

        boss.performCommand("district account statement s");
        assertTrue(drain(boss).contains("Выписка"));
    }

    @Test
    void statementContainsOnlyAccountOperations() {
        plugin.power().deposit(steve, shop, 100);
        plugin.journal().add("s", steve.getUniqueId(), "x", Journal.Action.NAME_APPROVED, "не по счёту");
        var statement = plugin.bank().statement("s", 10);
        assertFalse(statement.isEmpty());
        assertTrue(statement.stream().noneMatch(e -> e.action() == Journal.Action.NAME_APPROVED));
    }

    @Test
    void treasuryCommandsNeedTheBankPermission() {
        steve.performCommand("district treasury");
        assertTrue(drain(steve).contains("Недостаточно прав"));
        boss.performCommand("district treasury");
        assertTrue(drain(boss).contains("Казна"));
    }
}
