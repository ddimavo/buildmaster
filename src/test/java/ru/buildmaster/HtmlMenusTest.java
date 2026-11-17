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
}
