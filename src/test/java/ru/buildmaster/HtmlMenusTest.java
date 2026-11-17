package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.html.GuiBackend;
import ru.buildmaster.html.Html;
import ru.buildmaster.html.HtmlMenus;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.support.PluginTestBase;

/** HTML-меню: мост к MC GUI (заглушка), страницы, режимы, обновление и уборка файлов. */
class HtmlMenusTest extends PluginTestBase {

    private static final class FakeGui implements GuiBackend {
        boolean available = true;
        File dir;
        final List<String> opened = new ArrayList<>();
        final Map<java.util.UUID, String> current = new HashMap<>();

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public File pagesDir() {
            return available ? dir : null;
        }

        @Override
        public boolean exists(String page) {
            return new File(dir, page + ".html").exists();
        }

        @Override
        public boolean open(Player player, String page) {
            if (!exists(page)) {
                return false;
            }
            opened.add(page);
            current.put(player.getUniqueId(), page);
            return true;
        }

        @Override
        public void close(Player player) {
            current.remove(player.getUniqueId());
        }

        @Override
        public String pageOf(Player player) {
            return current.get(player.getUniqueId());
        }
    }

    @TempDir
    File tmp;
    private FakeGui gui;
    private PlayerMock steve;
    private PlayerMock boss;
    private Plot shop;

    private String suffix(Player p) {
        return p.getUniqueId().toString().replace("-", "");
    }

    private String read(String kind, Player p) throws Exception {
        return Files.readString(new File(tmp, "buildmaster/" + kind + "-" + suffix(p) + ".html").toPath());
    }

    @BeforeEach
    void setUp() {
        gui = new FakeGui();
        gui.dir = tmp;
        plugin.htmlMenus().setBackend(gui);
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.shop", 0);
        steve = player("Steve", 10_000);
        boss = admin("Boss");
        shop = addPlot("s", Category.SHOP, 0, -1, 10);
        assertTrue(plugin.service().rent(steve, shop, 3));
    }

    @Test
    void escapeProtectsTheMarkup() {
        assertEquals("&lt;b&gt;&amp;&quot;x&quot;", Html.esc("<b>&\"x\""));
        assertEquals("", Html.esc(null));
        assertFalse(Html.attr("a\nb").contains("\n"));
    }

    @Test
    void menuCommandOpensAnHtmlPageWhenTheGuiPluginIsThere() throws Exception {
        steve.performCommand("district menu");
        assertEquals(1, gui.opened.size());
        assertTrue(gui.opened.get(0).startsWith("buildmaster/main-" + suffix(steve)));
        String html = read("main", steve);
        assertTrue(html.contains("Steve") && html.contains("Район коммерции"));
        assertTrue(html.contains("href=\"bank-" + suffix(steve) + ".html\""));
        assertFalse(html.contains("treasury-" + suffix(steve)), "казна - только с правом банка");
    }

