package ru.buildmaster.gui;

import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.util.Text;

/** Администратор: игроки с арестованными вещами. Нажатие открывает их вещи и решения. */
public final class ArrestedMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    public ArrestedMenu(BuildMasterPlugin plugin, Player player, int page) {
        super(plugin, player, "<dark_gray>Арестованные вещи", 6);
        if (!player.hasPermission("buildmaster.admin")) {
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
            set(i, icon, e -> new ArrestedPlayerMenu(plugin, player, id, 0).open());
        }
        if (players.isEmpty()) {
            set(22, Text.icon(Material.LIME_DYE, "<green>Арестованных вещей нет"));
        }
        if (current > 0) {
            set(45, Text.icon(Material.ARROW, "<yellow>Назад"), e -> new ArrestedMenu(plugin, player, current - 1).open());
        }
        set(49, Text.icon(Material.ARROW, "<yellow>В главное меню"), e -> new MainMenu(plugin, player).open());
        if (current < pages - 1) {
            set(53, Text.icon(Material.ARROW, "<yellow>Вперёд"), e -> new ArrestedMenu(plugin, player, current + 1).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
