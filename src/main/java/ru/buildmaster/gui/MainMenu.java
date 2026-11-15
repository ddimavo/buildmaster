package ru.buildmaster.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.util.Text;

public final class MainMenu extends Menu {

    public MainMenu(BuildMasterPlugin plugin, Player player) {
        super(plugin, player, "<dark_gray>Район коммерции", 3);
        int free = (int) plugin.plots().all().stream().filter(p -> p.isFree()).count();
        int mine = (int) plugin.plots().all().stream().filter(p -> p.isMember(player.getUniqueId())).count();
        int pending = plugin.pending().count(player.getUniqueId());

        set(11, Text.icon(Material.LIME_CONCRETE, "<green>Свободные участки",
                "<gray>Доступно: <white>" + free, "<yellow>Нажмите, чтобы посмотреть"),
                e -> new PlotListMenu(plugin, player, PlotListMenu.Mode.FREE, 0).open());
        set(13, Text.icon(Material.EMERALD_BLOCK, "<gold>Мои участки",
                "<gray>У вас: <white>" + mine, "<yellow>Управление зданием, арендой"),
                e -> new PlotListMenu(plugin, player, PlotListMenu.Mode.MINE, 0).open());
        set(15, Text.icon(pending > 0 ? Material.CHEST : Material.BARREL, "<aqua>Возвращённые предметы",
                "<gray>Ожидают выдачи: <white>" + pending,
                pending > 0 ? "<yellow>Нажмите, чтобы забрать" : "<gray>Пока ничего нет"),
                e -> {
                    int left = plugin.pending().claim(player);
                    if (left > 0) {
                        Text.send(player, "<yellow>В инвентаре нет места. Осталось предметов: " + left);
                    } else {
                        Text.ok(player, "Все предметы выданы.");
                    }
                    new MainMenu(plugin, player).open();
                });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
