package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Employee;
import ru.industryreborn.model.JobApplication;
import ru.industryreborn.model.JobRole;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Vacancy;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.PlotService;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.util.ChunkAccess;
import ru.industryreborn.util.ChunkJob;

/** Работники и зарплаты: вакансии, отклики, наём, начисление за время и процент, расчёт при увольнении. */
class JobsTest extends PluginTestBase {

    private PlayerMock steve;   // управляющий
    private PlayerMock alex;    // работник
    private PlayerMock mia;
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
        ChunkJob.setLoader((world, cx, cz, cb) -> cb.accept(world.getChunkAt(cx, cz), null));
        ChunkAccess.setSource(new ChunkAccess.Source() {
            @Override
            public List<org.bukkit.block.BlockState> tileEntities(org.bukkit.Chunk chunk) {
                return List.of();
            }

            @Override
            public java.util.Collection<org.bukkit.entity.Entity> entities(org.bukkit.Chunk chunk) {
                return List.of();
            }
        });
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.shop", 0);
        plugin.getConfig().set("limits.categories.house", 0);
        plugin.getConfig().set("limits.categories.hotel", 0);
        steve = player("Steve", 100_000);
        alex = player("Alex", 1_000);
        mia = player("Mia", 1_000);
        boss = admin("Boss");
        shop = addPlot("s", Category.SHOP, 0, 100, -1);
        assertTrue(plugin.service().buy(steve, shop));
        shop.account.deposit(1_000);
    }

    @AfterEach
    void tearDownSeams() {
        ChunkJob.setLoader(null);
        ChunkAccess.setSource(null);
    }

    private Vacancy post(JobRole role, int slots, double rate, double percent) {
        assertNull(plugin.jobs().post(steve, shop, role, slots, rate, percent, null));
        List<Vacancy> list = plugin.jobs().vacanciesOf(shop.id);
        return list.get(list.size() - 1);
    }

    private Employee hire(PlayerMock who, Vacancy v) {
        assertNull(plugin.jobs().apply(who, v));
        JobApplication a = plugin.jobs().applicationsBy(who.getUniqueId()).get(0);
        assertNull(plugin.jobs().accept(steve, a));
        return plugin.jobs().employee(shop.id, who.getUniqueId());
    }

    private void stand(PlayerMock p, int x) {
        p.teleport(new Location(server.getWorld("world"), x, 1, 2));
    }

    private void minutes(int n) {
        for (int i = 0; i < n; i++) {
            plugin.jobs().minuteTick();
        }
    }

    // ------------------------------------------------------------ вакансии

    @Test
    void aManagerPostsAVacancyWithDefaultRights() {
        Vacancy v = post(JobRole.SELLER, 2, 50, 0);
        assertEquals(JobRole.SELLER, v.role);
        assertEquals(EnumSet.of(Plot.Perm.CONTAINERS), v.perms);
        assertEquals(1, plugin.jobs().board().size());
        assertEquals(Journal.Action.JOB, plugin.journal().forPlot("s").get(0).action());
    }

    @Test
    void postingIsValidated() {
        assertNotNull(plugin.jobs().post(alex, shop, JobRole.SELLER, 1, 10, 0, null), "не управляющий");
        assertNotNull(plugin.jobs().post(steve, shop, null, 1, 10, 0, null), "нет должности");
        assertNotNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 0, 10, 0, null), "мест 0");
        assertNotNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 11, 10, 0, null), "слишком много мест");
        assertNotNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 1, 501, 0, null), "ставка выше предела");
        assertNotNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 1, -1, 0, null), "отрицательная ставка");
        assertNotNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 1, 0, 0, null), "нет зарплаты");
        assertNotNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 1, 10, 31, null), "процент выше предела");
        assertNotNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 1, Double.NaN, 0, null));
        assertTrue(plugin.jobs().vacanciesOf("s").isEmpty());
    }

    @Test
    void percentNeedsAnIncomeSourceAndRespectsTheTotalCap() {
        Plot workshop = addPlot("w", Category.WORKSHOP, 20, 100, -1);
        plugin.service().buy(steve, workshop);
        assertNotNull(plugin.jobs().post(steve, workshop, JobRole.MASTER, 1, 10, 5, null), "в мастерской нет процента");
        assertNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 2, 0, 20, null));          // 40%
        assertNotNull(plugin.jobs().post(steve, shop, JobRole.MANAGER, 1, 0, 11, null), "40 + 11 > 50");
        assertNull(plugin.jobs().post(steve, shop, JobRole.MANAGER, 1, 0, 10, null));
    }

    @Test
    void onlyAllowedBuildingsHireAndVacanciesAreCapped() {
        Plot house = addPlot("h", Category.HOUSE, 20, 100, -1);
        plugin.service().buy(steve, house);
        assertNotNull(plugin.jobs().post(steve, house, JobRole.CLEANER, 1, 10, 0, null));
        for (int i = 0; i < 5; i++) {
            assertNull(plugin.jobs().post(steve, shop, JobRole.GUARD, 1, 10, 0, null));
        }
        assertNotNull(plugin.jobs().post(steve, shop, JobRole.GUARD, 1, 10, 0, null), "максимум вакансий");
    }

    @Test
    void aFreePlotCannotHire() {
        Plot free = addPlot("f", Category.SHOP, 20, 100, -1);
        assertNotNull(plugin.jobs().post(steve, free, JobRole.SELLER, 1, 10, 0, null));
        assertNotNull(plugin.jobs().post(boss, free, JobRole.SELLER, 1, 10, 0, null));
    }

    @Test
    void closedAndDeletedVacanciesLeaveTheBoard() {
        Vacancy v = post(JobRole.SELLER, 1, 10, 0);
        assertNull(plugin.jobs().setOpen(steve, v, false));
        assertTrue(plugin.jobs().board().isEmpty());
        assertNotNull(plugin.jobs().apply(alex, v));
        assertNull(plugin.jobs().setOpen(steve, v, true));
        assertEquals(1, plugin.jobs().board().size());
        assertNull(plugin.jobs().apply(alex, v));
        assertNull(plugin.jobs().delete(steve, v));
        assertTrue(plugin.jobs().applicationsFor("s").isEmpty(), "отклики на удалённую вакансию отменены");
        assertTrue(drain(alex).contains("снята"));
    }

    // ------------------------------------------------------------ отклики и наём

    @Test
    void applyAndAcceptHiresAndGrantsRights() {
        Vacancy v = post(JobRole.COOK, 1, 10, 0);
        Employee e = hire(alex, v);
        assertNotNull(e);
        assertEquals(EnumSet.of(Plot.Perm.CONTAINERS, Plot.Perm.STATIONS), shop.permsOf(alex.getUniqueId()));
        assertFalse(shop.can(alex.getUniqueId(), Plot.Perm.BUILD), "строить работник не может");
        assertTrue(plugin.jobs().board().isEmpty(), "мест не осталось");
        assertTrue(plugin.jobs().applicationsFor("s").isEmpty());
    }

    @Test
    void applyIsValidated() {
        Vacancy v = post(JobRole.SELLER, 1, 10, 0);
        assertNotNull(plugin.jobs().apply(steve, v), "на своём участке");
        assertNull(plugin.jobs().apply(alex, v));
        assertNotNull(plugin.jobs().apply(alex, v), "повторный отклик");
        Plot other = addPlot("o", Category.SHOP, 20, 100, -1);
        plugin.service().buy(steve, other);
        for (int i = 0; i < 4; i++) {
            assertNull(plugin.jobs().post(steve, other, JobRole.GUARD, 1, 10, 0, null));
        }
        List<Vacancy> vs = plugin.jobs().vacanciesOf("o");
        assertNull(plugin.jobs().apply(alex, vs.get(0)));
        assertNull(plugin.jobs().apply(alex, vs.get(1)));
        assertNotNull(plugin.jobs().apply(alex, vs.get(2)), "максимум откликов - 3");
    }

    @Test
    void thereIsALimitOfJobsPerPlayer() {
        Plot a = addPlot("a", Category.SHOP, 20, 100, -1);
        Plot b = addPlot("b", Category.SHOP, 40, 100, -1);
        plugin.service().buy(steve, a);
        plugin.service().buy(steve, b);
        plugin.jobs().post(steve, shop, JobRole.GUARD, 1, 10, 0, null);
        plugin.jobs().post(steve, a, JobRole.GUARD, 1, 10, 0, null);
        plugin.jobs().post(steve, b, JobRole.GUARD, 1, 10, 0, null);
        for (String id : List.of("s", "a")) {
            plugin.jobs().apply(alex, plugin.jobs().vacanciesOf(id).get(0));
            plugin.jobs().accept(steve, plugin.jobs().applicationsBy(alex.getUniqueId()).get(0));
        }
        assertEquals(2, plugin.jobs().jobsOf(alex.getUniqueId()).size());
        assertNotNull(plugin.jobs().apply(alex, plugin.jobs().vacanciesOf("b").get(0)));
    }

    @Test
    void onlyTheManagerAcceptsAndDeclines() {
        Vacancy v = post(JobRole.SELLER, 2, 10, 0);
        plugin.jobs().apply(alex, v);
        plugin.jobs().apply(mia, v);
        JobApplication a = plugin.jobs().applicationsBy(alex.getUniqueId()).get(0);
        assertNotNull(plugin.jobs().accept(mia, a));
        assertNull(plugin.jobs().decline(steve, plugin.jobs().applicationsBy(mia.getUniqueId()).get(0)));
        assertTrue(drain(mia).contains("отклонён"));
        assertNull(plugin.jobs().accept(boss, a), "администратор может");
    }

    @Test
    void aFullVacancyRejectsTheSecondAcceptance() {
        Vacancy v = post(JobRole.SELLER, 1, 10, 0);
        plugin.jobs().apply(alex, v);
        JobApplication a = plugin.jobs().applicationsBy(alex.getUniqueId()).get(0);
        plugin.jobs().apply(mia, v);
        JobApplication b = plugin.jobs().applicationsBy(mia.getUniqueId()).get(0);
        assertNull(plugin.jobs().accept(steve, a));
        assertNotNull(plugin.jobs().accept(steve, b));
    }

    // ------------------------------------------------------------ зарплата за время

    @Test
    void workingOnThePlotEarnsTheRatePerBlockAndIsPaidFromTheAccount() {
        Employee e = hire(alex, post(JobRole.SELLER, 1, 60, 0));
        stand(alex, 2);
        minutes(29);
        assertEquals(1_000, economy.getBalance(alex), 1e-9, "блок ещё не закончен");
        minutes(1);
        assertEquals(1_060, economy.getBalance(alex), 1e-9);
        assertEquals(940, shop.account.rawBalance(), 1e-6);
        assertEquals(0, e.accrued, 1e-9);
        assertEquals(60, e.totalPaid, 1e-9);
    }

    @Test
    void timeOffThePlotOrOfflineDoesNotCount() {
        hire(alex, post(JobRole.SELLER, 1, 60, 0));
        stand(alex, 50);
        minutes(60);
        assertEquals(1_000, economy.getBalance(alex), 1e-9);
        stand(alex, 2);
        minutes(30);
        assertEquals(1_060, economy.getBalance(alex), 1e-9);
    }

    @Test
    void theDailyCapLimitsPaidTime() {
        plugin.getConfig().set("jobs.max-work-minutes-per-day", 60);
        hire(alex, post(JobRole.SELLER, 1, 60, 0));
        stand(alex, 2);
        minutes(300);
        assertEquals(1_120, economy.getBalance(alex), 1e-9, "оплачено только 60 минут = 2 блока");
    }

    @Test
    void theDailyCapResetsTheNextDay() {
        plugin.getConfig().set("jobs.max-work-minutes-per-day", 30);
        long[] now = {1_000_000_000L};
        plugin.jobs().setClock(() -> now[0]);
        hire(alex, post(JobRole.SELLER, 1, 60, 0));
        stand(alex, 2);
        minutes(100);
        assertEquals(1_060, economy.getBalance(alex), 1e-9);
        now[0] += 86_400_000L;
        minutes(100);
        assertEquals(1_120, economy.getBalance(alex), 1e-9);
    }

    @Test
    void aShortAccountDelaysPayAsDebtUntilFunded() {
        shop.account.withdraw(1_000);
        shop.account.deposit(25);
        Employee e = hire(alex, post(JobRole.SELLER, 1, 60, 0));
        stand(alex, 2);
        minutes(30);
        assertEquals(1_025, economy.getBalance(alex), 1e-9);
        assertEquals(35, e.accrued, 1e-9);
        shop.account.deposit(100);
        minutes(1);
        assertEquals(1_060, economy.getBalance(alex), 1e-9);
        assertEquals(0, e.accrued, 1e-9);
    }

    @Test
    void aFrozenPlotDoesNotAccrueTime() {
        hire(alex, post(JobRole.SELLER, 1, 60, 0));
        stand(alex, 2);
        shop.graceUntil = System.currentTimeMillis() + 100_000;
        minutes(60);
        assertEquals(1_000, economy.getBalance(alex), 1e-9);
    }

    // ------------------------------------------------------------ процент от дохода

    @Test
    void shopSalesAccruePercentToEmployees() {
        Employee e = hire(alex, post(JobRole.SELLER, 1, 0, 10));
        plugin.jobs().onSale(shop, 200);
        assertEquals(20, e.accrued, 1e-9);
        minutes(1);
        assertEquals(1_020, economy.getBalance(alex), 1e-9, "выплата со счёта предприятия");
    }

    @Test
    void salesOutsideShopsAndOnFreePlotsAreIgnored() {
        Employee e = hire(alex, post(JobRole.SELLER, 1, 0, 10));
        Plot restaurant = addPlot("r", Category.RESTAURANT, 20, 100, -1);
        plugin.service().buy(steve, restaurant);
        plugin.jobs().onSale(restaurant, 500);
        assertEquals(0, e.accrued, 1e-9);
        plugin.jobs().onSale(null, 500);
    }

    @Test
    void hotelRoomIncomeAccruesPercentToHotelStaff() {
        Plot hotel = addPlot("hot", Category.HOTEL, 20, 100, -1);
        plugin.service().buy(steve, hotel);
        assertNull(plugin.jobs().post(steve, hotel, JobRole.MANAGER, 1, 0, 10, null));
        plugin.jobs().apply(alex, plugin.jobs().vacanciesOf("hot").get(0));
        plugin.jobs().accept(steve, plugin.jobs().applicationsBy(alex.getUniqueId()).get(0));
        Plot room = new Plot("hot-1", "world", new Box(21, 1, 1, 23, 3, 3));
        room.category = Category.ROOM;
        room.parentId = "hot";
        plugin.plots().add(room);
        plugin.hotels().income(room, 300, "тест");
        assertEquals(30, plugin.jobs().employee("hot", alex.getUniqueId()).accrued, 1e-9);
        assertEquals(300, hotel.account.rawBalance(), 1e-6);
    }

    @Test
    void rentTaxIsNotPaidToWorkersTwice() {
        plugin.getConfig().set("bank.rent-tax-percent", 10);
        Plot hotel = addPlot("hot", Category.HOTEL, 20, 100, -1);
        plugin.service().buy(steve, hotel);
        plugin.jobs().post(steve, hotel, JobRole.MANAGER, 1, 0, 10, null);
        plugin.jobs().apply(alex, plugin.jobs().vacanciesOf("hot").get(0));
        plugin.jobs().accept(steve, plugin.jobs().applicationsBy(alex.getUniqueId()).get(0));
        Plot room = new Plot("hot-1", "world", new Box(21, 1, 1, 23, 3, 3));
        room.category = Category.ROOM;
        room.parentId = "hot";
        plugin.plots().add(room);
        plugin.hotels().income(room, 100, "тест");
        assertEquals(9, plugin.jobs().employee("hot", alex.getUniqueId()).accrued, 1e-9, "процент от чистого дохода");
    }

    // ------------------------------------------------------------ увольнение

    @Test
    void firingSettlesFromTheAccountAndRevokesRights() {
        Employee e = hire(alex, post(JobRole.COOK, 1, 0, 10));
        plugin.jobs().onSale(shop, 100);
        assertNull(plugin.jobs().fire(steve, shop, alex.getUniqueId()));
        assertNull(plugin.jobs().employee("s", alex.getUniqueId()));
        assertEquals(1_010, economy.getBalance(alex), 1e-9);
        assertTrue(shop.permsOf(alex.getUniqueId()).isEmpty());
        assertFalse(shop.involves(alex.getUniqueId()));
        assertEquals(1, plugin.jobs().board().size(), "место снова свободно");
        assertTrue(drain(alex).contains("больше не работаете"));
    }

    @Test
    void settlementTakesTheShortfallFromTheManagersWallet() {
        shop.account.withdraw(1_000);
        Employee e = hire(alex, post(JobRole.SELLER, 1, 0, 10));
        plugin.jobs().onSale(shop, 500);
        assertEquals(50, e.accrued, 1e-9);
        double before = economy.getBalance(steve);
        plugin.jobs().fire(steve, shop, alex.getUniqueId());
        assertEquals(1_050, economy.getBalance(alex), 1e-9);
        assertEquals(before - 50, economy.getBalance(steve), 1e-9);
    }

    @Test
    void anInsolventManagerDoesNotPayMoreThanHeHas() {
        shop.account.withdraw(1_000);
        Employee e = hire(alex, post(JobRole.SELLER, 1, 0, 10));
        plugin.jobs().onSale(shop, 500);
        economy.set(steve, 10);
        plugin.jobs().fire(steve, shop, alex.getUniqueId());
        assertEquals(1_000, economy.getBalance(alex), 1e-9);
        assertEquals(10, economy.getBalance(steve), 1e-9);
        assertNull(plugin.jobs().employee("s", alex.getUniqueId()));
        assertTrue(plugin.journal().forPlot("s").stream().anyMatch(x -> x.reason().contains("не выплачено")));
    }

    @Test
    void aWorkerCanQuit() {
        hire(alex, post(JobRole.SELLER, 1, 10, 0));
        assertNull(plugin.jobs().quit(alex, shop));
        assertNotNull(plugin.jobs().quit(alex, shop));
        assertTrue(shop.permsOf(alex.getUniqueId()).isEmpty());
    }

    @Test
    void youCannotFireStrangersOrDeleteVacanciesWithStaff() {
        Vacancy v = post(JobRole.SELLER, 1, 10, 0);
        hire(alex, v);
        assertNotNull(plugin.jobs().fire(steve, shop, mia.getUniqueId()));
        assertNotNull(plugin.jobs().fire(mia, shop, alex.getUniqueId()));
        assertNotNull(plugin.jobs().delete(steve, v));
    }

    @Test
    void vacatingThePlotSettlesEveryoneAndClosesTheVacancies() {
        Employee e = hire(alex, post(JobRole.SELLER, 1, 0, 10));
        plugin.jobs().onSale(shop, 100);
        post(JobRole.GUARD, 1, 5, 0);
        plugin.jobs().apply(mia, plugin.jobs().vacanciesOf("s").get(1));

        plugin.service().evict(shop, new PlotService.Release("Boss", "тест", Journal.Action.EVICTED, false,
                Plot.RestoreMode.ITEMS_ONLY));
        server.getScheduler().performTicks(20);

        assertEquals(1_010, economy.getBalance(alex), 1e-9);
        assertTrue(plugin.jobs().vacanciesOf("s").isEmpty());
        assertTrue(plugin.jobs().applicationsFor("s").isEmpty());
        assertTrue(plugin.jobs().employeesOf("s").isEmpty());
        assertTrue(plugin.jobs().board().isEmpty());
    }

    // ------------------------------------------------------------ защита и сохранение

    @Test
    void workersWithContainerRightsUseChestsButCannotBuild() {
        hire(alex, post(JobRole.KEEPER, 1, 10, 0));
        assertTrue(shop.can(alex.getUniqueId(), Plot.Perm.CONTAINERS));
        assertFalse(shop.can(alex.getUniqueId(), Plot.Perm.STATIONS));
        assertFalse(shop.isMember(alex.getUniqueId()));
    }

    @Test
    void buildRightIsNeverGrantedToWorkers() {
        assertNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 1, 10, 0,
                EnumSet.of(Plot.Perm.BUILD, Plot.Perm.CONTAINERS)));
        Vacancy v = plugin.jobs().vacanciesOf("s").get(0);
        assertEquals(EnumSet.of(Plot.Perm.CONTAINERS), v.perms);
        hire(alex, v);
        assertFalse(shop.isMember(alex.getUniqueId()));
    }

    @Test
    void jobsSurviveAReload() {
        Vacancy v = post(JobRole.COOK, 3, 40, 0);
        Employee e = hire(alex, v);
        e.accrued = 12.5;
        e.blockMinutes = 7;
        plugin.jobs().apply(mia, v);
        plugin.jobs().save();
        plugin.jobs().load();
        Vacancy v2 = plugin.jobs().vacancy(v.id);
        assertEquals(JobRole.COOK, v2.role);
        assertEquals(3, v2.slots);
        assertEquals(40, v2.rate, 1e-9);
        assertEquals(EnumSet.of(Plot.Perm.CONTAINERS, Plot.Perm.STATIONS), v2.perms);
        Employee e2 = plugin.jobs().employee("s", alex.getUniqueId());
        assertEquals(12.5, e2.accrued, 1e-9);
        assertEquals(7, e2.blockMinutes);
        assertEquals(1, plugin.jobs().applicationsBy(mia.getUniqueId()).size());
        post(JobRole.GUARD, 1, 5, 0);
        assertTrue(plugin.jobs().vacanciesOf("s").stream().map(x -> x.id).distinct().count() == 2, "счётчик не сбросился");
    }

    @Test
    void disabledJobsRefuseEverything() {
        plugin.getConfig().set("jobs.enabled", false);
        assertNotNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 1, 10, 0, null));
    }

    // ------------------------------------------------------------ команды и меню

    @Test
    void commandsCoverTheWholeFlow() {
        steve.performCommand("district job post s seller 2 40");
        assertEquals(1, plugin.jobs().vacanciesOf("s").size());
        int no = plugin.jobs().vacanciesOf("s").get(0).id;
        alex.performCommand("district job apply " + no);
        assertEquals(1, plugin.jobs().applicationsFor("s").size());
        int app = plugin.jobs().applicationsFor("s").get(0).id;
        steve.performCommand("district job accept " + app);
        assertNotNull(plugin.jobs().employee("s", alex.getUniqueId()));
        steve.performCommand("district job close " + no);
        assertFalse(plugin.jobs().vacancy(no).open);
        drain(steve);
        steve.performCommand("district job staff s");
        assertTrue(drain(steve).contains("Alex"));
        steve.performCommand("district job fire s Alex");
        assertNull(plugin.jobs().employee("s", alex.getUniqueId()));
        alex.performCommand("district job post s guard 1 10");
        assertEquals(1, plugin.jobs().vacanciesOf("s").size(), "чужой участок - вакансия не создана");
    }

    @Test
    void postCommandAcceptsPercentAndRights() {
        steve.performCommand("district job post s seller 1 0 15 none");
        Vacancy v = plugin.jobs().vacanciesOf("s").get(0);
        assertEquals(15, v.percent, 1e-9);
        assertTrue(v.perms.isEmpty());
        steve.performCommand("district job post s guard 1 10 0 containers,stations");
        assertEquals(EnumSet.of(Plot.Perm.CONTAINERS, Plot.Perm.STATIONS), plugin.jobs().vacanciesOf("s").get(1).perms);
    }

    @SuppressWarnings("removal")
    @Test
    void theBoardMenuShowsVacanciesAndAppliesOnClick() {
        Vacancy v = post(JobRole.SELLER, 1, 40, 0);
        alex.performCommand("district job board");
        alex.simulateInventoryClick(9);
        assertEquals(1, plugin.jobs().applicationsBy(alex.getUniqueId()).size());
        alex.performCommand("district job board");
        alex.simulateInventoryClick(9);
        assertTrue(plugin.jobs().applicationsBy(alex.getUniqueId()).isEmpty(), "повторное нажатие отзывает отклик");
        assertEquals(v.id, plugin.jobs().board().get(0).id);
    }

    @SuppressWarnings("removal")
    @Test
    void theStaffMenuAcceptsApplicationsAndTogglesVacancies() {
        Vacancy v = post(JobRole.SELLER, 1, 40, 0);
        plugin.jobs().apply(alex, v);
        new ru.industryreborn.gui.StaffMenu(plugin, steve, shop, null, ru.industryreborn.gui.StaffMenu.Tab.APPLICATIONS).open();
        steve.simulateInventoryClick(0);
        assertNotNull(plugin.jobs().employee("s", alex.getUniqueId()));
        new ru.industryreborn.gui.StaffMenu(plugin, steve, shop, null, ru.industryreborn.gui.StaffMenu.Tab.VACANCIES).open();
        steve.simulateInventoryClick(0);
        assertFalse(v.open);
    }

    @Test
    void roleParsingAcceptsKeysAndTitles() {
        assertEquals(JobRole.SELLER, JobRole.parse("seller"));
        assertEquals(JobRole.SELLER, JobRole.parse("Продавец"));
        assertEquals(JobRole.CLEANER, JobRole.parse("CLEANER"));
        assertNull(JobRole.parse("king"));
        assertNull(JobRole.parse(null));
    }
}
