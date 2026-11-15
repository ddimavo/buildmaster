package ru.buildmaster.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.util.Text;

public final class ConfirmMenu extends Menu {

    public ConfirmMenu(BuildMasterPlugin plugin, Player player, String question, String[] lore,
                       Runnable onYes, Runnable onNo) {
        super(plugin, player, "<dark_red>Подтверждение", 3);
        set(13, Text.icon(Material.PAPER, "<white>" + Text.esc(question), lore));
        set(11, Text.icon(Material.LIME_CONCRETE, "<green>Да"), e -> onYes.run());
        set(15, Text.icon(Material.RED_CONCRETE, "<red>Нет"), e -> onNo.run());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
