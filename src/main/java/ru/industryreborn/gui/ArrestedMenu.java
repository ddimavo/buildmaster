package ru.industryreborn.gui;

import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.util.Text;

/** Администратор: игроки с арестованными вещами. Нажатие открывает их вещи и решения. */
public final class ArrestedMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private static final int FIRST = 9;

    public ArrestedMenu(IndustryRebornPlugin plugin, Player player, int page) {
        super(plugin, player, "<dark_gray>Район › Арестованные вещи", 6);
        if (!player.hasPermission("industryreborn.admin")) {
            set(22, Text.icon(Material.BARRIER, "<red>Недостаточно прав"));
            fill(Material.GRAY_STAINED_GLASS_PANE);
            return;
        }
        List<UUID> players = plugin.arrests().players();
        int pages = Math.max(1, (players.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= players.size()) {
                break;
            }
            UUID id = players.get(index);
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            String name = op.getName() == null ? id.toString().substring(0, 8) : op.getName();
            ItemStack icon = Text.icon(Material.PLAYER_HEAD, "<gold>" + Text.esc(name),
                    "<gray>Партий: <white>" + plugin.arrests().entries(id).size(),
                    "<gray>Вещей: <white>" + plugin.arrests().count(id),
                    "<yellow>Нажмите, чтобы посмотреть и решить");
            icon.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(op));
            set(FIRST + i, icon, e -> new ArrestedPlayerMenu(plugin, player, id, 0).open());
        }
        if (players.isEmpty()) {
            set(22, Text.icon(Material.LIME_DYE, "<green>Арестованных вещей нет"));
        }
        set(4, Text.icon(Material.IRON_BARS, "<red>Арестованные вещи", "<gray>Игроков: <white>" + players.size(),
                "<gray>Выберите игрока, чтобы вернуть, удалить или отправить на аукцион"));
        navBar(null, null, current, pages, p -> new ArrestedMenu(plugin, player, p).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
