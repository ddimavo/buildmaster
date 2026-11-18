package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.html.HtmlMenus;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.util.Style;
import ru.industryreborn.util.Text;

/**
 * Главное меню: шапка (баланс, профиль, уведомления), разделы по группам (участки, дела, личное, администрация) и нижняя панель.
 * Слоты: шапка 1, 4, 7; группы начинаются в колонке 0 цветной панелью-заголовком, кнопки со второй колонки; низ 45, 49, 53.
 */
public final class MainMenu extends Menu {

    public MainMenu(IndustryRebornPlugin plugin, Player player) {
        super(plugin, player, "<dark_gray>Район коммерции", 6);
        var id = player.getUniqueId();
        boolean admin = player.hasPermission("industryreborn.admin");
        List<Plot> approved = plugin.plots().all().stream()
                .filter(p -> p.approval == Plot.Approval.APPROVED).toList();
        List<Plot> freePlots = approved.stream().filter(p -> p.isFree() && !p.restorePending).toList();
        List<Plot> mine = plugin.plots().all().stream().filter(p -> p.involves(id)).toList();
        int stored = plugin.storage().countItems(id, StorageEntry.State.AVAILABLE);
        int unread = plugin.warnings().unread(id);
        int jobs = plugin.jobs().jobsOf(id).size();
        long now = System.currentTimeMillis();

        // ---- шапка ----
        double balance = plugin.economy().balance(player);
        double accounts = mine.stream().filter(p -> p.isOwner(id)).mapToDouble(p -> plugin.power().balance(p)).sum();
        List<String> money = new ArrayList<>();
        money.add("<gray>Наличные: <white>" + plugin.economy().format(balance));
        if (accounts > 0) {
            money.add("<gray>На счетах предприятий: <white>" + plugin.economy().format(accounts));
        }
        money.add("<yellow>Нажмите: банк, счета, выписка");
        set(1, Text.icon(Material.GOLD_INGOT, "<gold>Баланс", money.toArray(new String[0])), e -> new BankMenu(plugin, player).open());

        List<String> profile = new ArrayList<>();
        profile.add("<gray>Участков: <white>" + mine.size());
        Plot soonest = mine.stream().filter(p -> p.status == Plot.Status.RENTED && p.isOwner(id))
                .min(java.util.Comparator.comparingLong(p -> p.rentedUntil)).orElse(null);
        if (soonest != null) {
            long left = soonest.rentedUntil - now;
            profile.add("<gray>Ближайшая оплата: <white>" + Text.esc(soonest.displayName()) + " · " + Text.duration(left));
            profile.add(Style.bar((double) left / (plugin.service().maxRentDays() * plugin.service().dayMillis()), 12, left < 86_400_000L ? "red" : "green"));
        }
        if (jobs > 0) {
            profile.add("<gray>Работаете: <white>" + jobs);
        }
        ItemStack head = Text.icon(Material.PLAYER_HEAD, "<gold>" + Text.esc(player.getName()), profile.toArray(new String[0]));
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(player));
        set(4, head);

        ItemStack bell = Text.icon(unread > 0 ? Material.BELL : Material.NOTE_BLOCK, "<gold>Уведомления",
                "<gray>Непрочитанных: " + (unread > 0 ? "<gold>" + unread : "<white>0"),
                "<gray>Аренда, электричество, отклики, отказы",
                "<yellow>Нажмите, чтобы открыть");
        bell.setAmount(Math.max(1, Math.min(64, unread)));
        set(7, bell, e -> new WarningsMenu(plugin, player, 0).open());

