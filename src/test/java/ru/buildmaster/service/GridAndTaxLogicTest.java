package ru.buildmaster.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.District;

class GridAndTaxLogicTest {

    // ------------------------------------------------------------ нагрузка станции

    @Test
    void loadRatioHandlesZeroCapacity() {
        assertEquals(0.5, GridLoad.ratio(50, 100), 1e-9);
        assertEquals(0, GridLoad.ratio(0, 0), 1e-9);
        assertTrue(Double.isInfinite(GridLoad.ratio(1, 0)));
    }

    @Test
    void overloadMeansDemandAboveCapacity() {
        assertFalse(GridLoad.overloaded(100, 100));
        assertTrue(GridLoad.overloaded(100.5, 100));
        assertTrue(GridLoad.overloaded(1, 0));
        assertFalse(GridLoad.overloaded(0, 0));
    }

    @Test
    void multiplierGrowsGraduallyUnderOverload() {
        double m = 1.0;
        m = GridLoad.nextMultiplier(m, true, 0.02, 3.0);
        assertEquals(1.02, m, 1e-9);
        m = GridLoad.nextMultiplier(m, true, 0.02, 3.0);
        assertEquals(1.04, m, 1e-9);
    }

    @Test
    void multiplierIsCappedAndNeverBelowOne() {
        assertEquals(3.0, GridLoad.nextMultiplier(2.99, true, 0.02, 3.0), 1e-9);
        assertEquals(3.0, GridLoad.nextMultiplier(3.0, true, 0.02, 3.0), 1e-9);
        assertEquals(1.0, GridLoad.nextMultiplier(1.01, false, 0.02, 3.0), 1e-9);
        assertEquals(1.0, GridLoad.nextMultiplier(1.0, false, 0.02, 3.0), 1e-9);
    }

    @Test
    void multiplierReturnsToExactlyOneWithoutFloatDrift() {
        double m = 1.0;
        for (int i = 0; i < 37; i++) {
            m = GridLoad.nextMultiplier(m, true, 0.02, 3.0);
        }
        for (int i = 0; i < 37; i++) {
            m = GridLoad.nextMultiplier(m, false, 0.02, 3.0);
        }
        assertEquals(1.0, m, 0.0, "ровно 1.0, без накопленной погрешности");
    }

    // ------------------------------------------------------------ единицы V

    @Test
    void dailyUnitsAreIndependentOfPrice() {
        Map<String, Double> coef = Map.of("smelter", 1.0, "anvil", 1.5);
        assertEquals(3.6, PowerTariff.dailyUnits(0.1, Map.of("smelter", 2, "anvil", 1), coef::get), 1e-9);
        assertEquals(360, PowerTariff.dailyRate(100, 0.1, Map.of("smelter", 2, "anvil", 1), coef::get, 0), 1e-9);
        assertEquals(0, PowerTariff.dailyUnits(0.1, Map.of(), coef::get), 1e-9);
    }

    // ------------------------------------------------------------ налоги

    @Test
    void percentOfAmount() {
        assertEquals(10, TaxRules.percentOf(100, 10), 1e-9);
        assertEquals(0, TaxRules.percentOf(100, 0), 1e-9);
        assertEquals(100, TaxRules.percentOf(100, 150), 1e-9, "процент ограничен 100");
        assertEquals(0, TaxRules.percentOf(100, -5), 1e-9);
        assertEquals(0, TaxRules.percentOf(-100, 10), 1e-9);
        assertEquals(0, TaxRules.percentOf(0, 10), 1e-9);
    }

    @Test
    void periodsDueCountsMissedPeriodsWithACap() {
        long week = 7 * 86_400_000L;
        assertEquals(0, TaxRules.periodsDue(1000, 2000, week, 4), "срок ещё не наступил");
        assertEquals(0, TaxRules.periodsDue(5000, 0, week, 4), "налог не назначен");
        assertEquals(1, TaxRules.periodsDue(2000, 2000, week, 4));
        assertEquals(1, TaxRules.periodsDue(2000 + week - 1, 2000, week, 4));
        assertEquals(2, TaxRules.periodsDue(2000 + week, 2000, week, 4));
        assertEquals(4, TaxRules.periodsDue(2000 + 50 * week, 2000, week, 4), "после долгого простоя не больше 4");
    }

    @Test
    void advanceMovesTheDeadlineForward() {
        assertEquals(1000 + 3 * 500, TaxRules.advance(1000, 500, 3));
    }

    // ------------------------------------------------------------ настройки банка

