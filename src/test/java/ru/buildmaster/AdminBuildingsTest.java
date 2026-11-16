package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.gui.AccountMenu;
import ru.buildmaster.gui.AccountsMenu;
import ru.buildmaster.gui.BankMenu;
import ru.buildmaster.gui.BankSettingsMenu;
import ru.buildmaster.gui.GridMenu;
import ru.buildmaster.gui.MainMenu;
import ru.buildmaster.gui.Menu;
import ru.buildmaster.gui.PlantMenu;
import ru.buildmaster.gui.ReportMenu;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.PlotFilter;
import ru.buildmaster.support.PluginTestBase;

/** Банк и электростанция как административные здания, а также их меню. */
class AdminBuildingsTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock boss;
    private Plot bank;
    private Plot plant;

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
        bank = addPlot("bank", Category.BANK, 100, -1, -1);
        plant = addPlot("p1", Category.POWER_PLANT, 120, -1, -1);
        plant.plantCapacity = 1000;
    }

    /** Пустой слот меню - либо null, либо серое стекло-заполнитель. */
    private static boolean blank(ItemStack item) {
        return item == null || item.getType().name().endsWith("_STAINED_GLASS_PANE");
    }

    private boolean openMenu(PlayerMock p, Class<? extends Menu> type) {
        return type.isInstance(p.getOpenInventory().getTopInventory().getHolder());
    }

    // ------------------------------------------------------------ административные здания

    @Test
    void nobodyCanBuyOrRentTheBankOrThePlant() {
        for (Plot p : List.of(bank, plant)) {
            p.price = 100;       // даже если цену выставили по ошибке
            p.rentPerDay = 10;
            assertFalse(plugin.service().buy(steve, p), p.id);
            assertFalse(plugin.service().rent(steve, p, 3), p.id);
            assertTrue(p.isFree());
        }
        assertEquals(100_000, economy.getBalance(steve), 1e-9);
    }

    @Test
    void theyAreNotOfferedInTheListOfFreePlots() {
        Plot shop = addPlot("s", Category.SHOP, 20, 100, -1);
        var visible = new PlotFilter(PlotFilter.Mode.FREE).apply(plugin.plots().all(), steve.getUniqueId());
        assertEquals(List.of(shop), visible);
    }

    @Test
    void playersCannotBuildInsideButCanUseDoorsAndSigns() {
        Block b = server.getWorld("world").getBlockAt(105, 3, 3);
        b.setType(Material.STONE);
        BlockPlaceEvent place = new BlockPlaceEvent(b, b.getState(), b.getRelative(0, -1, 0),
                new ItemStack(Material.STONE), steve, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);
        assertTrue(place.isCancelled());

        Block door = server.getWorld("world").getBlockAt(106, 3, 3);
        door.setType(Material.OAK_DOOR);
        PlayerInteractEvent use = new PlayerInteractEvent(steve, Action.RIGHT_CLICK_BLOCK, null, door,
                org.bukkit.block.BlockFace.NORTH, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(use);
        assertFalse(use.isCancelled());
    }

    @Test
    void createAcceptsDashesForPriceAndRentOfAdministrativeTypes() {
        boss.performCommand("district create bank2 bank - -");
        String out = drain(boss);
        assertFalse(out.contains("Нужна хотя бы цена"), out);
        assertTrue(out.contains("выделите область"), out);

        boss.performCommand("district create shop2 shop - -");
        assertTrue(drain(boss).contains("Нужна хотя бы цена"), "для обычных типов цена нужна");
    }

    @Test
    void stationLimitsDoNotRestrictTheNewTypes() {
        assertEquals(-1, plugin.stations().limit(Category.BANK, ru.buildmaster.model.Station.ANVIL));
        assertEquals(-1, plugin.stations().limit(Category.POWER_PLANT, ru.buildmaster.model.Station.FURNACE));
    }

    @Test
    void newTypesAreRememberedAcrossRestarts() {
        plant.plantCapacity = 777;
        plant.plantMultiplier = 1.14;
        plugin.plots().save();
        plugin.plots().load();
        Plot loaded = plugin.plots().get("p1");
        assertEquals(Category.POWER_PLANT, loaded.category);
        assertEquals(777, loaded.plantCapacity, 1e-9);
        assertEquals(1.14, loaded.plantMultiplier, 1e-9);
        assertEquals(Category.BANK, plugin.plots().get("bank").category);
    }

    @Test
    void connectionAndTaxFieldsArePersisted() {
        Plot w = addPlot("w", Category.WORKSHOP, 40, -1, 10);
        plugin.service().rent(steve, w, 3);
        plugin.grid().connect(w, plant, "Boss");
        w.taxDebt = 12.5;
        w.account.setUnitsPerDay(3.6);
        plugin.plots().save();
        plugin.plots().load();
        Plot loaded = plugin.plots().get("w");
        assertEquals("p1", loaded.plantId);
        assertEquals("p1", loaded.lastPlantId);
        assertEquals(12.5, loaded.taxDebt, 1e-9);
        assertTrue(loaded.nextTaxAt > 0);
        assertEquals(3.6, loaded.account.unitsPerDay(), 1e-9);
    }

    @Test
    void disconnectedStateIsPersisted() {
        Plot w = addPlot("w", Category.WORKSHOP, 40, -1, 10);
        plugin.service().rent(steve, w, 3);
        plugin.grid().disconnect(w, "авария на линии", "Boss");
        plugin.plots().save();
        plugin.plots().load();
        Plot loaded = plugin.plots().get("w");
        assertNull(loaded.plantId);
        assertEquals("авария на линии", loaded.gridReason);
        assertEquals("p1", loaded.lastPlantId);
    }

    // ------------------------------------------------------------ меню

    @Test
    void bankMenuOpensForEveryoneAndShowsStaffSectionsToStaffOnly() {
        new BankMenu(plugin, steve).open();
        assertTrue(openMenu(steve, BankMenu.class));
        assertTrue(blank(steve.getOpenInventory().getTopInventory().getItem(23)), "настроек банка у игрока нет");

        new BankMenu(plugin, boss).open();
        assertFalse(blank(boss.getOpenInventory().getTopInventory().getItem(23)));
        assertFalse(blank(boss.getOpenInventory().getTopInventory().getItem(21)));
        assertFalse(blank(boss.getOpenInventory().getTopInventory().getItem(14)));
    }

    @Test
    void accountMenusOpen() {
        Plot shop = addPlot("s", Category.SHOP, 20, 100, -1);
        plugin.service().buy(steve, shop);
        plugin.power().deposit(steve, shop, 50);

        new AccountsMenu(plugin, steve, false, 0).open();
        assertTrue(openMenu(steve, AccountsMenu.class));
        assertFalse(blank(steve.getOpenInventory().getTopInventory().getItem(0)), "свой счёт в списке");

        new AccountsMenu(plugin, boss, true, 0).open();
        assertFalse(blank(boss.getOpenInventory().getTopInventory().getItem(0)));

        new AccountMenu(plugin, boss, shop).open();
        assertTrue(openMenu(boss, AccountMenu.class));
    }

    @Test
    void debtButtonIsOnlyForBankStaff() {
        Plot shop = addPlot("s", Category.SHOP, 20, 100, -1);
        plugin.service().buy(steve, shop);
        shop.taxDebt = 40;

        new AccountMenu(plugin, steve, shop).open();
        assertTrue(blank(steve.getOpenInventory().getTopInventory().getItem(11)));
        new AccountMenu(plugin, boss, shop).open();
        assertFalse(blank(boss.getOpenInventory().getTopInventory().getItem(11)));
    }

    @Test
    void settingsMenuRefusesOrdinaryPlayers() {
        new BankSettingsMenu(plugin, steve).open();
        var top = steve.getOpenInventory().getTopInventory();
        assertTrue(blank(top.getItem(10)));
        assertFalse(blank(top.getItem(13)));

        new BankSettingsMenu(plugin, boss).open();
        assertFalse(blank(boss.getOpenInventory().getTopInventory().getItem(10)));
        assertFalse(blank(boss.getOpenInventory().getTopInventory().getItem(14)));
    }

    @Test
    void settingsMenuTogglesBankOnlyMode() {
        new BankSettingsMenu(plugin, boss).open();
        assertFalse(plugin.bank().onlyInBank());
        boss.simulateInventoryClick(14);
        assertTrue(plugin.bank().onlyInBank());
    }

    @Test
    void gridMenusOpenForGridStaffOnly() {
        new GridMenu(plugin, steve, 0).open();
        assertTrue(blank(steve.getOpenInventory().getTopInventory().getItem(9)), "список станций игроку не показывается");

        new GridMenu(plugin, boss, 0).open();
        assertTrue(openMenu(boss, GridMenu.class));
        assertFalse(blank(boss.getOpenInventory().getTopInventory().getItem(9)), "станция p1");

        new PlantMenu(plugin, boss, plant, 0).open();
        assertTrue(openMenu(boss, PlantMenu.class));

        plugin.districts().create("Центр");
        new ReportMenu(plugin, boss, null).open();
        assertTrue(openMenu(boss, ReportMenu.class));
        new ReportMenu(plugin, boss, plugin.districts().get("Центр")).open();
        assertTrue(openMenu(boss, ReportMenu.class));
    }

    @Test
    void plantMenuAdjustsCapacityWithButtons() {
        new PlantMenu(plugin, boss, plant, 0).open();
        boss.simulateInventoryClick(7);     // +100
        assertEquals(1100, plant.plantCapacity, 1e-9);
        boss.simulateInventoryClick(1);     // -100
        assertEquals(1000, plant.plantCapacity, 1e-9);
        boss.simulateInventoryClick(2);     // -10
        assertEquals(990, plant.plantCapacity, 1e-9);
    }

    @Test
    void plantMenuListsConnectedWorkshops() {
        Plot w = addPlot("w", Category.WORKSHOP, 40, -1, 10);
        plugin.service().rent(steve, w, 3);
        assertEquals("p1", w.plantId);

        new PlantMenu(plugin, boss, plant, 0).open();
        assertFalse(blank(boss.getOpenInventory().getTopInventory().getItem(9)));
    }

    @Test
    void autoConnectButtonInTheGridMenu() {
        Plot w = addPlot("w", Category.WORKSHOP, 40, -1, 10);
        plugin.service().rent(steve, w, 3);
        plugin.grid().disconnect(w, "причина", "Boss");
        assertNull(w.plantId);

        new GridMenu(plugin, boss, 0).open();
        boss.simulateInventoryClick(47);

        assertEquals("p1", w.plantId);
    }

    @Test
    void mainMenuShowsBankToEveryoneAndGridToStaff() {
        new MainMenu(plugin, steve).open();
        assertFalse(blank(steve.getOpenInventory().getTopInventory().getItem(24)));
        assertTrue(blank(steve.getOpenInventory().getTopInventory().getItem(25)));
        new MainMenu(plugin, boss).open();
        assertFalse(blank(boss.getOpenInventory().getTopInventory().getItem(25)));
    }

    @Test
    void menuClicksNeverLetPlayersTakeItems() {
        new BankMenu(plugin, boss).open();
        boss.simulateInventoryClick(10);
        // меню открылось/сменилось, а предметы из него в инвентарь не попали
        assertEquals(0, java.util.Arrays.stream(boss.getInventory().getContents())
                .filter(i -> i != null && !i.getType().isAir()).count());
    }
}
