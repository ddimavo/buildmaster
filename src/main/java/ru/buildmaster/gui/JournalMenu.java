package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.TextRules;
import ru.buildmaster.util.Text;

/** Журнал игрока: события по его участкам и решения администрации с причинами. */
public final class JournalMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    public JournalMenu(BuildMasterPlugin plugin, Player player, int page) {
        super(plugin, player, "<dark_gray>Журнал", 6);
        List<Journal.Entry> entries = plugin.journal().forPlayer(player.getUniqueId());
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= entries.size()) {
                break;
            }
            Journal.Entry e = entries.get(index);
            boolean bad = switch (e.action()) {
                case NAME_REJECTED, DESC_REJECTED, EVICTED, SEIZED, STORAGE_ARRESTED, STORAGE_DELETED, EXPIRED -> true;
                default -> false;
            };
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + Text.date(e.time()));
            lore.add("<gray>Участок: <white>" + Text.esc(e.plotId()));
            lore.add("<gray>Кто: <white>" + Text.esc(e.actor()));
            if (!e.reason().isBlank()) {
                lore.add("<gray>Причина / детали:");
                for (String l : TextRules.wrap(e.reason(), 38)) {
                    lore.add("<white>" + Text.esc(l));
                }
            }
            set(i, Text.icon(bad ? Material.RED_DYE : Material.PAPER,
                    (bad ? "<red>" : "<green>") + e.action().title(), lore.toArray(new String[0])));
        }
        if (entries.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>Записей пока нет"));
        }
        if (current > 0) {
            set(45, Text.icon(Material.ARROW, "<yellow>Назад"), e -> new JournalMenu(plugin, player, current - 1).open());
        }
        set(49, Text.icon(Material.OAK_DOOR, "<gray>В главное меню"), e -> new MainMenu(plugin, player).open());
        if (current < pages - 1) {
            set(53, Text.icon(Material.ARROW, "<yellow>Вперёд"), e -> new JournalMenu(plugin, player, current + 1).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
