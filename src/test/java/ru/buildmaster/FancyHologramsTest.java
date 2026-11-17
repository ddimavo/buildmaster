package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.data.property.Visibility;
import de.oliver.fancyholograms.api.hologram.Hologram;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.holo.FancyHolograms;
import ru.buildmaster.holo.Holo;
import ru.buildmaster.holo.HoloFactory;
import ru.buildmaster.holo.HoloSpec;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.support.PluginTestBase;

/** Голограммы через FancyHolograms: если плагин есть, рисует он, иначе сущности TextDisplay. */
class FancyHologramsTest extends PluginTestBase {

    private FancyHolograms.Manager manager;
    private PlayerMock steve;
    private PlayerMock boss;

    @BeforeEach
    void setUp() {
        FancyHolograms.MANAGER.holograms.clear();
        FancyHolograms.MANAGER.loaded = true;
        manager = FancyHolograms.MANAGER;
        MockBukkit.loadSimple(FancyHolograms.class, "FancyHolograms", "2.10.0");
        server.getWorld("world").loadChunk(0, 0);
        steve = player("Steve", 1000);
        boss = admin("Boss");
        steve.teleport(new Location(server.getWorld("world"), 3, 1, 3));
        boss.teleport(new Location(server.getWorld("world"), 3, 1, 3));
    }

    @AfterEach
    void tearDown() {
        manager.holograms.clear();
    }

    private Holo spawn(String name, HoloSpec spec) {
        return plugin.holoFactory().spawn(name, spec);
    }

    private HoloSpec spec(String text) {
        return new HoloSpec(new Location(server.getWorld("world"), 1, 2, 3), text);
    }

    @Test
    void factoryUsesFancyHologramsWhenInstalled() {
        assertTrue(plugin.holoFactory().fancyActive());
        Holo h = spawn("test", spec("<gold>Привет\nвторая строка"));
        assertEquals(1, manager.holograms.size());
        Hologram created = manager.holograms.get("bm_test");
        assertNotNull(created);
        TextHologramData data = (TextHologramData) created.getData();
        assertEquals(java.util.List.of("<gold>Привет", "вторая строка"), data.getText());
        assertFalse(data.isPersistent(), "голограмма не сохраняется в файл FancyHolograms");
        assertEquals(Display.Billboard.CENTER, data.getBillboard());
        assertEquals(1, data.getLocation().getBlockX());
        assertTrue(h.valid());
    }

    @Test
    void updateTeleportAndRemoveGoThroughTheApi() {
        Holo h = spawn("t", spec("a"));
        h.text("b\nc");
        TextHologramData data = (TextHologramData) manager.holograms.get("bm_t").getData();
        assertEquals(java.util.List.of("b", "c"), data.getText());
        h.teleport(new Location(server.getWorld("world"), 9, 9, 9));
        assertEquals(9, data.getLocation().getBlockX());
        h.remove();
        assertTrue(manager.holograms.isEmpty());
        assertFalse(h.valid());
    }

    @Test
    void aHologramRemovedByFancyHologramsIsNoLongerValid() {
        Holo h = spawn("t", spec("a"));
        manager.holograms.clear();             // например, /fancyholograms reload
        assertFalse(h.valid());
    }

    @Test
    void perViewerHologramsAreManualAndShownToChosenPlayersOnly() {
        Holo h = spawn("panel", spec("x").perViewer(true).left(true));
        FancyHolograms.TestHologram created = (FancyHolograms.TestHologram) manager.holograms.get("bm_panel");
        assertEquals(Visibility.MANUAL, created.getData().getVisibility());
        assertEquals(de.oliver.fancyholograms.api.data.TextHologramData.class, created.getData().getClass());
        assertEquals(org.bukkit.entity.TextDisplay.TextAlignment.LEFT, ((TextHologramData) created.getData()).getTextAlignment());
        h.show(steve);
        assertTrue(created.forced.contains(steve.getUniqueId()));
        h.hide(steve);
        assertFalse(created.forced.contains(steve.getUniqueId()));
    }

    @Test
    void publicHologramsStayVisibleToEveryone() {
        spawn("pub", spec("x"));
        assertEquals(Visibility.ALL, manager.holograms.get("bm_pub").getData().getVisibility());
    }

    @Test
    void viewRangeBecomesBlocksAndScaleIsApplied() {
        spawn("r", spec("x").style(200, 0.25f, 0x78000000).scale(0.5f));
        TextHologramData data = (TextHologramData) manager.holograms.get("bm_r").getData();
        assertEquals(16, data.getVisibilityDistance());
        assertEquals(0.5f, data.getScale().x(), 1e-6);
        assertEquals(0x78, (data.getBackground().asARGB() >>> 24) & 0xFF);
    }

