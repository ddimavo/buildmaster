package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.TextRules;
import ru.buildmaster.util.Text;

/** Один счёт: баланс и выписка; банковский работник может списать долг по налогу. */
public final class AccountMenu extends Menu {

    public AccountMenu(BuildMasterPlugin plugin, Player player, Plot plot) {
        super(plugin, player, "<dark_gray>Счёт · участок " + Text.esc(plot.id), 6);
        plugin.power().touch(plot);
        boolean banker = player.hasPermission("buildmaster.bank");
        boolean mine = plot.isOwner(player.getUniqueId());

        List<String> info = new ArrayList<>();
        info.add("<gray>Баланс: <white>" + plugin.economy().format(plugin.power().balance(plot)));
        info.add("<gray>Управляющий: <white>" + Text.esc(String.valueOf(plot.ownerName)));
        if (plot.taxDebt > 1e-9) {
            info.add("<red>Долг по налогу: " + plugin.economy().format(plot.taxDebt));
        }
        set(4, Text.icon(Material.GOLD_BLOCK, "<gold>" + Text.esc(plot.displayName()), info.toArray(new String[0])));

        List<Journal.Entry> entries = plugin.bank().statement(plot.id, 27);
        int slot = 18;
        for (Journal.Entry e : entries) {
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + Text.date(e.time()) + " · " + Text.esc(e.actor()));
            for (String l : TextRules.wrap(e.reason(), 36)) {
                lore.add("<white>" + Text.esc(l));
            }
            set(slot++, Text.icon(Material.PAPER, "<yellow>" + e.action().title(), lore.toArray(new String[0])));
        }
        if (entries.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>Операций пока нет"));
        }
        if (banker && plot.taxDebt > 1e-9) {
            set(11, Text.icon(Material.RED_DYE, "<red>Списать долг по налогу", "<yellow>Потребуется причина"),
                    e -> plugin.chatPrompt().ask(player, "Причина списания долга (обязательно):", reason -> {
                        if (reason.isBlank() || reason.equalsIgnoreCase("отмена")) {
                            Text.err(player, "Действие отменено: причина обязательна.");
                        } else {
                            String error = plugin.bank().forgiveDebt(plot, player.getName(), reason);
                            if (error != null) {
                                Text.err(player, error);
                            }
                        }
                        new AccountMenu(plugin, player, plot).open();
                    }));
        }
        set(49, Text.icon(Material.ARROW, "<yellow>Назад"),
                e -> new AccountsMenu(plugin, player, banker && !mine, 0).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