        // ---- участки ----
        set(9, Ui.section("Участки", Material.LIME_STAINED_GLASS_PANE));
        Map<Category, Integer> byCategory = new EnumMap<>(Category.class);
        for (Plot p : freePlots) {
            byCategory.merge(p.category, 1, Integer::sum);
        }
        List<String> freeLore = new ArrayList<>();
        freeLore.add("<gray>Доступно: <white>" + freePlots.size());
        byCategory.forEach((c, n) -> freeLore.add("<gray>" + c.title() + ": <white>" + n));
        freeLore.add("<yellow>Нажмите: фильтры, сортировка, поиск");
        set(11, Text.icon(Material.LIME_CONCRETE, "<green>Свободные участки", freeLore.toArray(new String[0])),
                e -> new PlotListMenu(plugin, player, new PlotFilter(PlotFilter.Mode.FREE), 0).open());

        long rented = mine.stream().filter(p -> p.isOwner(id) && p.status == Plot.Status.RENTED).count();
        long owned = mine.stream().filter(p -> p.isOwner(id) && p.status == Plot.Status.OWNED).count();
        set(12, Text.icon(Material.EMERALD_BLOCK, "<gold>Мои участки",
                "<gray>Всего: <white>" + mine.size(),
                "<gray>Аренда: <white>" + rented + " <gray>· куплено: <white>" + owned,
                "<yellow>Нажмите: управление, аренда, настройки"),
                e -> new PlotListMenu(plugin, player, new PlotFilter(PlotFilter.Mode.MINE), 0).open());
        if (plugin.lots().enabled()) {
            set(13, Text.icon(Material.GOLD_BLOCK, "<gold>Аукционы участков",
                    "<gray>Торгов сейчас: <white>" + plugin.lots().all().size(),
                    "<yellow>Нажмите: лоты и ставки"), e -> {
                        player.closeInventory();
                        player.performCommand("district lot menu");
                    });
        }
        if (plugin.buildings().playerBuildEnabled() && (!plugin.buildings().buildablePlots(player).isEmpty() || admin)) {
            set(14, Text.icon(Material.BRICKS, "<aqua>Заказать здание",
                    "<gray>Цена блока: <white>" + plugin.economy().format(plugin.buildings().pricePerBlock(null)),
                    "<gray>Здание ставится на пустой свой участок",
                    "<yellow>Нажмите: выбрать участок и здание"), e -> {
                        player.closeInventory();
                        player.performCommand("district build menu");
                    });
        }

        // ---- дела ----
        set(18, Ui.section("Дела", Material.YELLOW_STAINED_GLASS_PANE));
        int slot = 20;
        if (plugin.jobs().enabled()) {
            set(slot++, Text.icon(Material.OAK_SIGN, "<green>Доска объявлений",
                    "<gray>Вакансий: <white>" + plugin.jobs().board().size(),
                    "<gray>Вы работаете: <white>" + jobs,
                    "<yellow>Нажмите: найти работу и зарплату"), e -> new JobBoardMenu(plugin, player, 0).open());
        }
        int orders = plugin.orders().ofClient(id).size();
        set(slot++, Text.icon(Material.CHEST, "<aqua>Мои заказы",
                "<gray>Заказов: <white>" + orders,
                "<gray>Заберите готовые или отмените ожидающие",
                "<yellow>Нажмите, чтобы открыть"), e -> new MyOrdersMenu(plugin, player, 0).open());
        if (jobs > 0) {
            set(slot++, Text.icon(Material.VILLAGER_SPAWN_EGG, "<green>Моя работа",
                    "<gray>Мест работы: <white>" + jobs, "<yellow>Нажмите, чтобы открыть"), e -> new MyJobsMenu(plugin, player).open());
        }

        // ---- личное ----
        set(27, Ui.section("Личное", Material.CYAN_STAINED_GLASS_PANE));
        set(29, Text.icon(stored > 0 ? Material.CHEST : Material.BARREL, "<aqua>Хранилище",
                "<gray>Вещей: <white>" + stored,
                "<gray>Хранятся: <white>" + plugin.getConfig().getInt("storage-days", 60) + " суток",
                stored > 0 ? "<yellow>Нажмите, чтобы забрать" : "<gray>Пока пусто"),
                e -> new StorageMenu(plugin, player, 0).open());
        set(30, Text.icon(Material.WRITABLE_BOOK, "<white>Журнал",
                "<gray>История ваших участков, решения", "<gray>администрации и причины",
                "<yellow>Нажмите, чтобы открыть"), e -> new JournalMenu(plugin, player, 0).open());
        set(31, Text.icon(Material.GOLD_NUGGET, "<gold>Банк", "<gray>Счета предприятий, комиссии, налоги",
                "<yellow>Нажмите, чтобы открыть"), e -> new BankMenu(plugin, player).open());

