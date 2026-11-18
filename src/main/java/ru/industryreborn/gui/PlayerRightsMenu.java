package ru.industryreborn.gui;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.util.Text;

/** Права одного игрока на участке. */
public final class PlayerRightsMenu extends Menu {

    private static final int MAX_PLAYERS = 10;

    public PlayerRightsMenu(IndustryRebornPlugin plugin, Player player, Plot plot, UUID target, PlotFilter back) {
        super(plugin, player, "<dark_gray>Район › Участки › Права › " + Text.esc(String.valueOf(Bukkit.getOfflinePlayer(target).getName())), 3);
        int slot = 11;
        for (Plot.Perm perm : Plot.Perm.values()) {
            boolean on = plot.permsOf(target).contains(perm);
            set(slot, Text.icon(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    "<white>" + perm.title() + ": " + (on ? "<green>есть" : "<red>нет"),
                    "<yellow>Нажмите, чтобы переключить"), e -> {
                        if (!on && !plot.grants.containsKey(target) && plot.grants.size() >= MAX_PLAYERS) {
                            Text.err(player, "Нельзя выдать права больше чем " + MAX_PLAYERS + " игрокам.");
                        } else {
                            if (on) {
                                plot.revoke(target, perm);
                            } else {
                                plot.grant(target, perm);
                            }
                            plugin.plots().save();
                            plugin.journal().add(plot.id, target, player.getName(), Journal.Action.RIGHTS,
                                    perm.title() + ": " + (on ? "снято" : "выдано"));
                        }
                        new PlayerRightsMenu(plugin, player, plot, target, back).open();
                    });
            slot += 2;
        }
        set(22, Text.icon(Material.RED_STAINED_GLASS, "<red>Лишить всех прав"), e -> {
            plot.revokeAll(target);
            plugin.plots().save();
            plugin.journal().add(plot.id, target, player.getName(), Journal.Action.RIGHTS, "права сняты");
            new RightsMenu(plugin, player, plot, back).open();
        });
        set(18, Ui.back(null), e -> new RightsMenu(plugin, player, plot, back).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
