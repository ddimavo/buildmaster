package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.util.Text;

/** Права на участке: публичный доступ для всех и права отдельных игроков. */
public final class RightsMenu extends Menu {

    public RightsMenu(IndustryRebornPlugin plugin, Player player, Plot plot, PlotFilter back) {
        super(plugin, player, "<dark_gray>Район › Участки › " + Text.esc(plot.id) + " › Права", 6);
        set(4, Text.icon(Material.WRITABLE_BOOK, "<gold>Права на участке",
                "<gray>Строительство - ставить и ломать блоки", "<gray>Контейнеры - сундуки, бочки и т.п.",
                "<gray>Станки - печи, наковальни и др."));

        set(9, Ui.section("Доступ для всех", Material.LIGHT_BLUE_STAINED_GLASS_PANE));
        List<String> allowed = plugin.getConfig().getStringList("public-permissions." + plot.category.key());
        int slot = 11;
        for (Plot.Perm perm : new Plot.Perm[] {Plot.Perm.CONTAINERS, Plot.Perm.STATIONS}) {
            boolean permitted = allowed.stream().anyMatch(a -> a.equalsIgnoreCase(perm.name()));
            boolean on = plot.publicPerms.contains(perm);
            set(slot, Text.icon(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    "<white>Для всех: " + perm.title() + " - " + (on ? "<green>открыто" : "<red>закрыто"),
                    permitted ? "<yellow>Нажмите, чтобы переключить"
                            : "<red>Для типа «" + plot.category.title() + "» запрещено администратором"),
                    e -> {
                        String error = plugin.service().setPublic(plot, perm, !on);
                        if (error != null) {
                            Text.err(player, error);
                        }
                        new RightsMenu(plugin, player, plot, back).open();
                    });
            slot += 2;
        }

        set(27, Ui.section("Игроки с правами", Material.CYAN_STAINED_GLASS_PANE));
        int i = 29;
        for (var entry : plot.grants.entrySet()) {
            if (i > 44) {
                break;
            }
            UUID id = entry.getKey();
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            List<String> lore = new ArrayList<>();
            for (Plot.Perm perm : Plot.Perm.values()) {
                lore.add((entry.getValue().contains(perm) ? "<green>✔ " : "<red>✘ ") + perm.title());
            }
            lore.add("");
            lore.add("<yellow>Нажмите, чтобы изменить");
            head.editMeta(SkullMeta.class, meta -> {
                meta.setOwningPlayer(op);
                meta.displayName(Text.item("<aqua>" + Text.esc(String.valueOf(op.getName()))));
                meta.lore(lore.stream().map(Text::item).toList());
            });
            set(i++, head, e -> new PlayerRightsMenu(plugin, player, plot, id, back).open());
        }

        set(6, Text.icon(Material.PLAYER_HEAD, "<green>Добавить игрока", "<yellow>Введите ник"),
                e -> plugin.chatPrompt().ask(player, "Введите ник игрока:", text -> {
                    OfflinePlayer target = Bukkit.getPlayerExact(text);
                    if (target == null) {
                        target = Bukkit.getOfflinePlayerIfCached(text);
                    }
                    if (text.equalsIgnoreCase("отмена")) {
                        new RightsMenu(plugin, player, plot, back).open();
                    } else if (target == null || target.getUniqueId().equals(player.getUniqueId())) {
                        Text.err(player, "Игрок не найден.");
                        new RightsMenu(plugin, player, plot, back).open();
                    } else {
                        new PlayerRightsMenu(plugin, player, plot, target.getUniqueId(), back).open();
                    }
                }));
        navBar("Участок", () -> new PlotMenu(plugin, player, plot, back).open(), 0, 1, p -> { });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