        // ---- администрация ----
        if (admin || player.hasPermission("industryreborn.grid") || player.hasPermission("industryreborn.bank")) {
            set(36, Ui.section("Администрация", Material.RED_STAINED_GLASS_PANE));
            int a = 38;
            if (admin) {
                int pending = plugin.moderation().pending().size();
                ItemStack mod = Text.icon(Material.ANVIL, "<red>Модерация", "<gray>Ожидают проверки: "
                                + (pending > 0 ? "<gold>" + pending : "<white>0"),
                        "<gray>Названия, описания, участки игроков, голограммы",
                        "<yellow>Нажмите, чтобы открыть");
                mod.setAmount(Math.max(1, Math.min(64, pending)));
                set(a++, mod, e -> new ModerationMenu(plugin, player, 0).open());
                int arrested = plugin.arrests().players().size();
                set(a++, Text.icon(Material.IRON_BARS, "<red>Арестованные вещи",
                        "<gray>Игроков: <white>" + arrested, "<yellow>Нажмите: вернуть, удалить или на аукцион"),
                        e -> new ArrestedMenu(plugin, player, 0).open());
            }
            if (player.hasPermission("industryreborn.grid")) {
                set(a++, Text.icon(Material.LIGHTNING_ROD, "<red>Электросеть", "<gray>Станции, нагрузка, отчёты",
                        "<yellow>Нажмите, чтобы открыть"), e -> new GridMenu(plugin, player, 0).open());
            }
            if (player.hasPermission("industryreborn.bank")) {
                set(a++, Text.icon(Material.EMERALD, "<green>Казна", "<gray>Баланс: <white>" + plugin.economy().format(plugin.treasury().balance()),
                        "<yellow>Нажмите: субсидии, призы, отчёт"), e -> new TreasuryMenu(plugin, player).open());
            }
            if (admin) {
                set(a++, Text.icon(Material.LEVER, "<red>Функции плагина", "<gray>Включить и выключить части плагина",
                        "<yellow>Нажмите, чтобы открыть"), e -> {
                            player.closeInventory();
                            if (!plugin.htmlMenus().open(player, HtmlMenus.Kind.MODULES)) {
                                player.performCommand("district module");
                            }
                        });
            }
        }

        // ---- нижняя панель ----
        set(45, Text.icon(Material.PAPER, "<white>Справка", "<gray>Команды и как пользоваться районом",
                "<yellow>Нажмите, чтобы открыть"), e -> {
                    player.closeInventory();
                    player.performCommand("district help");
                });
        set(49, Ui.close(), e -> player.closeInventory());
        var menus = plugin.htmlMenus();
        boolean html = menus.backend().available();
        set(53, Text.icon(Material.COMPARATOR, "<white>Вид меню",
                "<gray>Сейчас: <white>" + (menus.modeOf(player) == HtmlMenus.Mode.CHEST ? "сундуки" : menus.modeOf(player) == HtmlMenus.Mode.HTML ? "HTML" : "авто"),
                html ? "<yellow>Нажмите: переключить сундуки и HTML" : "<gray>HTML-меню недоступны: нет MC GUI"), e -> {
                    if (!html) {
                        return;
                    }
                    menus.setMode(player, menus.modeOf(player) == HtmlMenus.Mode.CHEST ? HtmlMenus.Mode.HTML : HtmlMenus.Mode.CHEST);
                    new MainMenu(plugin, player).open();
                });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
