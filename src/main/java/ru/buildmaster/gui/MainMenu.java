package ru.buildmaster.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.StorageEntry;
import ru.buildmaster.service.PlotFilter;
import ru.buildmaster.util.Text;

public final class MainMenu extends Menu {

    public MainMenu(BuildMasterPlugin plugin, Player player) {
        super(plugin, player, "<dark_gray>Район коммерции", 3);
        int free = (int) plugin.plots().all().stream().filter(p -> p.isFree() && !p.restorePending).count();
        int mine = (int) plugin.plots().all().stream().filter(p -> p.involves(player.getUniqueId())).count();
        int stored = plugin.storage().countItems(player.getUniqueId(), StorageEntry.State.AVAILABLE);

        set(10, Text.icon(Material.LIME_CONCRETE, "<green>Свободные участки",
                "<gray>Доступно: <white>" + free, "<yellow>Фильтры, сортировка, поиск"),
                e -> new PlotListMenu(plugin, player, new PlotFilter(PlotFilter.Mode.FREE), 0).open());
        set(12, Text.icon(Material.EMERALD_BLOCK, "<gold>Мои участки",
                "<gray>У вас: <white>" + mine, "<yellow>Управление, аренда, настройки"),
                e -> new PlotListMenu(plugin, player, new PlotFilter(PlotFilter.Mode.MINE), 0).open());
        set(14, Text.icon(stored > 0 ? Material.CHEST : Material.BARREL, "<aqua>Хранилище",
                "<gray>Вещей: <white>" + stored,
                stored > 0 ? "<yellow>Нажмите, чтобы забрать" : "<gray>Пока пусто"),
                e -> new StorageMenu(plugin, player, 0).open());
        set(16, Text.icon(Material.WRITABLE_BOOK, "<white>Журнал",
                "<gray>История ваших участков, решения", "<gray>администрации и причины"),
                e -> new JournalMenu(plugin, player, 0).open());
        int unread = plugin.warnings().unread(player.getUniqueId());
        set(20, Text.icon(unread > 0 ? Material.BELL : Material.NOTE_BLOCK, "<gold>Журнал предупреждений",
                "<gray>Непрочитанных: <white>" + unread, "<gray>Аренда, электричество, отказы"),
                e -> new WarningsMenu(plugin, player, 0).open());
        set(26, Text.icon(Material.CHEST, "<aqua>Мои заказы",
                "<gray>Заказов: <white>" + plugin.orders().ofClient(player.getUniqueId()).size(),
                "<gray>Заберите готовые или отмените ожидающие"), e -> new MyOrdersMenu(plugin, player, 0).open());
        set(24, Text.icon(Material.GOLD_INGOT, "<gold>Банк", "<gray>Счета, комиссии, налоги"),
                e -> new BankMenu(plugin, player).open());
        if (player.hasPermission("buildmaster.grid")) {
            set(25, Text.icon(Material.LIGHTNING_ROD, "<red>Электросеть", "<gray>Станции, нагрузка, отчёты"),
                    e -> new GridMenu(plugin, player, 0).open());
        }
        if (player.hasPermission("buildmaster.admin")) {
            int pending = plugin.moderation().pending().size();
            set(22, Text.icon(Material.ANVIL, "<red>Модерация", "<gray>Ожидают проверки: <white>" + pending,
                    "<yellow>Названия и описания участков"),
                    e -> new ModerationMenu(plugin, player, 0).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
