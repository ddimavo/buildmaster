package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.StorageEntry;
import ru.buildmaster.service.ArrestService;
import ru.buildmaster.util.Text;

/**
 * Арестованные вещи одного игрока. Вверху - сами предметы (для осмотра), снизу - решение по всем вещам игрока.
 * Партии (с одного участка) показываются отдельно: нажатие на партию открывает решение только по ней.
 */
public final class ArrestedPlayerMenu extends Menu {

    private final UUID target;

    public ArrestedPlayerMenu(BuildMasterPlugin plugin, Player player, UUID target, int page) {
        super(plugin, player, "<dark_gray>Арест · " + Text.esc(String.valueOf(Bukkit.getOfflinePlayer(target).getName())), 6);
        this.target = target;
        if (!player.hasPermission("buildmaster.admin")) {
            fill(Material.GRAY_STAINED_GLASS_PANE);
            return;
        }
        int slot = 0;
        for (StorageEntry entry : plugin.arrests().entries(target)) {
            if (slot >= 45) {
                break;
            }
            int items = 0;
            List<String> lore = new ArrayList<>();
            ItemStack first = null;
            for (byte[] b : entry.stacks) {
                ItemStack s = ItemStack.deserializeBytes(b);
                items += s.getAmount();
                if (first == null) {
                    first = s;
                }
                if (lore.size() < 6) {
                    lore.add("<gray>" + s.getAmount() + " × <white>" + s.getType().name());
                }
            }
            if (entry.stacks.size() > 6) {
                lore.add("<gray>... и ещё " + (entry.stacks.size() - 6));
            }
            lore.add(0, "<gray>Участок: <white>" + Text.esc(entry.plotId) + "<gray>, " + Text.date(entry.createdAt));
            lore.add(1, "<gray>Причина ареста: <white>" + Text.esc(String.valueOf(entry.reason)));
            lore.add(2, "<gray>Предметов: <white>" + items);
            lore.add("<yellow>Нажмите, чтобы решить по этой партии");
            Material icon = first == null ? Material.CHEST : first.getType();
            UUID entryId = entry.id;
            set(slot++, Text.icon(icon, "<gold>Партия " + Text.esc(entry.plotId), lore.toArray(new String[0])),
                    e -> new ArrestDecisionMenu(plugin, player, target, entryId).open());
        }
        if (slot == 0) {
            set(22, Text.icon(Material.LIME_DYE, "<green>Арестованных вещей нет"));
        }
        set(45, Text.icon(Material.ARROW, "<yellow>К списку"), e -> new ArrestedMenu(plugin, player, 0).open());
        set(49, Text.icon(Material.IRON_BARS, "<red>Решить по всем вещам игрока",
                "<gray>Вещей: <white>" + plugin.arrests().count(target), "<yellow>Нажмите"),
                e -> new ArrestDecisionMenu(plugin, player, target, null).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    /** Решение: вернуть, удалить или на аукцион; причина обязательна и спрашивается в чате. */
    static void decide(BuildMasterPlugin plugin, Player admin, UUID target, UUID entryId, ArrestService.Decision decision) {
        plugin.chatPrompt().ask(admin, decision.title() + ": укажите причину (обязательно):", reason -> {
            if (reason.isBlank() || reason.equalsIgnoreCase("отмена")) {
                Text.err(admin, "Действие отменено: причина обязательна.");
            } else {
                String error = plugin.arrests().decide(admin.getName(), target, entryId, decision, reason);
                if (error != null) {
                    Text.err(admin, error);
                } else {
                    Text.ok(admin, decision.action().title() + ".");
                }
            }
            new ArrestedPlayerMenu(plugin, admin, target, 0).open();
        });
    }
}
