package ru.buildmaster.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.util.Text;

/** Подтверждение опасного действия: вопрос с пояснениями посередине, «Да» слева, «Нет» справа. */
public final class ConfirmMenu extends Menu {

    public ConfirmMenu(BuildMasterPlugin plugin, Player player, String question, String[] lore,
                       Runnable onYes, Runnable onNo) {
        super(plugin, player, "<dark_gray>Подтверждение", 3);
        String[] details = new String[lore.length + 2];
        System.arraycopy(lore, 0, details, 0, lore.length);
        details[lore.length] = "";
        details[lore.length + 1] = "<red>Действие нельзя будет отменить";
        set(13, Text.icon(Material.PAPER, "<white>" + Text.esc(question), details));
        set(11, Text.icon(Material.LIME_CONCRETE, "<green>Да, подтверждаю", "<yellow>Нажмите, чтобы выполнить"),
                e -> onYes.run());
        set(15, Text.icon(Material.RED_CONCRETE, "<red>Нет, отмена", "<yellow>Нажмите, чтобы вернуться"),
                e -> onNo.run());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
