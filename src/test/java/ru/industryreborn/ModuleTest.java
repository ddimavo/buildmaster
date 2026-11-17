package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.JobRole;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Station;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.Module;
import ru.industryreborn.support.PluginTestBase;

/** Отключение функций: недоступно никому, включая администрацию; расчёты и меню учитывают состояние. */
class ModuleTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock boss;

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
        plugin.getConfig().set("limits.total", 0);
        for (String c : new String[] {"workshop", "shop", "hotel", "room", "house"}) {
            plugin.getConfig().set("limits.categories." + c, 0);
        }
        server.getWorld("world").loadChunk(0, 0);
        steve = player("Steve", 100_000);
        boss = admin("Boss");
    }

    @Test
    void everythingIsEnabledByDefaultAndConfigCanDisable() {
        for (Module m : Module.values()) {
            if (m != Module.AUTO_BALANCE && m != Module.WEBHOOKS) {
                assertTrue(plugin.modules().enabled(m), m.key());
            }
        }
        plugin.getConfig().set("modules.jobs", false);
        assertFalse(plugin.modules().enabled(Module.JOBS));
        assertNotNull(plugin.modules().refusal(Module.JOBS));
    }

    @Test
    void commandTogglesAndTheStateSurvivesARestart() {
        boss.performCommand("district module hotels off");
        assertFalse(plugin.modules().enabled(Module.HOTELS));
        assertTrue(new File(plugin.getDataFolder(), "modules.yml").exists());
        assertFalse(new ru.industryreborn.service.ModuleService(plugin).enabled(Module.HOTELS), "прочитано из файла");
        boss.performCommand("district module hotels on");
        assertTrue(plugin.modules().enabled(Module.HOTELS));
        plugin.getConfig().set("modules.hotels", false);
        boss.performCommand("district module hotels reset");
        assertFalse(plugin.modules().enabled(Module.HOTELS), "сброс возвращает значение из config.yml");
    }

    @Test
    void onlyAdministratorsCanToggle() {
        steve.performCommand("district module jobs off");
        assertTrue(plugin.modules().enabled(Module.JOBS));
    }

    @Test
    void aToggleIsJournaledAndAdminsAreTold() {
        PlayerMock other = admin("Other");
        drain(other);
        boss.performCommand("district module jobs off");
        assertTrue(plugin.journal().forPlot("-").stream().anyMatch(e -> e.action() == Journal.Action.MODULE && e.reason().contains("отключено")));
        assertTrue(drain(other).contains("отключена"));
        drain(steve);
        assertEquals("", drain(steve), "обычным игрокам не пишем");
    }

    @Test
    void aDisabledFeatureBlocksItsCommandsEvenForAdministrators() {
        boss.performCommand("district module jobs off");
        drain(boss);
        boss.performCommand("district job board");
        assertTrue(drain(boss).contains("отключена"));
        assertFalse(boss.getOpenInventory().getTopInventory() != null
                && boss.getOpenInventory().getTopInventory().getHolder() instanceof ru.industryreborn.gui.JobBoardMenu);
        boss.performCommand("district module jobs on");
        boss.performCommand("district job board");
        assertTrue(boss.getOpenInventory().getTopInventory().getHolder() instanceof ru.industryreborn.gui.JobBoardMenu);
    }

    @Test
    void servicesRefuseWhenTheirFeatureIsOff() {
        Plot shop = addPlot("s", Category.SHOP, 0, -1, 10);
        assertTrue(plugin.service().rent(steve, shop, 3));
        plugin.modules().set(Module.JOBS, false, "Boss");
        assertNotNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 1, 10, 0, null));
        plugin.modules().set(Module.GOODS, false, "Boss");
        assertNotNull(plugin.goods().setPrice(steve, shop, Material.IRON_INGOT, 5.0));
        assertFalse(plugin.goods().sells(shop));
        assertNotNull(plugin.goods().buy(steve, shop, Material.IRON_INGOT, 1));
        plugin.modules().set(Module.TREASURY_PAYOUTS, false, "Boss");
        plugin.treasury().deposit(1000, "t");
        assertNotNull(plugin.payouts().subsidy("Boss", shop, 100, "тест"));
        assertEquals(1000, plugin.treasury().balance(), 1.0 + 100_000, "казна не тронута субсидией");
        assertNotNull(plugin.payouts().prize("Boss", steve, 10, "тест"));
        plugin.modules().set(Module.SERVICES, false, "Boss");
        assertNotNull(plugin.orders().configureOffer(steve, shop, "smelt", 1.0, true));
    }

    @Test
    void productionOffEnchantingFollows() {
        Plot w = addPlot("w", Category.WORKSHOP, 40, -1, 10);
        assertTrue(plugin.service().rent(steve, w, 3));
        plugin.modules().set(Module.PRODUCTION, false, "Boss");
        assertNotNull(plugin.factory().assign(steve, w, "1,1,1", "furnace:IRON_INGOT"));
        assertNotNull(plugin.factory().upgrade(steve, w));
        assertNotNull(plugin.factory().load(steve, w, 0));
        assertFalse(plugin.modules().enabled(Module.ENCHANTING), "зачарования зависят от производства");
        assertNotNull(plugin.enchants().propose(steve, w, Material.DIAMOND_SWORD, "sharpness", 1));
        assertTrue(plugin.modules().refusal(Module.ENCHANTING).contains("производство"));
        plugin.modules().set(Module.PRODUCTION, true, "Boss");
        assertTrue(plugin.modules().enabled(Module.ENCHANTING));
    }

    @Test
    void electricityOffMeansEverythingRunsWithoutIt() {
        Plot w = addPlot("w", Category.WORKSHOP, 40, -1, 10);
        assertTrue(plugin.service().rent(steve, w, 3));
        server.getWorld("world").getBlockAt(41, 1, 1).setType(Material.FURNACE);
        plugin.stations().addMachine(w, 41, 1, 1, Station.fromName("FURNACE"));
        plugin.stations().inc(w, Station.fromName("FURNACE"));
        plugin.power().recalc(w);
        assertTrue(plugin.power().isBilled(w));
        assertTrue(w.account.ratePerDay() > 0);
        assertFalse(plugin.power().isPowered(w), "счёт пуст");
        boss.performCommand("district module electricity off");
        assertFalse(plugin.power().isBilled(w));
        assertEquals(0, w.account.ratePerDay(), 1e-9, "тариф обнулён");
        assertTrue(plugin.power().isPowered(w), "станки работают без электричества");
        assertTrue(plugin.power().useStation(w, "smelter"));
        assertFalse(plugin.grid().active());
        drain(boss);
        boss.performCommand("district grid info");
        assertTrue(drain(boss).contains("отключена"));
        boss.performCommand("district power admin info");
        assertTrue(drain(boss).contains("отключена"));
        boss.performCommand("district module electricity on");
        assertTrue(plugin.power().isBilled(w));
        assertTrue(w.account.ratePerDay() > 0, "тариф вернулся");
    }

    @Test
    void thePowerGridIsInactiveWhileElectricityIsOff() {
        addPlot("p", Category.POWER_PLANT, 100, -1, -1);
        assertTrue(plugin.grid().active());
        plugin.modules().set(Module.ELECTRICITY, false, "Boss");
        assertFalse(plugin.grid().active());
        plugin.modules().set(Module.ELECTRICITY, true, "Boss");
        assertTrue(plugin.grid().active());
    }

    @Test
    void hotelsOffBlockRentingRoomsAndRates() {
        plugin.getConfig().set("limits.categories.hotel", 0);
        Plot hotel = addPlot("hot", Category.HOTEL, 60, -1, 10);
        assertTrue(plugin.service().rent(steve, hotel, 3));
        Plot room = new Plot("hot-1", "world", new Box(61, 1, 1, 63, 3, 4));
        room.category = Category.ROOM;
        room.parentId = "hot";
        room.rentPerDay = 50;
        plugin.plots().add(room);
        PlayerMock mia = player("Mia", 1000);
        plugin.modules().set(Module.HOTELS, false, "Boss");
        drain(mia);
        assertFalse(plugin.service().rent(mia, room, 2));
        assertTrue(drain(mia).contains("отключена"));
        assertNotNull(plugin.hotels().setRate(steve, room, 60));
        assertFalse(plugin.service().rent(player("Late", 1000), hotel, 1), "и сам отель");
        assertFalse(plugin.power().isBilled(hotel));
        boss.performCommand("district room list hot");
        plugin.modules().set(Module.HOTELS, true, "Boss");
        assertTrue(plugin.service().rent(mia, room, 2));
    }

    @Test
    void playerPlotsAuctionAndHtmlFollowTheirModules() {
        plugin.modules().set(Module.PLAYER_PLOTS, false, "Boss");
        assertFalse(plugin.playerPlots().enabled());
        plugin.modules().set(Module.AUCTION, false, "Boss");
        assertFalse(plugin.treasuryAuction().enabled());
        plugin.modules().set(Module.HTML_MENUS, false, "Boss");
        assertFalse(plugin.htmlMenus().enabledFor(steve));
    }

    @Test
    void modulesPageListsAllFeaturesWithToggleButtons() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("mods").toFile();
        plugin.htmlMenus().setBackend(new ru.industryreborn.html.GuiBackend() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public File pagesDir() {
                return dir;
            }

            @Override
            public boolean exists(String page) {
                return true;
            }

            @Override
            public boolean open(org.bukkit.entity.Player player, String page) {
                return true;
            }

            @Override
            public void close(org.bukkit.entity.Player player) { }

            @Override
            public String pageOf(org.bukkit.entity.Player player) {
                return null;
            }
        });
        boss.performCommand("district module");
        String suffix = boss.getUniqueId().toString().replace("-", "");
        String html = java.nio.file.Files.readString(new File(dir, "industryreborn/modules-" + suffix + ".html").toPath());
        for (Module m : Module.values()) {
            assertTrue(html.contains("district module " + m.key() + " "), m.key());
        }
        assertTrue(html.contains("district module jobs off"));
        boss.performCommand("district module jobs off");
        plugin.htmlMenus().cleanUp();
        boss.performCommand("district module");
        html = java.nio.file.Files.readString(new File(dir, "industryreborn/modules-" + suffix + ".html").toPath());
        assertTrue(html.contains("district module jobs on"));
    }

    @Test
    void mainPageHidesCardsOfDisabledFeatures() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("mods2").toFile();
        plugin.htmlMenus().setBackend(new ru.industryreborn.html.GuiBackend() {
            public boolean available() {
                return true;
            }

            public File pagesDir() {
                return dir;
            }

            public boolean exists(String page) {
                return true;
            }

            public boolean open(org.bukkit.entity.Player player, String page) {
                return true;
            }

            public void close(org.bukkit.entity.Player player) { }

            public String pageOf(org.bukkit.entity.Player player) {
                return null;
            }
        });
        String suffix = steve.getUniqueId().toString().replace("-", "");
        steve.performCommand("district menu");
        String before = java.nio.file.Files.readString(new File(dir, "industryreborn/main-" + suffix + ".html").toPath());
        assertTrue(before.contains("jobs-" + suffix) && before.contains("orders-" + suffix));
        plugin.modules().set(Module.JOBS, false, "Boss");
        plugin.modules().set(Module.SERVICES, false, "Boss");
        steve.performCommand("district menu");
        String after = java.nio.file.Files.readString(new File(dir, "industryreborn/main-" + suffix + ".html").toPath());
        assertFalse(after.contains("jobs-" + suffix) || after.contains("orders-" + suffix));
    }
}