    @Test
    void entityModeIgnoresFancyHolograms() {
        plugin.getConfig().set("holograms.backend", "entity");
        assertFalse(plugin.holoFactory().fancyActive());
        plugin.getConfig().set("holograms.backend", "auto");
        assertTrue(plugin.holoFactory().fancyActive());
    }

    @Test
    void notLoadedFancyHologramsFallsBackToEntities() {
        manager.loaded = false;
        assertFalse(plugin.holoFactory().fancyActive());
    }

    @Test
    void namesAreSafeAndUnique() {
        assertEquals("bm_plot_w1", HoloFactory.fancyName("plot_w1"));
        String a = HoloFactory.fancyName("door_h-1#0");
        String b = HoloFactory.fancyName("door_h-1#1");
        assertTrue(a.matches("[A-Za-z0-9_-]+") && b.matches("[A-Za-z0-9_-]+"), a);
        assertNotEquals(a, b);
        assertNotEquals(HoloFactory.fancyName("x#1"), HoloFactory.fancyName("x_1"), "разные ключи с одинаковой очисткой не сливаются");
        assertEquals(HoloFactory.fancyName("x#1"), HoloFactory.fancyName("x#1"), "имя стабильно");
    }

    @Test
    void twoHologramsWithTheSameNameReplaceTheOldOne() {
        spawn("same", spec("one"));
        spawn("same", spec("two"));
        assertEquals(1, manager.holograms.size());
        assertEquals(java.util.List.of("two"), ((TextHologramData) manager.holograms.get("bm_same").getData()).getText());
    }

    // ------------------------------------------------------------ сервисы BuildMaster поверх FancyHolograms

    @Test
    void bankBoardsAreCreatedInFancyHolograms() {
        Plot bank = addPlot("bank", Category.BANK, 0, -1, -1);
        bank.hasHolo = true;
        bank.holoX = 3;
        bank.holoY = 5;
        bank.holoZ = 3;
        plugin.treasury().deposit(321, "Тест");
        plugin.bankHolograms().tick();
        assertEquals(2, manager.holograms.size(), manager.holograms.keySet().toString());
        String all = manager.holograms.values().stream().map(h -> String.join("\n", ((TextHologramData) h.getData()).getText()))
                .reduce("", (x, y) -> x + y);
        assertTrue(all.contains("Налоги") && all.contains("321") && all.contains("Последние операции"), all);
        // игрок ушёл: голограммы убраны
        steve.teleport(new Location(server.getWorld("world"), 500, 1, 500));
        boss.teleport(new Location(server.getWorld("world"), 500, 1, 500));
        plugin.bankHolograms().tick();
        assertTrue(manager.holograms.isEmpty());
    }

    @Test
    void anExternalReloadOfFancyHologramsRecreatesOurHolograms() {
        Plot bank = addPlot("bank", Category.BANK, 0, -1, -1);
        bank.hasHolo = true;
        bank.holoX = 3;
        bank.holoY = 5;
        bank.holoZ = 3;
        plugin.bankHolograms().tick();
        assertEquals(2, manager.holograms.size());
        manager.holograms.clear();
        plugin.bankHolograms().tick();
        assertEquals(2, manager.holograms.size(), "пересозданы");
    }

    @Test
    void plotHologramUsesFancyHolograms() {
        Plot shop = addPlot("s1", Category.SHOP, 0, 500, 20);
        shop.hasHolo = true;
        shop.holoX = 3;
        shop.holoY = 3;
        shop.holoZ = 3;
        plugin.holograms().update(shop);
        Hologram h = manager.holograms.get("bm_plot_s1");
        assertNotNull(h);
        assertTrue(String.join("\n", ((TextHologramData) h.getData()).getText()).contains("s1"));
        plugin.holograms().remove(shop);
        assertTrue(manager.holograms.isEmpty());
    }

    @Test
    void removeAllCleansEverythingOnDisable() {
        Plot shop = addPlot("s1", Category.SHOP, 0, 500, 20);
        shop.hasHolo = true;
        shop.holoX = 3;
        shop.holoY = 3;
        shop.holoZ = 3;
        plugin.holograms().update(shop);
        plugin.holograms().removeAll();
        assertTrue(manager.holograms.isEmpty());
    }

    @Test
    void withoutTheFancyPluginEntitiesAreUsed() {
        MockBukkit.getMock().getPluginManager().disablePlugin(server.getPluginManager().getPlugin("FancyHolograms"));
        assertFalse(plugin.holoFactory().fancyActive());
    }
}
