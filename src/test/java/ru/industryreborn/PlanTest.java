package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.NotReadyException;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.extractor.ExtensionExtractor;
import com.djrapitops.plan.extension.table.Table;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.plan.IndustryRebornExtension;
import ru.industryreborn.plan.IndustryRebornTablesExtension;
import ru.industryreborn.service.Module;
import ru.industryreborn.support.PluginTestBase;

/** Интеграция с Plan: расширение проходит проверку Plan, показатели берутся из снимка данных. */
class PlanTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock boss;
    private IndustryRebornExtension extension;
    private IndustryRebornTablesExtension tables;

    @BeforeEach
    void setUp() {
        plugin.getConfig().set("limits.total", 0);
        for (String c : new String[] {"shop", "workshop", "hotel", "room"}) {
            plugin.getConfig().set("limits.categories." + c, 0);
        }
        steve = player("Steve", 100_000);
        boss = admin("Boss");
        Plot shop = addPlot("s", Category.SHOP, 0, -1, 80);
        shop.name = "Лавка";
        assertTrue(plugin.service().rent(steve, shop, 3));
        shop.revenue = 777;
        plugin.treasury().deposit(300, "Аренда");
        extension = new IndustryRebornExtension(plugin.planHook());
        tables = new IndustryRebornTablesExtension(plugin.planHook());
    }

    @Test
    void theExtensionPassesPlansOwnValidation() {
        ExtensionExtractor extractor = new ExtensionExtractor(extension);
        extractor.validateAnnotations();        // бросает исключение при ошибках аннотаций
        assertTrue(extractor.getWarnings().isEmpty(), extractor.getWarnings().toString());
        assertEquals("IndustryReborn", extractor.getPluginInfo().name());
        assertFalse(extractor.getTabInformation().isEmpty());
        ExtensionExtractor tableExtractor = new ExtensionExtractor(tables);
        tableExtractor.validateAnnotations();
        assertTrue(tableExtractor.getWarnings().isEmpty(), tableExtractor.getWarnings().toString());
    }

    @Test
    void oldTableResultsAreRemovedFromPlansDatabase() {
        // Plan не удаляет результаты старых провайдеров сам: без @InvalidateMethod таблицы, ушедшие в другое дополнение, остаются в базе
        // под именем «IndustryReborn» и вместе с показателями дают «Invalid card width: 18»
        var invalidated = new ExtensionExtractor(extension).getInvalidateMethodAnnotations().stream().map(a -> a.value()).toList();
        for (String old : new String[] {"plotsByCategory", "topRevenue", "treasuryOperations", "playerPlotsTable"}) {
            assertTrue(invalidated.contains(old), old);
            assertFalse(Arrays.stream(IndustryRebornExtension.class.getMethods()).anyMatch(m -> m.getName().equals(old)), "метод " + old + " в основном дополнении не должен возвращаться");
        }
        assertTrue(new ExtensionExtractor(tables).getInvalidateMethodAnnotations().isEmpty(), "новому дополнению нечего удалять");
    }

    // ---- раскладка карточек панели Plan: перенос computeLayoutWidths и проверки ширины из фронтенда Plan (ExtensionLayoutWidths.ts, optimizeCardOrder.ts)

    private record Item(String element, int min, int max) { }

    private static java.util.List<Item> expand(java.util.List<Item> row, int cardWidth) {
        double[] min = new double[row.size()];
        double[] max = new double[row.size()];
        for (int i = 0; i < row.size(); i++) {
            min[i] = (double) row.get(i).min() / cardWidth * 12;
            max[i] = (double) row.get(i).max() / cardWidth * 12;
        }
        double remaining = 12;
        for (double v : min) {
            remaining -= v;
        }
        boolean allWide = true;
        for (double v : min) {
            allWide &= v > 6;
        }
        java.util.List<Item> out = new java.util.ArrayList<>();
        if (remaining < 0 && allWide) {
            for (Item i : row) {
                out.add(new Item(i.element(), 12, 12));
            }
            return out;
        }
        while (remaining > 0) {
            boolean changed = false;
            for (int i = 0; i < min.length; i++) {
                if (min[i] < max[i]) {
                    min[i]++;
                    remaining--;
                    changed = true;
                    if (remaining == 0) {
                        break;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        for (int i = 0; i < min.length; i++) {
            out.add(new Item(row.get(i).element(), (int) Math.floor(min[i]) == min[i] ? (int) min[i] : -1, (int) max[i]));
        }
        return out;
    }

    /** Ширины карточек, которые панель Plan посчитает для дополнения; -1 - ширина не целая. Возвращает карту элемент -> ширина, ключ CARD - карточка целиком. */
    private static Map<String, Integer> planWidths(boolean hasValues, boolean hasTable, boolean hasWideTable) {
        java.util.List<Item> layout = new java.util.ArrayList<>();
        if (hasValues) {
            layout.add(new Item("VALUES", 4, 6));
        }
        if (hasTable) {
            layout.add(new Item("TABLE", hasWideTable ? 12 : 4, hasWideTable ? 12 : 6));   // extension.wide == есть широкая таблица
        }
        int combined = layout.stream().mapToInt(Item::min).sum();
        boolean hasFullWidth = layout.stream().anyMatch(i -> i.min() == 12);
        boolean split = !hasFullWidth && hasValues && combined == 8 && layout.size() == 2
                || combined == 16 && layout.size() == 2 && layout.stream().anyMatch(i -> i.min() < 8)
                || combined == 16 && (layout.size() == 3 || layout.size() == 4) && !hasValues;
        int card;
        if (split) {
            card = combined / 2;
        } else if (combined > 12) {
            card = 12;
        } else {
            card = combined;
        }
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        result.put("CARD", card);
        for (Item i : expand(layout, card)) {
            result.put(i.element(), i.min());
        }
        return result;
    }

    private static void assertPlanCanDraw(String what, Map<String, Integer> widths) {
        for (var e : widths.entrySet()) {
            int w = e.getValue();
            assertTrue(w >= 1 && w <= 12, what + ": Invalid card width: " + w + " (" + e.getKey() + ")");
        }
    }

    private static boolean has(Class<?> type, Class<? extends java.lang.annotation.Annotation>... annotations) {
        for (Method m : type.getMethods()) {
            for (var a : annotations) {
                if (m.isAnnotationPresent(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    @Test
    void cardWidthsAreValidForPlansLayout() throws Exception {
        plugin.planHook().refresh();
        // воспроизведение ошибки: значения и широкая (3+ столбцов) таблица в одном дополнении без графиков дают ширину 18
        var bug = planWidths(true, true, true);
        assertEquals(18, bug.get("TABLE"), "так выглядит ошибка Invalid card width: 18");
        // наши дополнения
        for (Object ext : new Object[] {extension, tables}) {
            Class<?> type = ext.getClass();
            boolean values = has(type, NumberProvider.class, com.djrapitops.plan.extension.annotation.DoubleProvider.class,
                    com.djrapitops.plan.extension.annotation.PercentageProvider.class, com.djrapitops.plan.extension.annotation.StringProvider.class);
            boolean table = has(type, com.djrapitops.plan.extension.annotation.TableProvider.class);
            boolean wide = false;
            for (Method m : type.getMethods()) {
                if (m.isAnnotationPresent(com.djrapitops.plan.extension.annotation.TableProvider.class)) {
                    Object t = m.getParameterCount() == 0 ? m.invoke(ext) : m.invoke(ext, steve.getUniqueId());
                    wide |= ((Table) t).getMaxColumnSize() >= 3;
                }
            }
            assertPlanCanDraw(type.getSimpleName(), planWidths(values, table, wide));
            assertFalse(values && table && wide, type.getSimpleName() + ": значения и широкая таблица вместе дают ошибку раскладки Plan");
        }
        // в дополнении с показателями таблиц нет, а таблицы лежат в отдельном
        assertFalse(has(IndustryRebornExtension.class, com.djrapitops.plan.extension.annotation.TableProvider.class));
        assertFalse(has(IndustryRebornTablesExtension.class, NumberProvider.class, com.djrapitops.plan.extension.annotation.DoubleProvider.class,
                com.djrapitops.plan.extension.annotation.PercentageProvider.class, com.djrapitops.plan.extension.annotation.StringProvider.class));
    }

    @Test
    void providersAreReadyOnlyAfterTheFirstSnapshot() {
        assertThrows(NotReadyException.class, extension::plots);
        plugin.planHook().refresh();
        assertEquals(1, extension.plots());
    }

    @Test
    void serverValuesComeFromTheSnapshot() {
        plugin.planHook().refresh();
        assertEquals(1, extension.occupied());
        assertEquals(1.0, extension.occupancy(), 1e-9);
        assertTrue(extension.treasury() >= 300);
        assertTrue(extension.income24() >= 300);
        assertEquals(plugin.power().base(), extension.electricityPrice(), 1e-9);
        assertEquals(0, extension.priceIndex(), 1e-9, "автокорректировка выключена");
        assertEquals(0, extension.workshops());
    }

    @Test
    void tablesCarryTheRows() {
        plugin.planHook().refresh();
        Table top = tables.topRevenue();
        assertEquals(1, top.getRows().size());
        assertEquals("Лавка", top.getRows().get(0)[0]);
        assertEquals("Steve", top.getRows().get(0)[1]);
        assertTrue(tables.treasuryOperations().getRows().stream().anyMatch(r -> "Аренда".equals(r[2])));
        Table byCategory = tables.plotsByCategory();
        assertTrue(byCategory.getRows().stream().anyMatch(r -> "Магазин".equals(r[0]) && "1".equals(String.valueOf(r[1])) && "1".equals(String.valueOf(r[2]))));
    }

    @Test
    void playerValuesDescribeTheirPlots() {
        plugin.planHook().refresh();
        assertEquals(1, extension.playerPlots(steve.getUniqueId()));
        assertEquals(1, extension.playerBusinesses(steve.getUniqueId()));
        assertEquals(777, extension.playerRevenue(steve.getUniqueId()), 1e-9);
        assertNotEquals("-", extension.playerNearestRent(steve.getUniqueId()));
        assertEquals(1, tables.playerPlotsTable(steve.getUniqueId()).getRows().size());
        assertThrows(NotReadyException.class, () -> extension.playerPlots(boss.getUniqueId()), "у игрока нет данных");
    }

    @Test
    void disabledFeaturesShowZeroesInsteadOfFailing() {
        plugin.modules().set(Module.ELECTRICITY, false, "Boss");
        plugin.modules().set(Module.HOTELS, false, "Boss");
        plugin.planHook().refresh();
        assertEquals(0, extension.plants());
        assertEquals(0, extension.rooms());
        assertEquals(0.0, extension.load(), 1e-9);
    }

    @Test
    void everyProviderIsPublicAndFollowsPlansSignatureRules() {
        long providers = Arrays.stream(IndustryRebornExtension.class.getMethods())
                .filter(m -> m.isAnnotationPresent(NumberProvider.class)).count();
        assertTrue(providers >= 20, "показателей: " + providers);
        for (Method m : IndustryRebornExtension.class.getMethods()) {
            if (m.getDeclaringClass() != IndustryRebornExtension.class || m.getName().startsWith("lambda")) {
                continue;
            }
            assertTrue(m.getParameterCount() <= 1, m.getName());
        }
        assertTrue(Arrays.asList(extension.callExtensionMethodsOn()).contains(CallEvents.SERVER_PERIODICAL));
    }

    @Test
    void withoutPlanNothingIsRegisteredAndNothingBreaks() {
        assertFalse(plugin.planHook().planInstalled());
        plugin.planHook().sync();
        plugin.planHook().tick();
        assertFalse(plugin.planHook().registered());
        assertNotNull(plugin.planHook().snapshot(), "снимок собирается и без регистрации для первого запроса");
        plugin.modules().set(Module.PLAN, false, "Boss");
        plugin.planHook().sync();
        assertFalse(plugin.planHook().registered());
    }

    @Test
    void snapshotCountsOrdersAndStorage() {
        plugin.storage().add(steve.getUniqueId(), "s", java.util.List.of(new org.bukkit.inventory.ItemStack(Material.DIRT, 5)),
                ru.industryreborn.model.StorageEntry.State.AVAILABLE, null, "t");
        plugin.planHook().refresh();
        assertEquals(5, extension.playerStored(steve.getUniqueId()));
        assertEquals(0, extension.playerOrdersReady(steve.getUniqueId()));
    }
}
