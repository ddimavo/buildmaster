package ru.buildmaster.gui;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.service.WarningLog;
import ru.buildmaster.util.Text;

/** Журнал предупреждений игрока: аренда, электричество, отказы. */
public final class WarningsMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    public WarningsMenu(BuildMasterPlugin plugin, Player player, int page) {
        super(plugin, player, "<dark_gray>Журнал предупреждений", 6);
        List<WarningLog.Warning> list = plugin.warnings().forPlayer(player.getUniqueId());
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= list.size()) {
                break;
            }
            WarningLog.Warning w = list.get(index);
            set(i, Text.icon(w.read ? Material.PAPER : Material.YELLOW_DYE,
                    w.read ? "<gray>" + Text.date(w.time) : "<yellow>Новое · " + Text.date(w.time),
                    "<gray>Участок: <white>" + Text.esc(w.plotId), "", w.text));
        }
        if (list.isEmpty()) {
            set(22, Text.icon(Material.LIME_DYE, "<green>Предупреждений нет"));
        }
        if (current > 0) {
            set(45, Ui.prev(current, pages), e -> new WarningsMenu(plugin, player, current - 1).open());
        }
        set(49, Ui.home(), e -> new MainMenu(plugin, player).open());
        if (current < pages - 1) {
            set(53, Ui.next(current, pages), e -> new WarningsMenu(plugin, player, current + 1).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
        // непрочитанные подсвечены только при этом открытии
        plugin.warnings().markAllRead(player.getUniqueId());
    }
}
