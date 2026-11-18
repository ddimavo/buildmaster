package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.gui.DialogService;
import ru.industryreborn.gui.MainMenu;
import ru.industryreborn.gui.PlotListMenu;
import ru.industryreborn.gui.PlotMenu;
import ru.industryreborn.html.GuiBackend;
import ru.industryreborn.html.HtmlMenus;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.support.PluginTestBase;

/** Новые меню-сундуки: группы, нижняя панель, окна ввода (диалоги) и запасной ввод в чат. */
class MenuRedesignTest extends PluginTestBase {

    /** Окно ввода-заглушка: запоминает, что показали, и отдаёт значения по команде теста. */
    private static final class FakeDialogs implements DialogService.Backend {
        final List<DialogService.Spec> shown = new ArrayList<>();
        Consumer<Map<String, Object>> submit;
        Runnable cancel;
        boolean fail;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void show(Player player, DialogService.Spec spec, Consumer<Map<String, Object>> submit, Runnable cancel) {
            if (fail) {
                throw new IllegalStateException("нет диалогов");
            }
            shown.add(spec);
            this.submit = submit;
            this.cancel = cancel;
        }

        DialogService.Spec last() {
            return shown.get(shown.size() - 1);
        }
    }

    private FakeDialogs dialogs;
    private PlayerMock steve;
    private PlayerMock boss;
    private Plot shop;