    @Test
    void bankSettingsPersistAndNullMeansConfig(@TempDir File dir) throws IOException {
        File file = new File(dir, "bank.yml");
        BankSettings s = new BankSettings();
        s.setDepositCommission(2.5);
        s.setWithdrawCommission(0.0);
        s.setSeizurePenalty(15.0);
        s.setRentTax(8.0);
        s.setOnlyInBank(true);
        s.setPropertyTax("shop", 120.0);
        s.save(file);

        BankSettings loaded = BankSettings.load(file);
        assertEquals(2.5, loaded.depositCommission(), 1e-9);
        assertEquals(0.0, loaded.withdrawCommission(), 1e-9, "ноль - это значение, а не отсутствие");
        assertEquals(15.0, loaded.seizurePenalty(), 1e-9);
        assertEquals(8.0, loaded.rentTax(), 1e-9);
        assertTrue(loaded.onlyInBank());
        assertEquals(120.0, loaded.propertyTax("shop"), 1e-9);
        assertNull(loaded.propertyTax("house"));

        loaded.setDepositCommission(null);
        loaded.setOnlyInBank(null);
        loaded.setPropertyTax("shop", null);
        loaded.save(file);
        BankSettings again = BankSettings.load(file);
        assertNull(again.depositCommission());
        assertNull(again.onlyInBank());
        assertNull(again.propertyTax("shop"));
        assertEquals(15.0, again.seizurePenalty(), 1e-9);
    }

    // ------------------------------------------------------------ журнал потребления

    @Test
    void consumptionIsBucketedByBillingDay() {
        ConsumptionLog log = new ConsumptionLog();
        long day = 86_400;
        log.record("w", 10 * day + 5, 3, 300);
        log.record("w", 10 * day + 50, 1, 100);
        log.record("w", 9 * day, 2, 200);
        log.record("W", 3 * day, 5, 500);

        long now = 10 * day + 100;
        assertEquals(4, log.last("w", now, 1).units(), 1e-9);
        assertEquals(400, log.last("w", now, 1).money(), 1e-9);
        assertEquals(6, log.last("w", now, 2).units(), 1e-9);
        assertEquals(6, log.last("w", now, 7).units(), 1e-9);
        assertEquals(11, log.total("w").units(), 1e-9);
        assertEquals(0, log.last("other", now, 7).units(), 1e-9);
    }

    @Test
    void consumptionKeepsThirtyDays() {
        ConsumptionLog log = new ConsumptionLog();
        long day = 86_400;
        for (int d = 0; d < 40; d++) {
            log.record("w", d * day, 1, 1);
        }
        assertEquals(30, log.total("w").units(), 1e-9);
    }

    @Test
    void consumptionPersistsAndIgnoresZeroRecords(@TempDir File dir) throws IOException {
        File file = new File(dir, "consumption.yml");
        ConsumptionLog log = new ConsumptionLog();
        log.record("w", 100, 0, 0);
        assertFalse(log.isDirty());
        log.record("w", 100, 2.5, 250);
        assertTrue(log.isDirty());
        log.save(file);
        assertFalse(log.isDirty());

        ConsumptionLog loaded = ConsumptionLog.load(file);
        assertEquals(2.5, loaded.total("w").units(), 1e-9);
        assertEquals(250, loaded.total("w").money(), 1e-9);
    }

    // ------------------------------------------------------------ районы

    @Test
    void plotBelongsToAtMostOneDistrict(@TempDir File dir) throws IOException {
        DistrictManager m = new DistrictManager(new File(dir, "districts.yml"));
        District north = m.create("Север");
        District south = m.create("Юг");
        assertNull(m.create("север"), "имя без учёта регистра");

        m.assign("w1", north);
        m.assign("W2", north);
        m.assign("w1", south);

        assertEquals(south, m.of("w1"));
        assertEquals(north, m.of("w2"));
        assertFalse(north.plotIds.contains("w1"));
        assertNull(m.of("w3"));
    }

    @Test
    void districtsPersist(@TempDir File dir) throws IOException {
        File file = new File(dir, "districts.yml");
        DistrictManager m = new DistrictManager(file);
        District d = m.create("Центр");
        m.assign("a", d);
        m.assign("b", d);
        m.save();

        DistrictManager loaded = new DistrictManager(file);
        loaded.load();
        assertEquals(List.of("a", "b"), loaded.plotIdsOf(loaded.get("центр")));
        assertTrue(loaded.delete("ЦЕНТР"));
        assertFalse(loaded.delete("Центр"));
        assertTrue(loaded.remove("zzz") == false);
    }

    // ------------------------------------------------------------ типы зданий

    @Test
    void adminOnlyCategories() {
        assertTrue(Category.BANK.isAdminOnly());
        assertTrue(Category.POWER_PLANT.isAdminOnly());
        assertFalse(Category.WORKSHOP.isAdminOnly());
        assertFalse(Category.HOUSE.isAdminOnly());
    }

    @Test
    void newCategoriesParseInSeveralSpellings() {
        assertEquals(Category.BANK, Category.parse("bank"));
        assertEquals(Category.BANK, Category.parse("Банк"));
        assertEquals(Category.POWER_PLANT, Category.parse("power_plant"));
        assertEquals(Category.POWER_PLANT, Category.parse("powerplant"));
        assertEquals(Category.POWER_PLANT, Category.parse("электростанция"));
    }
}
