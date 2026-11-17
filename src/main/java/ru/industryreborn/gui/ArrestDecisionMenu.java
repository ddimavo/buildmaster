package ru.industryreborn.gui;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.ArrestService;
import ru.industryreborn.util.Text;

/** Содержимое арестованной партии (или всех вещей игрока) и три решения. */
public final class ArrestDecisionMenu extends Menu {

    public ArrestDecisionMenu(IndustryRebornPlugin plugin, Player player, UUID target, UUID entryId) {
        super(plugin, player, "<dark_gray>Решение по вещам", 6);
        if (!player.hasPermission("industryreborn.admin")) {
            fill(Material.GRAY_STAINED_GLASS_PANE);
            return;
        }
        int slot = 0;
        for (StorageEntry entry : plugin.arrests().entries(target)) {
            if (entryId != null && !entry.id.equals(entryId)) {
                continue;
            }
            for (byte[] b : entry.stacks) {
                if (slot >= 36) {
                    break;
                }
                set(slot++, ItemStack.deserializeBytes(b));
            }
        }
        if (slot == 0) {
            set(22, Text.icon(Material.BARRIER, "<gray>Вещей нет"));
        }
        set(45, Ui.back(null), e -> new ArrestedPlayerMenu(plugin, player, target, 0).open());
        set(46, Text.icon(Material.LIME_CONCRETE, "<green>Вернуть игроку",
                "<gray>Вещи станут доступны в его хранилище", "<yellow>Потребуется причина"),
                e -> ArrestedPlayerMenu.decide(plugin, player, target, entryId, ArrestService.Decision.RELEASE));
        set(49, Text.icon(Material.GOLD_BLOCK, "<gold>На аукцион",
                "<gray>Вещи уйдут на общий аукцион от казны,", "<gray>выручка - в казну",
                plugin.treasuryAuction().ready() ? "<green>Аукцион подключён" : "<yellow>Аукцион недоступен: вещи подождут в очереди",
                "<yellow>Потребуется причина"),
                e -> ArrestedPlayerMenu.decide(plugin, player, target, entryId, ArrestService.Decision.AUCTION));
        set(52, Text.icon(Material.RED_CONCRETE, "<red>Удалить",
                "<gray>Вещи удаляются безвозвратно", "<yellow>Потребуется причина"),
                e -> ArrestedPlayerMenu.decide(plugin, player, target, entryId, ArrestService.Decision.DELETE));
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