    private static String plain(net.kyori.adventure.text.Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private Inventory open() {
        return steve.getOpenInventory().getTopInventory();
    }

    private void tick() {
        server.getScheduler().performTicks(2);
    }

    @BeforeEach
    void setUp() {
        dialogs = new FakeDialogs();
        plugin.dialogs().setBackend(dialogs);
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.shop", 0);
        steve = player("Steve", 100_000);
        boss = admin("Boss");
        shop = addPlot("s1", Category.SHOP, 0, 1000, 50);
        plugin.service().rent(steve, shop, 5);
    }

    // ------------------------------------------------------------ каркас

    @Test
    void sectionsAreHeaderedAndTheBottomBarIsTheSame() {
        Inventory inv = new MainMenu(plugin, steve).getInventory();
        for (int slot : new int[] {9, 18, 27}) {
            assertTrue(inv.getItem(slot).getType().name().endsWith("_STAINED_GLASS_PANE"), "панель-заголовок в слоте " + slot);
        }
        assertEquals("УЧАСТКИ", plain(inv.getItem(9).getItemMeta().displayName()));
        assertEquals("ДЕЛА", plain(inv.getItem(18).getItemMeta().displayName()));
        assertEquals("ЛИЧНОЕ", plain(inv.getItem(27).getItemMeta().displayName()));
        assertNotEquals(Material.RED_STAINED_GLASS_PANE, inv.getItem(36).getType(), "администрация - только администратору");
        Inventory adm = new MainMenu(plugin, boss).getInventory();
        assertEquals("АДМИНИСТРАЦИЯ", plain(adm.getItem(36).getItemMeta().displayName()));
        assertEquals(Material.PAPER, inv.getItem(45).getType());
        assertEquals(Material.BARRIER, inv.getItem(49).getType());
        assertEquals(Material.COMPARATOR, inv.getItem(53).getType());
    }

    @Test
    void titlesShowThePath() {
        steve.openInventory(new PlotListMenu(plugin, steve, new PlotFilter(PlotFilter.Mode.MINE), 0).getInventory());
        String title = plain(steve.getOpenInventory().title());
        assertTrue(title.contains("Район") && title.contains("›") && title.contains("Мои участки"), title);
        new PlotMenu(plugin, steve, shop, new PlotFilter(PlotFilter.Mode.MINE)).open();
        title = plain(steve.getOpenInventory().title());
        assertTrue(title.contains("Район") && title.contains("Участки") && title.contains("s1"), title);
    }

    @Test
    void theMainMenuOffersAuctionsAndBuildingsWhenAvailable() {
        steve.openInventory(new MainMenu(plugin, steve).getInventory());
        assertEquals(Material.GOLD_BLOCK, open().getItem(13).getType());
        assertTrue(plain(open().getItem(13).getItemMeta().displayName()).contains("Аукционы"));
        assertEquals(Material.BRICKS, open().getItem(14).getType(), "у игрока есть свой участок для здания");
        plugin.modules().set(ru.industryreborn.service.Module.PLOT_AUCTION, false, "test");
        assertNotEquals(Material.GOLD_BLOCK, new MainMenu(plugin, steve).getInventory().getItem(13).getType());
        plugin.getConfig().set("auto-build.player-build", false);
        assertNotEquals(Material.BRICKS, new MainMenu(plugin, steve).getInventory().getItem(14).getType());
    }

    @Test
    void theViewModeButtonSwitchesBetweenChestsAndHtml() {
        // без MC GUI кнопка ничего не переключает
        steve.openInventory(new MainMenu(plugin, steve).getInventory());
        assertTrue(plain(open().getItem(53).getItemMeta().displayName()).contains("Вид меню"));
        GuiBackend gui = new GuiBackend() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public java.io.File pagesDir() {
                return null;
            }

            @Override
            public boolean exists(String page) {
                return false;
            }

            @Override
            public boolean open(Player player, String page) {
                return false;
            }

            @Override
            public void close(Player player) { }

            @Override
            public String pageOf(Player player) {
                return null;
            }
        };
        plugin.htmlMenus().setBackend(gui);
        steve.openInventory(new MainMenu(plugin, steve).getInventory());
        assertNotEquals(HtmlMenus.Mode.CHEST, plugin.htmlMenus().modeOf(steve));
        steve.simulateInventoryClick(53);
        assertEquals(HtmlMenus.Mode.CHEST, plugin.htmlMenus().modeOf(steve));
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof MainMenu);
        steve.simulateInventoryClick(53);
        assertEquals(HtmlMenus.Mode.HTML, plugin.htmlMenus().modeOf(steve));
    }

    // ------------------------------------------------------------ список

    @Test
    void listCardsHaveStatusDotsAndRightClickExtends() {
        steve.openInventory(new PlotListMenu(plugin, steve, new PlotFilter(PlotFilter.Mode.MINE), 0).getInventory());
        ItemStack card = open().getItem(9);
        assertEquals(Material.EMERALD, card.getType(), "иконка типа участка");
        String name = plain(card.getItemMeta().displayName());
        assertTrue(name.startsWith("● ") && name.contains("s1"), name);
        long before = shop.rentedUntil;
        double money = economy.getBalance(steve);
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.RIGHT, 9);
        DialogService.Spec spec = dialogs.last();
        assertEquals("Продлить аренду", spec.title());
        var field = spec.fields().get(0);
        assertEquals(DialogService.Kind.NUMBER, field.kind());
        assertEquals(1, field.min(), 1e-9);
        assertTrue(field.max() >= 2, "ползунок до допустимого срока");
        dialogs.submit.accept(Map.of("value", 3.0));
        tick();
        assertEquals(before + 3 * plugin.service().dayMillis(), shop.rentedUntil);
        assertEquals(money - 3 * 50, economy.getBalance(steve), 1e-9);
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof PlotListMenu, "после окна список открывается снова");
    }

    @Test
    void leftClickOpensTheCard() {
        steve.openInventory(new PlotListMenu(plugin, steve, new PlotFilter(PlotFilter.Mode.MINE), 0).getInventory());
        steve.simulateInventoryClick(9);
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof PlotMenu);
    }

    @Test
    void searchUsesADialog() {
        addPlot("h1", Category.HOUSE, 40, 100, 5);
        steve.openInventory(new PlotListMenu(plugin, steve, new PlotFilter(PlotFilter.Mode.FREE), 0).getInventory());
        steve.simulateInventoryClick(4);
        assertEquals("Поиск участка", dialogs.last().title());
        dialogs.submit.accept(Map.of("value", "h1"));
        tick();
        Inventory inv = open();
        assertTrue(plain(inv.getItem(9).getItemMeta().displayName()).contains("h1"));
        assertTrue(inv.getItem(10).getType().name().endsWith("_STAINED_GLASS_PANE"), "найден один участок");
    }

    @Test
    void pagingUsesTheBottomBar() {
        for (int i = 0; i < 40; i++) {
            addPlot("p" + i, Category.HOUSE, 100 + i * 12, 100, 5);
        }
        steve.openInventory(new PlotListMenu(plugin, steve, new PlotFilter(PlotFilter.Mode.FREE), 0).getInventory());
        assertEquals(Material.SPECTRAL_ARROW, open().getItem(50).getType());
        steve.simulateInventoryClick(50);
        assertEquals(Material.SPECTRAL_ARROW, open().getItem(46).getType(), "на второй странице есть «назад»");
        assertNotEquals(Material.SPECTRAL_ARROW, open().getItem(50).getType());
        assertEquals(Material.PAPER, open().getItem(48).getType());
        assertTrue(plain(open().getItem(48).getItemMeta().displayName()).contains("2 из 2"));
    }

    // ------------------------------------------------------------ карточка

    private PlotMenu card(Player p, Plot plot) {
        PlotMenu menu = new PlotMenu(plugin, p, plot, new PlotFilter(PlotFilter.Mode.MINE));
        menu.open();
        return menu;
    }

    @Test
    void ownersCardIsGroupedLikeTheMockup() {
        card(steve, shop);
        Inventory inv = open();
        assertEquals("АРЕНДА", plain(inv.getItem(9).getItemMeta().displayName()));
        assertEquals("ПРЕДПРИЯТИЕ", plain(inv.getItem(18).getItemMeta().displayName()));
        assertEquals("ВИД И ДОСТУП", plain(inv.getItem(27).getItemMeta().displayName()));
        assertEquals("ОПАСНАЯ ЗОНА", plain(inv.getItem(36).getItemMeta().displayName()));
        assertEquals(Material.CLOCK, inv.getItem(11).getType());
        assertEquals(Material.DIAMOND, inv.getItem(15).getType(), "другой срок");
        assertEquals(Material.GOLD_NUGGET, inv.getItem(20).getType(), "счёт предприятия");
        assertEquals(Material.NAME_TAG, inv.getItem(29).getType());
        assertEquals(Material.TNT, inv.getItem(40).getType(), "отказ - в опасной зоне");
        assertEquals(Material.ARROW, inv.getItem(45).getType());
        assertEquals(Material.ENDER_PEARL, inv.getItem(47).getType());
        assertEquals(Material.NETHER_STAR, inv.getItem(49).getType());
        assertEquals(Material.BARRIER, inv.getItem(53).getType());
        assertEquals(Material.EMERALD, inv.getItem(4).getType());
        assertTrue(plain(inv.getItem(4).getItemMeta().displayName()).startsWith("● "));
    }

    @Test
    void backButtonLeadsToTheListAndHomeToTheMainMenu() {
        card(steve, shop);
        steve.simulateInventoryClick(45);
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof PlotListMenu);
        card(steve, shop);
        steve.simulateInventoryClick(49);
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof MainMenu);
    }

    @Test
    void renamingGoesThroughADialog() {
        plugin.getConfig().set("moderation.max-name-length", 20);
        card(steve, shop);
        steve.simulateInventoryClick(29);
        DialogService.Spec spec = dialogs.last();
        assertEquals("Название участка", spec.title());
        assertEquals(DialogService.Kind.TEXT, spec.fields().get(0).kind());
        assertEquals(20, spec.fields().get(0).maxLength());
        dialogs.submit.accept(Map.of("value", "Булочная"));
        tick();
        assertEquals(1, plugin.moderation().pending().size());
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof PlotMenu, "карточка открывается снова");
    }

    @Test
    void depositAndWithdrawAskForAnAmount() {
        card(steve, shop);
        double cash = economy.getBalance(steve);
        steve.simulateInventoryClick(20);
        assertEquals("Пополнить счёт", dialogs.last().title());
        dialogs.submit.accept(Map.of("value", "500"));
        tick();
        assertEquals(500, plugin.power().balance(shop), 1e-9);
        assertEquals(cash - 500, economy.getBalance(steve), 1e-9);
        steve.simulateInventoryClick(steve.getOpenInventory(), ClickType.RIGHT, 20);
        assertEquals("Снять со счёта", dialogs.last().title());
        dialogs.submit.accept(Map.of("value", "200,5"));
        tick();
        assertEquals(299.5, plugin.power().balance(shop), 1e-9, "запятая тоже годится");
        steve.simulateInventoryClick(20);
        dialogs.submit.accept(Map.of("value", "abc"));
        tick();
        assertEquals(299.5, plugin.power().balance(shop), 1e-9, "не число - ничего не меняется");
    }

    @Test
    void customTermOpensASliderUpToTheAllowedDays() {
        card(steve, shop);
        steve.simulateInventoryClick(15);
        var f = dialogs.last().fields().get(0);
        int allowed = ru.industryreborn.service.RentRules.maxExtendDays(System.currentTimeMillis(), shop.rentedUntil, plugin.service().dayMillis(),
                plugin.service().maxRentDays());
        assertEquals(allowed, f.max(), 1e-9);
        assertEquals(Math.min(7, allowed), Double.parseDouble(f.initial()), 1e-9);
        assertTrue(dialogs.last().body().stream().anyMatch(l -> l.contains("за сутки")));
    }

    @Test
    void adminDecisionsNeedAReasonFromTheDialog() {
        new PlotMenu(plugin, boss, shop, new PlotFilter(PlotFilter.Mode.MINE)).open();
        Inventory inv = boss.getOpenInventory().getTopInventory();
        assertEquals("АДМИНИСТРАЦИЯ", plain(inv.getItem(36).getItemMeta().displayName()));
        assertEquals(Material.TNT, inv.getItem(38).getType());
        assertEquals(Material.IRON_DOOR, inv.getItem(39).getType());
        assertEquals(Material.IRON_BARS, inv.getItem(41).getType());
        boss.simulateInventoryClick(39);
        assertTrue(dialogs.last().title().contains("Выселение"));
        dialogs.submit.accept(Map.of("value", "  "));
        tick();
        assertTrue(shop.isOwner(steve.getUniqueId()), "пустая причина - ничего не происходит");
        new PlotMenu(plugin, boss, shop, null).open();
        boss.simulateInventoryClick(39);
        dialogs.submit.accept(Map.of("value", "нарушение правил"));
        tick();
        assertTrue(shop.isFree() || shop.restorePending, "участок освобождён");
        assertTrue(plugin.journal().forPlot("s1").stream().anyMatch(e -> e.action() == Journal.Action.EVICTED && e.reason().contains("нарушение")));
    }

    @Test
    void freePlotsHaveTheirOwnGroup() {
        Plot free = addPlot("f1", Category.SHOP, 40, 700, 30);
        new PlotMenu(plugin, steve, free, new PlotFilter(PlotFilter.Mode.FREE)).open();
        Inventory inv = open();
        assertEquals("ПОКУПКА И АРЕНДА", plain(inv.getItem(9).getItemMeta().displayName()));
        assertEquals(Material.GOLD_INGOT, inv.getItem(11).getType());
        assertEquals(Material.CLOCK, inv.getItem(13).getType());
        steve.simulateInventoryClick(11);
        assertTrue(free.isOwner(steve.getUniqueId()));
    }

    // ------------------------------------------------------------ окна ввода и запасной вариант

    @Test
    void withoutDialogsTheFirstFieldIsAskedInChat() {
        plugin.dialogs().setBackend(null);
        assertFalse(plugin.dialogs().available());
        List<String> got = new ArrayList<>();
        plugin.dialogs().text(steve, "Название", "Название", "", 20, got::add);
        assertTrue(plugin.chatPrompt().isWaiting(steve));
        plugin.chatPrompt().deliver(steve, "Лавка");
        tick();
        assertEquals(List.of("Лавка"), got);
        plugin.dialogs().text(steve, "Название", "Название", "", 20, got::add);
        plugin.chatPrompt().deliver(steve, "отмена");
        tick();
        assertEquals(1, got.size(), "«отмена» ничего не вызывает");
    }

    @Test
    void chatFallbackClampsNumbersAndRejectsGarbage() {
        plugin.dialogs().setBackend(null);
        List<Double> got = new ArrayList<>();
        boolean[] cancelled = {false};
        plugin.dialogs().number(steve, "Срок", List.of(), "Дней", 3, 1, 10, 1, got::add, () -> cancelled[0] = true);
        plugin.chatPrompt().deliver(steve, "99");
        tick();
        assertEquals(List.of(10.0), got, "значение приводится к пределам");
        plugin.dialogs().number(steve, "Срок", List.of(), "Дней", 3, 1, 10, 1, got::add, () -> cancelled[0] = true);
        plugin.chatPrompt().deliver(steve, "много");
        tick();
        assertEquals(1, got.size());
        assertTrue(cancelled[0]);
    }

    @Test
    void dialogsCanBeSwitchedOffAndBrokenBackendsFallBack() {
        plugin.getConfig().set("gui.dialogs", "off");
        assertFalse(plugin.dialogs().available());
        List<String> got = new ArrayList<>();
        plugin.dialogs().text(steve, "Название", "Название", "", 20, got::add);
        assertTrue(dialogs.shown.isEmpty(), "при gui.dialogs: off окно не показывается");
        assertTrue(plugin.chatPrompt().isWaiting(steve));
        plugin.chatPrompt().deliver(steve, "а");
        plugin.getConfig().set("gui.dialogs", "auto");
        dialogs.fail = true;
        plugin.dialogs().text(steve, "Название", "Название", "", 20, got::add);
        assertTrue(plugin.chatPrompt().isWaiting(steve), "сбой показа окна - ввод в чате");
        plugin.chatPrompt().deliver(steve, "б");
        tick();
        assertEquals(List.of("а", "б"), got);
    }

    @Test
    void amountDialogRejectsNonPositiveValues() {
        List<Double> got = new ArrayList<>();
        plugin.dialogs().amount(steve, "Сумма", List.of("<gray>тест"), "Сумма", got::add, null);
        dialogs.submit.accept(Map.of("value", "-5"));
        dialogs.submit.accept(Map.of("value", "0"));
        dialogs.submit.accept(Map.of("value", "Infinity"));
        assertTrue(got.isEmpty());
        dialogs.submit.accept(Map.of("value", "12.5"));
        assertEquals(List.of(12.5), got);
        assertEquals(List.of("<gray>тест"), dialogs.last().body());
    }

    @Test
    void theDialogClosesTheInventoryBeforeShowing() {
        steve.openInventory(new MainMenu(plugin, steve).getInventory());
        plugin.dialogs().text(steve, "Название", "Название", "", 20, s -> { });
        var top = steve.getOpenInventory().getTopInventory();
        assertFalse(top != null && top.getHolder() instanceof MainMenu, "перед окном ввода меню закрывается");
    }

    @Test
    void everyOldChatPromptBecomesADialogWhenAvailable() {
        List<String> got = new ArrayList<>();
        plugin.chatPrompt().ask(steve, "Введите сумму субсидии:", got::add);
        assertEquals(1, dialogs.shown.size());
        assertEquals("Введите сумму субсидии", dialogs.last().title(), "заголовок из подсказки без двоеточия");
        assertFalse(plugin.chatPrompt().isWaiting(steve), "в чат ничего не ждём");
        dialogs.submit.accept(Map.of("value", "1500"));
        tick();
        assertEquals(List.of("1500"), got);
        plugin.chatPrompt().ask(steve, "Введите причину:", got::add);
        dialogs.cancel.run();
        tick();
        assertEquals("отмена", got.get(1), "кнопка «Отмена» отдаёт слово «отмена», как в чате");
        plugin.dialogs().setBackend(null);
        plugin.chatPrompt().ask(steve, "Введите ник:", got::add);
        assertTrue(plugin.chatPrompt().isWaiting(steve), "без окон - чат");
    }
}
