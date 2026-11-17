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
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.util.Text;

/** Главное меню: панель игрока со сводкой и разделы района. */
public final class MainMenu extends Menu {

    public MainMenu(IndustryRebornPlugin plugin, Player player) {
        super(plugin, player, "<dark_gray>Район коммерции", 3);
        var id = player.getUniqueId();
        List<Plot> approved = plugin.plots().all().stream()
                .filter(p -> p.approval == Plot.Approval.APPROVED).toList();
        List<Plot> freePlots = approved.stream().filter(p -> p.isFree() && !p.restorePending).toList();
        List<Plot> mine = plugin.plots().all().stream().filter(p -> p.involves(id)).toList();
        int stored = plugin.storage().countItems(id, StorageEntry.State.AVAILABLE);
        int unread = plugin.warnings().unread(id);
        int jobs = plugin.jobs().jobsOf(id).size();
        long now = System.currentTimeMillis();

        // ---- панель игрока ----
        List<String> profile = new ArrayList<>();
        profile.add("<gray>Баланс: <white>" + plugin.economy().format(plugin.economy().balance(player)));
        profile.add("<gray>Участков: <white>" + mine.size());
        Plot soonest = mine.stream().filter(p -> p.status == Plot.Status.RENTED && p.isOwner(id))
                .min(java.util.Comparator.comparingLong(p -> p.rentedUntil)).orElse(null);
        if (soonest != null) {
            long left = soonest.rentedUntil - now;
            profile.add("<gray>Ближайшее окончание аренды: <white>" + Text.esc(soonest.id) + " · " + Text.duration(left));
            profile.add(ru.industryreborn.util.Style.bar((double) left / (plugin.service().maxRentDays()
                    * plugin.service().dayMillis()), 12, left < 86_400_000L ? "red" : "green"));
        }
        if (jobs > 0) {
            profile.add("<gray>Работаете: <white>" + jobs);
        }
        profile.add("<gray>Вещей в хранилище: <white>" + stored);
        profile.add("<gray>Непрочитанных уведомлений: " + (unread > 0 ? "<gold>" + unread : "<white>0"));
        ItemStack head = Text.icon(Material.PLAYER_HEAD, "<gold>" + Text.esc(player.getName()), profile.toArray(new String[0]));
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(player));
        set(4, head);

        // ---- разделы ----
        Map<Category, Integer> byCategory = new EnumMap<>(Category.class);
        for (Plot p : freePlots) {
            byCategory.merge(p.category, 1, Integer::sum);
        }
        List<String> freeLore = new ArrayList<>();
        freeLore.add("<gray>Доступно: <white>" + freePlots.size());
        byCategory.forEach((c, n) -> freeLore.add("<gray>" + c.title() + ": <white>" + n));
        freeLore.add("<yellow>Нажмите: фильтры, сортировка, поиск");
        set(10, Text.icon(Material.LIME_CONCRETE, "<green>Свободные участки", freeLore.toArray(new String[0])),
                e -> new PlotListMenu(plugin, player, new PlotFilter(PlotFilter.Mode.FREE), 0).open());

        long rented = mine.stream().filter(p -> p.isOwner(id) && p.status == Plot.Status.RENTED).count();
        long owned = mine.stream().filter(p -> p.isOwner(id) && p.status == Plot.Status.OWNED).count();
        set(12, Text.icon(Material.EMERALD_BLOCK, "<gold>Мои участки",
                "<gray>Всего: <white>" + mine.size(),
                "<gray>Арендовано: <white>" + rented + " <gray>· куплено: <white>" + owned,
                "<yellow>Нажмите: управление, аренда, настройки"),
                e -> new PlotListMenu(plugin, player, new PlotFilter(PlotFilter.Mode.MINE), 0).open());
        set(14, Text.icon(stored > 0 ? Material.CHEST : Material.BARREL, "<aqua>Хранилище",
                "<gray>Вещей: <white>" + stored,
                "<gray>Хранятся: <white>" + plugin.getConfig().getInt("storage-days", 60) + " суток",
                stored > 0 ? "<yellow>Нажмите, чтобы забрать" : "<gray>Пока пусто"),
                e -> new StorageMenu(plugin, player, 0).open());
        set(16, Text.icon(Material.WRITABLE_BOOK, "<white>Журнал",
                "<gray>История ваших участков, решения", "<gray>администрации и причины",
                "<yellow>Нажмите, чтобы открыть"), e -> new JournalMenu(plugin, player, 0).open());
        if (plugin.jobs().enabled()) {
            set(18, Text.icon(Material.OAK_SIGN, "<green>Доска объявлений",
                    "<gray>Вакансий: <white>" + plugin.jobs().board().size(),
                    "<gray>Вы работаете: <white>" + jobs,
                    "<yellow>Нажмите: найти работу и зарплату"), e -> new JobBoardMenu(plugin, player, 0).open());
        }
        set(20, Text.icon(unread > 0 ? Material.BELL : Material.NOTE_BLOCK, "<gold>Журнал предупреждений",
                "<gray>Непрочитанных: " + (unread > 0 ? "<gold>" + unread : "<white>0"),
                "<gray>Аренда, электричество, отклики, отказы",
                "<yellow>Нажмите, чтобы открыть"), e -> new WarningsMenu(plugin, player, 0).open());
        set(24, Text.icon(Material.GOLD_INGOT, "<gold>Банк", "<gray>Счета предприятий, комиссии, налоги",
                "<yellow>Нажмите, чтобы открыть"), e -> new BankMenu(plugin, player).open());
        int orders = plugin.orders().ofClient(id).size();
        set(26, Text.icon(Material.CHEST, "<aqua>Мои заказы",
                "<gray>Заказов: <white>" + orders,
                "<gray>Заберите готовые или отмените ожидающие",
                "<yellow>Нажмите, чтобы открыть"), e -> new MyOrdersMenu(plugin, player, 0).open());
        if (player.hasPermission("industryreborn.grid")) {
            set(25, Text.icon(Material.LIGHTNING_ROD, "<red>Электросеть", "<gray>Станции, нагрузка, отчёты",
                    "<yellow>Нажмите, чтобы открыть"), e -> new GridMenu(plugin, player, 0).open());
        }
        if (player.hasPermission("industryreborn.admin")) {
            int pending = plugin.moderation().pending().size();
            int arrested = plugin.arrests().players().size();
            set(23, Text.icon(Material.IRON_BARS, "<red>Арестованные вещи",
                    "<gray>Игроков: <white>" + arrested, "<yellow>Нажмите: вернуть, удалить или на аукцион"),
                    e -> new ArrestedMenu(plugin, player, 0).open());
            set(22, Text.icon(Material.ANVIL, "<red>Модерация", "<gray>Ожидают проверки: "
                            + (pending > 0 ? "<gold>" + pending : "<white>0"),
                    "<gray>Названия, описания, участки игроков, голограммы",
                    "<yellow>Нажмите, чтобы открыть"),
                    e -> new ModerationMenu(plugin, player, 0).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
