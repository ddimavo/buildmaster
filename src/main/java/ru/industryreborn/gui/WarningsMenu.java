package ru.industryreborn.gui;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.service.WarningLog;
import ru.industryreborn.util.Text;

/** Журнал предупреждений игрока: аренда, электричество, отказы. */
public final class WarningsMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private static final int FIRST = 9;

    public WarningsMenu(IndustryRebornPlugin plugin, Player player, int page) {
        super(plugin, player, "<dark_gray>Район › Уведомления", 6);
        List<WarningLog.Warning> list = plugin.warnings().forPlayer(player.getUniqueId());
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= list.size()) {
                break;
            }
            WarningLog.Warning w = list.get(index);
            set(FIRST + i, Text.icon(w.read ? Material.PAPER : Material.YELLOW_DYE,
                    w.read ? "<gray>" + Text.date(w.time) : "<yellow>Новое · " + Text.date(w.time),
                    "<gray>Участок: <white>" + Text.esc(w.plotId), "", w.text));
        }
        if (list.isEmpty()) {
            set(22, Text.icon(Material.LIME_DYE, "<green>Предупреждений нет"));
        }
        set(4, Text.icon(Material.BELL, "<gold>Уведомления", "<gray>Всего: <white>" + list.size(),
                "<gray>Аренда, электричество, отклики и отказы"));
        navBar(null, null, current, pages, p -> new WarningsMenu(plugin, player, p).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
        // непрочитанные подсвечены только при этом открытии
        plugin.warnings().markAllRead(player.getUniqueId());
    }
}
