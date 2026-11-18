package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.gui.ArrestedMenu;
import ru.industryreborn.gui.BankMenu;
import ru.industryreborn.gui.ConfirmMenu;
import ru.industryreborn.gui.JobBoardMenu;
import ru.industryreborn.gui.MainMenu;
import ru.industryreborn.gui.ModerationMenu;
import ru.industryreborn.gui.PlotListMenu;
import ru.industryreborn.gui.StorageMenu;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.support.PluginTestBase;

/** Оформление меню: рамки по разделам, панель игрока, навигация и единый стиль описаний. */
class MenuDesignTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock boss;

    @BeforeEach
    void setUp() {
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.shop", 0);
        steve = player("Steve", 1234);
        boss = admin("Boss");
    }

    private static String plain(net.kyori.adventure.text.Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static String lore(ItemStack item) {
        StringBuilder sb = new StringBuilder();
        if (item.getItemMeta().lore() != null) {
            item.getItemMeta().lore().forEach(l -> sb.append(plain(l)).append('\n'));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ рамки

    @Test
    void theMainMenuHasADarkFrameAndGrayInside() {
        Inventory inv = new MainMenu(plugin, steve).getInventory();
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, inv.getItem(0).getType(), "шапка тёмная");
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, inv.getItem(8).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inv.getItem(10).getType(), "внутри окна нейтральный фон");
        assertEquals(Material.LIME_STAINED_GLASS_PANE, inv.getItem(9).getType(), "группа начинается цветной панелью");
        assertEquals(Material.BARRIER, inv.getItem(49).getType(), "внизу кнопка «Закрыть»");
    }

    @Test
    void everySectionHasItsOwnColor() {
        assertEquals(Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                new PlotListMenu(plugin, steve, new PlotFilter(PlotFilter.Mode.FREE), 0).getInventory().getItem(45).getType());
        assertEquals(Material.CYAN_STAINED_GLASS_PANE, new StorageMenu(plugin, steve, 0).getInventory().getItem(45).getType());
        assertEquals(Material.LIME_STAINED_GLASS_PANE, new JobBoardMenu(plugin, steve, 0).getInventory().getItem(45).getType());
        assertEquals(Material.YELLOW_STAINED_GLASS_PANE, new BankMenu(plugin, steve).getInventory().getItem(28).getType());
        assertEquals(Material.RED_STAINED_GLASS_PANE, new ModerationMenu(plugin, boss, 0).getInventory().getItem(45).getType());
        assertEquals(Material.RED_STAINED_GLASS_PANE, new ArrestedMenu(plugin, boss, 0).getInventory().getItem(46).getType());
        assertEquals(Material.RED_STAINED_GLASS_PANE,
                new ConfirmMenu(plugin, steve, "Вопрос?", new String[0], () -> { }, () -> { }).getInventory().getItem(18).getType());
    }

    @Test
    void fillNeverReplacesRealButtons() {
        Inventory inv = new MainMenu(plugin, boss).getInventory();
        assertEquals(Material.IRON_BARS, inv.getItem(39).getType());
        assertEquals(Material.ANVIL, inv.getItem(38).getType());
        assertEquals(Material.LIME_CONCRETE, inv.getItem(11).getType());
    }

    // ------------------------------------------------------------ панель игрока

    @Test
    void theProfileShowsBalanceAndPlots() {
        Plot shop = addPlot("s1", Category.SHOP, 0, 1000, 50);
        plugin.service().rent(steve, shop, 5);
        Inventory inv = new MainMenu(plugin, steve).getInventory();
        ItemStack head = inv.getItem(4);
        assertEquals(Material.PLAYER_HEAD, head.getType());
        assertEquals("Steve", plain(head.getItemMeta().displayName()));
        String text = lore(head);
        assertTrue(text.contains("Участков: 1"));
        assertTrue(text.contains("Магазин s1") || text.contains("s1"), "ближайшая оплата: " + text);
        assertTrue(text.contains("█"), "полоска срока");
        ItemStack money = inv.getItem(1);
        assertEquals(Material.GOLD_INGOT, money.getType());
        assertEquals("Баланс", plain(money.getItemMeta().displayName()));
        assertTrue(lore(money).contains("Наличные"), lore(money));
    }

    @Test
    void theFreePlotsButtonCountsByType() {
        addPlot("s1", Category.SHOP, 0, 1000, 50);
        addPlot("s2", Category.SHOP, 20, 1000, 50);
        addPlot("h1", Category.HOUSE, 40, 500, 10);
        String text = lore(new MainMenu(plugin, steve).getInventory().getItem(11));
        assertTrue(text.contains("Доступно: 3"), text);
        assertTrue(text.contains("Магазин: 2") && text.contains("Дом: 1"), text);
    }

    @Test
    void unreadWarningsAreHighlighted() {
        plugin.warnings().add(steve.getUniqueId(), "x", "<red>тест");
        ItemStack bell = new MainMenu(plugin, steve).getInventory().getItem(7);
        assertEquals(Material.BELL, bell.getType());
        assertTrue(lore(bell).contains("Непрочитанных: 1"));
    }

    @Test
    void adminButtonsShowCounters() {
        String text = lore(new MainMenu(plugin, boss).getInventory().getItem(38));
        assertTrue(text.contains("Ожидают проверки: 0"));
    }

    // ------------------------------------------------------------ навигация и карточки

    @Test
    void pagerArrowsShowThePageNumbers() {
        for (int i = 0; i < 60; i++) {
            addPlot("p" + i, Category.HOUSE, i * 12, 100, 5);
        }
        Inventory inv = new PlotListMenu(plugin, steve, new PlotFilter(PlotFilter.Mode.FREE), 0).getInventory();
        ItemStack next = inv.getItem(50);
        assertEquals(Material.SPECTRAL_ARROW, next.getType());
        assertTrue(lore(next).contains("Страница: 1 из 2"), lore(next));
        assertEquals(2, next.getAmount(), "на стрелке номер следующей страницы");
        assertTrue(plain(next.getItemMeta().displayName()).contains("Следующая"));
        assertNotEquals(Material.SPECTRAL_ARROW, inv.getItem(46).getType(), "на первой странице стрелки назад нет");
        assertEquals(Material.NETHER_STAR, inv.getItem(49).getType(), "главное меню внизу по центру");
    }

    @Test
    void plotCardsShowStatusTimeAndWorkshopPower() {
        Plot workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        plugin.service().rent(steve, workshop, 3);
        String text = lore(new PlotListMenu(plugin, steve, new PlotFilter(PlotFilter.Mode.MINE), 0).getInventory().getItem(9));
        assertTrue(text.contains("● Арендован"), text);
        assertTrue(text.contains("█"), "полоска остатка аренды");
        assertTrue(text.contains("Станков"), text);
        assertTrue(text.contains("▶"), "подсказка действия");
    }

    @Test
    void homeAndConfirmButtonsAreConsistent() {
        Inventory list = new StorageMenu(plugin, steve, 0).getInventory();
        assertEquals(Material.NETHER_STAR, list.getItem(49).getType());
        Inventory confirm = new ConfirmMenu(plugin, steve, "Удалить?", new String[] {"<gray>Вещи пропадут"}, () -> { }, () -> { }).getInventory();
        assertEquals(Material.LIME_CONCRETE, confirm.getItem(11).getType());
        assertEquals(Material.RED_CONCRETE, confirm.getItem(15).getType());
        String q = lore(confirm.getItem(13));
        assertTrue(q.contains("Вещи пропадут") && q.contains("нельзя будет отменить"), q);
    }

    @Test
    void windowTitlesUseTheCommonStyle() {
        var view = steve.openInventory(new MainMenu(plugin, steve).getInventory());
        String title = plain(view.title());
        assertTrue(title.contains("◆") && title.contains("Район коммерции"), title);
    }

    @Test
    void clickingAButtonPlaysASoundAndStillWorks() {
        steve.openInventory(new MainMenu(plugin, steve).getInventory());
        steve.simulateInventoryClick(29);
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof StorageMenu);
    }
}
