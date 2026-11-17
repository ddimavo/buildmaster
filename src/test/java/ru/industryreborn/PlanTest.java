package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.NotReadyException;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.extractor.ExtensionExtractor;
import com.djrapitops.plan.extension.table.Table;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.plan.IndustryRebornExtension;
import ru.industryreborn.service.Module;
import ru.industryreborn.support.PluginTestBase;

/** Интеграция с Plan: расширение проходит проверку Plan, показатели берутся из снимка данных. */
class PlanTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock boss;
    private IndustryRebornExtension extension;

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
    }

    @Test
    void theExtensionPassesPlansOwnValidation() {
        ExtensionExtractor extractor = new ExtensionExtractor(extension);
        extractor.validateAnnotations();        // бросает исключение при ошибках аннотаций
        assertTrue(extractor.getWarnings().isEmpty(), extractor.getWarnings().toString());
        assertEquals("IndustryReborn", extractor.getPluginInfo().name());
        assertFalse(extractor.getTabInformation().isEmpty());
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
        Table top = extension.topRevenue();
        assertEquals(1, top.getRows().size());
        assertEquals("Лавка", top.getRows().get(0)[0]);
        assertEquals("Steve", top.getRows().get(0)[1]);
        assertTrue(extension.treasuryOperations().getRows().stream().anyMatch(r -> "Аренда".equals(r[2])));
        Table byCategory = extension.plotsByCategory();
        assertTrue(byCategory.getRows().stream().anyMatch(r -> "Магазин".equals(r[0]) && "1".equals(String.valueOf(r[1])) && "1".equals(String.valueOf(r[2]))));
    }

    @Test
    void playerValuesDescribeTheirPlots() {
        plugin.planHook().refresh();
        assertEquals(1, extension.playerPlots(steve.getUniqueId()));
        assertEquals(1, extension.playerBusinesses(steve.getUniqueId()));
        assertEquals(777, extension.playerRevenue(steve.getUniqueId()), 1e-9);
        assertNotEquals("-", extension.playerNearestRent(steve.getUniqueId()));
        assertEquals(1, extension.playerPlotsTable(steve.getUniqueId()).getRows().size());
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
