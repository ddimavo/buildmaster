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
}