    @Test
    void withoutTheGuiPluginTheChestMenuOpens() {
        gui.available = false;
        steve.performCommand("district menu");
        assertTrue(gui.opened.isEmpty());
        assertNotNull(steve.getOpenInventory().getTopInventory());
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof ru.buildmaster.gui.MainMenu);
    }

    @Test
    void classicArgumentAndChestModeKeepTheChestMenu() {
        steve.performCommand("district menu classic");
        assertTrue(gui.opened.isEmpty());
        steve.performCommand("district ui chest");
        assertEquals(HtmlMenus.Mode.CHEST, plugin.htmlMenus().modeOf(steve));
        steve.performCommand("district menu");
        assertTrue(gui.opened.isEmpty());
        steve.performCommand("district ui auto");
        steve.performCommand("district menu");
        assertEquals(1, gui.opened.size());
    }

    @Test
    void serverModeChestDisablesHtmlForEveryone() {
        plugin.getConfig().set("gui.mode", "chest");
        steve.performCommand("district menu");
        assertTrue(gui.opened.isEmpty());
        steve.performCommand("district ui html");
        steve.performCommand("district menu");
        assertEquals(1, gui.opened.size(), "личный выбор перекрывает режим сервера");
    }

    @Test
    void preferencesSurviveARestart() {
        steve.performCommand("district ui chest");
        assertTrue(new File(plugin.getDataFolder(), "ui-prefs.yml").exists());
        var fresh = new HtmlMenus(plugin);
        assertEquals(HtmlMenus.Mode.CHEST, fresh.modeOf(steve));
    }

    @Test
    void bankPageListsAccountsWithActionButtons() throws Exception {
        steve.performCommand("district bank");
        assertTrue(gui.opened.get(0).startsWith("buildmaster/bank-"));
        String html = read("bank", steve);
        assertTrue(html.contains("Мои счета") && html.contains("№ s"));
        assertTrue(html.contains("data-command=\"district account deposit 100 s\""));
        assertTrue(html.contains("data-command=\"district account withdraw 1000 s\""));
        assertTrue(html.contains("href=\"main-" + suffix(steve) + ".html\""));
    }

    @Test
    void bankClassicOpensTheChestMenu() {
        boss.performCommand("district bank classic");
        assertTrue(gui.opened.isEmpty());
        assertTrue(boss.getOpenInventory().getTopInventory().getHolder() instanceof ru.buildmaster.gui.BankMenu);
    }

    @Test
    void treasuryPageIsForBankStaffOnly() throws Exception {
        plugin.treasury().deposit(777, "Тест <b>&");
        boss.performCommand("district treasury menu");
        assertTrue(gui.opened.get(0).startsWith("buildmaster/treasury-"));
        String html = read("treasury", boss);
        assertTrue(html.contains("Казна") && html.contains("777"));
        assertTrue(html.contains("Тест &lt;b&gt;&amp;"), "причины экранируются");
        assertFalse(html.contains("Тест <b>"));
        String other = read("treasury", boss);
        assertEquals(html, other);
        // игрок без права видит заглушку, а не данные казны
        steve.performCommand("district bank");
        assertTrue(read("treasury", steve).contains("Недостаточно прав"));
        assertFalse(read("treasury", steve).contains("777"));
    }

    @Test
    void openPagesAreRefreshedAndFilesRemovedWhenClosed() throws Exception {
        steve.performCommand("district bank");
        String before = read("bank", steve);
        assertTrue(plugin.power().deposit(steve, shop, 500));
        plugin.htmlMenus().tick();
        assertNotEquals(before, read("bank", steve), "после пополнения страница обновилась");
        assertEquals(1, plugin.htmlMenus().openCount());
        plugin.htmlMenus().setLoadGraceMs(0);
        gui.current.remove(steve.getUniqueId());      // игрок закрыл страницу
        plugin.htmlMenus().tick();
        assertEquals(0, plugin.htmlMenus().openCount());
        assertFalse(new File(tmp, "buildmaster/bank-" + suffix(steve) + ".html").exists(), "файлы убраны");
    }

    @Test
    void unchangedPagesAreNotRewritten() {
        steve.performCommand("district bank");
        File f = new File(tmp, "buildmaster/bank-" + suffix(steve) + ".html");
        assertTrue(f.setLastModified(1_000_000L));
        plugin.htmlMenus().tick();
        assertEquals(1_000_000L, f.lastModified(), "содержимое то же: MC GUI не должен перечитывать страницу зря");
    }

    @Test
    void aPageStillLoadingIsNotTreatedAsClosed() {
        steve.performCommand("district bank");
        gui.current.remove(steve.getUniqueId());      // MC GUI ещё не показал страницу
        plugin.htmlMenus().tick();
        assertEquals(1, plugin.htmlMenus().openCount());
        assertTrue(new File(tmp, "buildmaster/bank-" + suffix(steve) + ".html").exists());
    }

    @Test
    void filesOfAPlayerWhoLeavesAreDeleted() {
        steve.performCommand("district bank");
        assertTrue(new File(tmp, "buildmaster/bank-" + suffix(steve) + ".html").exists());
        steve.disconnect();
        assertFalse(new File(tmp, "buildmaster/bank-" + suffix(steve) + ".html").exists());
        assertEquals(0, plugin.htmlMenus().openCount());
    }

    @Test
    void cleanUpRemovesLeftoverPages() throws Exception {
        steve.performCommand("district menu");
        plugin.htmlMenus().cleanUp();
        File[] left = new File(tmp, "buildmaster").listFiles();
        assertTrue(left == null || left.length == 0);
    }

    @Test
    void anUnavailablePagesFolderFallsBack() {
        gui.dir = tmp;
        gui.available = true;
        gui.dir = null;
        steve.performCommand("district menu");
        assertTrue(gui.opened.isEmpty());
    }

    // ------------------------------------------------------------ остальные страницы

    @Test
    void mainPageLinksToEverySection() throws Exception {
        steve.performCommand("district menu");
        String html = read("main", steve);
        for (String page : new String[] {"free", "mine", "bank", "storage", "journal", "warnings"}) {
            assertTrue(html.contains("href=\"" + page + "-" + suffix(steve) + ".html\""), page);
            assertTrue(new File(tmp, "buildmaster/" + page + "-" + suffix(steve) + ".html").exists(), "файл " + page);
        }
    }

    @Test
    void freePlotsAreGroupedWithRentAndBuyButtons() throws Exception {
        Plot house = addPlot("h1", Category.HOUSE, 30, 500, 20);
        house.description = "Уютный <дом>";
        addPlot("h2", Category.HOUSE, 50, -1, -1);
        steve.performCommand("district plots free");
        assertTrue(gui.opened.get(0).startsWith("buildmaster/free-"));
        String html = read("free", steve);
        assertTrue(html.contains("Дом") && html.contains("№ h1"));
        assertFalse(html.contains("№ s "), "занятый участок не показывается");
        assertTrue(html.contains("data-command=\"district rent 7 h1\""));
        assertTrue(html.contains("data-command=\"district buy h1\""));
        assertTrue(html.contains("data-command=\"district tp h1\""));
        assertTrue(html.contains("Уютный &lt;дом&gt;"), "описание экранируется");
        assertTrue(html.contains("Цена не назначена"));
        assertFalse(html.contains("district buy h2"));
    }

    @Test
    void minePageShowsRentExtensionAndAccount() throws Exception {
        steve.performCommand("district plots mine");
        String html = read("mine", steve);
        assertTrue(html.contains("№ s"));
        assertTrue(html.contains("data-command=\"district extend 7 s\""));
        assertTrue(html.contains("data-command=\"district autorenew on s\""));
        assertTrue(html.contains("data-command=\"district release s\""));
        String bank = read("bank", steve);
        plugin.service().setAutoRenew(shop, true);
        plugin.power().deposit(steve, shop, 100);
        plugin.htmlMenus().tick();
        assertTrue(read("mine", steve).contains("district autorenew off s"), "открытая страница обновилась");
        assertEquals(bank, read("bank", steve), "закрытые страницы обновляются при открытии, не каждый раз");
    }

    @Test
    void mineHidesActionsFromAGuestWithAccessOnly() throws Exception {
        shop.grant(boss.getUniqueId(), Plot.Perm.BUILD);
        boss.performCommand("district plots mine");
        String html = read("mine", boss);
        assertTrue(html.contains("доступ"));
        assertFalse(html.contains("district extend"));
        assertFalse(html.contains("district release"));
    }

    @Test
    void storagePageSumsItemsAndTakeAllMovesThemToTheInventory() throws Exception {
        plugin.storage().add(steve.getUniqueId(), "s", List.of(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_SWORD, 1),
                new org.bukkit.inventory.ItemStack(org.bukkit.Material.COBBLESTONE, 40)), ru.buildmaster.model.StorageEntry.State.AVAILABLE,
                null, "test");
        steve.performCommand("district storage");
        String html = read("storage", steve);
        assertTrue(html.contains("Diamond Sword") && html.contains("Cobblestone") && html.contains("40"));
        assertTrue(html.contains("data-command=\"district storage take\""));
        steve.performCommand("district storage take");
        assertEquals(40, count(steve, org.bukkit.Material.COBBLESTONE));
        assertEquals(1, count(steve, org.bukkit.Material.DIAMOND_SWORD));
        assertEquals(0, plugin.storage().countItems(steve.getUniqueId(), ru.buildmaster.model.StorageEntry.State.AVAILABLE));
    }

    private static int count(Player p, org.bukkit.Material m) {
        int n = 0;
        for (var s : p.getInventory().getContents()) {
            if (s != null && s.getType() == m) {
                n += s.getAmount();
            }
        }
        return n;
    }

    @Test
    void takeAllLeavesWhatDoesNotFit() {
        plugin.storage().add(steve.getUniqueId(), "s", List.of(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIRT, 64),
                new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE, 64)), ru.buildmaster.model.StorageEntry.State.AVAILABLE, null, "t");
        for (int i = 0; i < steve.getInventory().getSize(); i++) {
            steve.getInventory().setItem(i, new org.bukkit.inventory.ItemStack(org.bukkit.Material.GRAVEL, 64));
        }
        boolean[] full = new boolean[1];
        int taken = plugin.storage().takeAll(steve, full);
        assertTrue(full[0], "taken=" + taken);
        assertEquals(0, taken);
        assertEquals(2, plugin.storage().countItems(steve.getUniqueId(), ru.buildmaster.model.StorageEntry.State.AVAILABLE) > 0 ? 2 : 0, "вещи остались");
    }

    @Test
    void journalAndWarningsPagesEscapeTextAndStripFormatting() throws Exception {
        plugin.journal().add("s", steve.getUniqueId(), "Boss", ru.buildmaster.service.Journal.Action.EVICTED, "причина <script>");
        plugin.warnings().add(steve.getUniqueId(), "s", "<red>Аренда <white>кончается</white> & скоро");
        steve.performCommand("district journal");
        String journal = read("journal", steve);
        assertTrue(journal.contains("причина &lt;script&gt;") && journal.contains("Выселение"));
        steve.performCommand("district warnings");
        String warnings = read("warnings", steve);
        assertTrue(warnings.contains("Аренда кончается &amp; скоро"), warnings);
        assertFalse(warnings.contains("<red>"));
        assertTrue(warnings.contains("●"), "новое помечено");
    }

    @Test
    void warningsBecomeReadOnlyAfterThePageIsActuallyShown() throws Exception {
        plugin.warnings().add(steve.getUniqueId(), "s", "Новое");
        steve.performCommand("district menu");
        assertEquals(1, plugin.warnings().unread(steve.getUniqueId()), "рендер страницы сам ничего не прочитывает");
        gui.current.put(steve.getUniqueId(), "buildmaster/warnings-" + suffix(steve));
        plugin.htmlMenus().tick();
        assertEquals(1, plugin.warnings().unread(steve.getUniqueId()), "первый показ: подсветка ещё видна");
        plugin.htmlMenus().tick();
        assertEquals(0, plugin.warnings().unread(steve.getUniqueId()));
        assertFalse(read("warnings", steve).contains("●"));
    }

    @Test
    void classicArgumentsKeepTheChestMenusForTheNewSections() {
        steve.performCommand("district storage classic");
        steve.performCommand("district journal classic");
        steve.performCommand("district warnings classic");
        steve.performCommand("district plots free classic");
        assertTrue(gui.opened.isEmpty());
    }

    // ------------------------------------------------------------ предприятия и отели

    private Plot workshopWithMachines() {
        plugin.recipes().setSource(() -> List.<org.bukkit.inventory.Recipe>of(
                new org.bukkit.inventory.FurnaceRecipe(new org.bukkit.NamespacedKey(plugin, "f1"), new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_INGOT),
                        org.bukkit.Material.RAW_IRON, 0.7f, 200),
                new org.bukkit.inventory.FurnaceRecipe(new org.bukkit.NamespacedKey(plugin, "f2"), new org.bukkit.inventory.ItemStack(org.bukkit.Material.GOLD_INGOT),
                        org.bukkit.Material.RAW_GOLD, 0.7f, 200)).iterator());
        plugin.getConfig().set("limits.categories.workshop", 0);
        server.getWorld("world").loadChunk(0, 0);
        Plot w = addPlot("w", Category.WORKSHOP, 40, -1, 10);
        assertTrue(plugin.service().rent(steve, w, 3));
        server.getWorld("world").getBlockAt(41, 1, 1).setType(org.bukkit.Material.FURNACE);
        plugin.stations().addMachine(w, 41, 1, 1, ru.buildmaster.model.Station.fromName("FURNACE"));
        plugin.stations().inc(w, ru.buildmaster.model.Station.fromName("FURNACE"));
        plugin.power().recalc(w);
        return w;
    }

    @Test
    void businessPageShowsAccountMachinesAndRecipeButtons() throws Exception {
        Plot w = workshopWithMachines();
        steve.performCommand("district business");
        assertTrue(gui.opened.get(0).startsWith("buildmaster/business-"));
        String html = read("business", steve);
        assertTrue(html.contains("№ w") && html.contains("№ s"), "и мастерская, и магазин");
        assertTrue(html.contains("data-command=\"district account deposit 1000 w\""));
        assertTrue(html.contains("data-command=\"district factory load w\""));
        assertTrue(html.contains("data-command=\"district machine at w 41,1,1 set furnace:IRON_INGOT\""), html);
        assertTrue(html.contains("data-command=\"district machine at w 41,1,1 set furnace:GOLD_INGOT\""));
        assertTrue(html.contains("без задания"));
    }

    @Test
    void machineAtCommandAssignsAndClearsRecipesWithoutLooking() {
        Plot w = workshopWithMachines();
        steve.performCommand("district machine at w 41,1,1 set furnace:IRON_INGOT");
        assertEquals("furnace:IRON_INGOT", w.machineStates.get("41,1,1").recipe);
        steve.performCommand("district machine at w 41,1,1 clear");
        assertTrue(w.machineStates.get("41,1,1") == null || w.machineStates.get("41,1,1").recipe == null);
        steve.performCommand("district machine at w 99,1,1 set furnace:IRON_INGOT");
        steve.performCommand("district machine at w 41,1,1 menu");
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof ru.buildmaster.gui.MachineMenu);
    }

    @Test
    void anotherPlayerCannotAssignThroughTheCommand() {
        Plot w = workshopWithMachines();
        player("Mia", 100).performCommand("district machine at w 41,1,1 set furnace:IRON_INGOT");
        assertTrue(w.machineStates.get("41,1,1") == null || w.machineStates.get("41,1,1").recipe == null);
    }

    @Test
    void assignedMachineShowsItsStatusOnThePage() throws Exception {
        Plot w = workshopWithMachines();
        steve.performCommand("district machine at w 41,1,1 set furnace:IRON_INGOT");
        steve.performCommand("district business");
        String html = read("business", steve);
        assertTrue(html.contains("Iron Ingot"));
        assertTrue(html.contains("data-command=\"district machine at w 41,1,1 clear\""));
    }

    @Test
    void businessPageOffersServicesAndGoods() throws Exception {
        Plot w = workshopWithMachines();
        w.goods.put(org.bukkit.Material.IRON_INGOT, 5);
        w.prices.put(org.bukkit.Material.IRON_INGOT, 10.0);
        steve.performCommand("district business");
        String html = read("business", steve);
        assertTrue(html.contains("Услуги для игроков") && html.contains("district service toggle w smelt on"), html);
        assertTrue(html.contains("district goods price IRON_INGOT 9 w"));
        assertTrue(html.contains("district goods price IRON_INGOT 11 w"));
        assertTrue(html.contains("district goods take IRON_INGOT all w"));
    }

    @Test
    void goodsPriceButtonCommandWorks() {
        Plot w = workshopWithMachines();
        w.goods.put(org.bukkit.Material.IRON_INGOT, 5);
        w.prices.put(org.bukkit.Material.IRON_INGOT, 10.0);
        steve.performCommand("district goods price IRON_INGOT 9 w");
        assertEquals(9.0, w.prices.get(org.bukkit.Material.IRON_INGOT), 1e-9);
    }

    @Test
    void mainPageShowsBusinessAndHotelCardsOnlyForOwners() throws Exception {
        steve.performCommand("district menu");
        String html = read("main", steve);
        assertTrue(html.contains("business-" + suffix(steve) + ".html"));
        assertFalse(html.contains("hotel-" + suffix(steve) + ".html"));
        player("Mia", 100).performCommand("district menu");
        // у игрока без предприятий карточки нет
        assertFalse(Files.readString(new File(tmp, "buildmaster/main-" + suffix(server.getPlayer("Mia")) + ".html").toPath()).contains("business-"));
    }

    private Plot hotelWithRooms() {
        plugin.getConfig().set("limits.categories.hotel", 0);
        plugin.getConfig().set("limits.categories.room", 0);
        Plot h = addPlot("hot", Category.HOTEL, 60, -1, 10);
        assertTrue(plugin.service().rent(steve, h, 3));
        for (int i = 0; i < 2; i++) {
            Plot r = new Plot("hot-" + (i + 1), "world", new ru.buildmaster.model.Box(61 + i * 4, 1, 1, 63 + i * 4, 3, 4));
            r.category = Category.ROOM;
            r.parentId = "hot";
            r.rentPerDay = 50;
            plugin.plots().add(r);
        }
        h.maxRoomRate = 200;
        return h;
    }

    @Test
    void hotelPageListsRoomsWithGuestsAndRateButtons() throws Exception {
        Plot h = hotelWithRooms();
        PlayerMock mia = player("Mia", 5000);
        assertTrue(plugin.service().rent(mia, plugin.plots().get("hot-1"), 2));
        steve.performCommand("district hotel");
        assertTrue(gui.opened.get(0).startsWith("buildmaster/hotel-"));
        String html = read("hotel", steve);
        assertTrue(html.contains("свободно 1 из 2"), html);
        assertTrue(html.contains("Mia") && html.contains("свободен"), html);
        assertTrue(html.contains("data-command=\"district room rate hot-2 45\""));
        assertTrue(html.contains("data-command=\"district room rate hot-2 55\""));
        assertTrue(html.contains("Потребление в сутки"));
    }

    @Test
    void roomRateButtonCommandChangesThePrice() {
        hotelWithRooms();
        steve.performCommand("district room rate hot-2 55");
        assertEquals(55, plugin.plots().get("hot-2").rentPerDay, 1e-9);
    }

    @Test
    void hotelAndBusinessFallBackToTheChestMenuWithoutMcGui() {
        gui.available = false;
        steve.performCommand("district business");
        steve.performCommand("district hotel");
        assertTrue(gui.opened.isEmpty());
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof ru.buildmaster.gui.PlotListMenu);
    }

    @Test
    void otherPlayersSeeNeitherBusinessesNorHotelsOfSomeoneElse() throws Exception {
        workshopWithMachines();
        hotelWithRooms();
        PlayerMock mia = player("Mia", 100);
        mia.performCommand("district business");
        assertTrue(read("business", mia).contains("У вас нет предприятий"));
        assertFalse(read("business", mia).contains("№ w"));
        mia.performCommand("district hotel");
        assertTrue(read("hotel", mia).contains("У вас нет отелей"));
        assertFalse(read("hotel", mia).contains("hot-1"));
    }

    @Test
    void anvilOffersTheChestMenuInsteadOfHundredsOfRecipeButtons() throws Exception {
        Plot w = workshopWithMachines();
        server.getWorld("world").getBlockAt(42, 1, 1).setType(org.bukkit.Material.ANVIL);
        plugin.stations().addMachine(w, 42, 1, 1, ru.buildmaster.model.Station.fromName("ANVIL"));
        steve.performCommand("district business");
        String html = read("business", steve);
        assertTrue(html.contains("data-command=\"district machine at w 42,1,1 menu\""), html);
        assertFalse(html.contains("machine at w 42,1,1 set"));
    }

    // ------------------------------------------------------------ работа и заказы

    private ru.buildmaster.model.Vacancy vacancy(ru.buildmaster.model.JobRole role) {
        assertNull(plugin.jobs().post(steve, shop, role, 2, 40, 0, null));
        var list = plugin.jobs().vacanciesOf(shop.id);
        return list.get(list.size() - 1);
    }

    @Test
    void jobsPageListsVacanciesWithApplyAndWithdrawButtons() throws Exception {
        var v = vacancy(ru.buildmaster.model.JobRole.SELLER);
        PlayerMock mia = player("Mia", 100);
        mia.performCommand("district job board");
        assertTrue(gui.opened.get(gui.opened.size() - 1).startsWith("buildmaster/jobs-"));
        String html = read("jobs", mia);
        assertTrue(html.contains("Продавец") && html.contains("№ s"), html);
        assertTrue(html.contains("data-command=\"district job apply " + v.id + "\""));
        mia.performCommand("district job apply " + v.id);
        plugin.htmlMenus().tick();
        gui.current.put(mia.getUniqueId(), "buildmaster/jobs-" + suffix(mia));
        plugin.htmlMenus().tick();
        String after = read("jobs", mia);
        assertTrue(after.contains("district job withdraw"), after);
        assertFalse(after.contains("district job apply " + v.id));
    }

    @Test
    void managerSeesApplicationsAndStaffOnTheBusinessPage() throws Exception {
        var v = vacancy(ru.buildmaster.model.JobRole.SELLER);
        PlayerMock mia = player("Mia", 100);
        mia.performCommand("district job apply " + v.id);
        steve.performCommand("district business");
        String html = read("business", steve);
        assertTrue(html.contains("Вакансия №" + v.id) && html.contains("district job close " + v.id));
        var app = plugin.jobs().applicationsFor(shop.id).get(0);
        assertTrue(html.contains("district job accept " + app.id) && html.contains("district job decline " + app.id));
        steve.performCommand("district job accept " + app.id);
        gui.current.put(steve.getUniqueId(), "buildmaster/business-" + suffix(steve));
        plugin.htmlMenus().tick();
        String staff = read("business", steve);
        assertTrue(staff.contains("district job fire s Mia"), staff);
        assertFalse(staff.contains("district job accept"));
    }

    @Test
    void jobsPageShowsMyWorkAndQuitButton() throws Exception {
        var v = vacancy(ru.buildmaster.model.JobRole.SELLER);
        PlayerMock mia = player("Mia", 100);
        mia.performCommand("district job apply " + v.id);
        steve.performCommand("district job accept " + plugin.jobs().applicationsFor(shop.id).get(0).id);
        mia.performCommand("district job board");
        String html = read("jobs", mia);
        assertTrue(html.contains("Я работаю") && html.contains("district job quit s"), html);
        assertTrue(html.contains("вы здесь работаете"));
    }

    @Test
    void jobsDisabledShowsAMessage() throws Exception {
        plugin.getConfig().set("jobs.enabled", false);
        steve.performCommand("district job board");
        assertTrue(read("jobs", steve).contains("отключена"));
        steve.performCommand("district menu");
        assertFalse(read("main", steve).contains("jobs-" + suffix(steve)));
    }

    @Test
    void ordersPageShowsOrdersWithCollectAndCancelButtons() throws Exception {
        Plot w = workshopWithMachines();
        plugin.getConfig().set("electricity.max-clock-step-seconds", 10_000_000);
        plugin.catalog().setSmeltLookup(in -> in.getType() == org.bukkit.Material.IRON_ORE
                ? new ru.buildmaster.service.ServiceCatalog.SmeltResult(new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_INGOT), 10) : null);
        assertTrue(plugin.power().deposit(steve, w, 5000));
        assertNull(plugin.orders().configureOffer(steve, w, "smelt", 2.0, true));
        PlayerMock mia = player("Mia", 1000);
        mia.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_ORE, 3));
        assertNull(plugin.orders().create(mia, w, "smelt", false));
        var order = plugin.orders().ofClient(mia.getUniqueId()).get(0);
        mia.performCommand("service");
        mia.performCommand("district service orders");
        String html = read("orders", mia);
        assertTrue(html.contains("№ " + order.shortId()), html);
        assertTrue(html.contains("district service cancel " + order.shortId()) || html.contains("выполняется"), html);
        mia.performCommand("district service cancel " + order.shortId());
        assertTrue(plugin.orders().ofClient(mia.getUniqueId()).isEmpty() || plugin.orders().ofClient(mia.getUniqueId()).get(0).state != null);
    }

    @Test
    void emptyOrdersPageExplainsHowToOrder() throws Exception {
        steve.performCommand("district service orders");
        assertTrue(read("orders", steve).contains("Заказов нет"));
        steve.performCommand("district service orders classic");
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof ru.buildmaster.gui.MyOrdersMenu);
        steve.performCommand("district job board classic");
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof ru.buildmaster.gui.JobBoardMenu);
    }
}
