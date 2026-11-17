package ru.industryreborn.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.util.Text;

/** Выбор части здания, в которой будет идти замена. */
public final class ZoneMenu extends Menu {

    public ZoneMenu(IndustryRebornPlugin plugin, Player player, Plot plot, PlotFilter back) {
        super(plugin, player, "<dark_gray>Что изменить? Участок " + Text.esc(plot.id), 4);
        set(4, Text.icon(Material.NETHER_BRICKS, "<gold>Всё здание", "<gray>Замена во всём участке",
                "<yellow>Нажмите для выбора"),
                e -> new SourceMenu(plugin, player, plot, "Всё здание", plot.box, back, 0).open());
        int slot = 9;
        for (Plot.Zone zone : plot.zones.values()) {
            if (slot > 26) {
                break;
            }
            Material icon = Material.matchMaterial(zone.icon());
            set(slot++, Text.icon(icon != null && icon.isItem() ? icon : Material.BRICKS,
                    "<aqua>" + Text.esc(zone.name()), "<gray>Часть здания: <white>" + zone.box().describe(),
                    "<yellow>Нажмите для выбора"),
                    e -> new SourceMenu(plugin, player, plot, zone.name(), zone.box(), back, 0).open());
        }
        if (plot.zones.isEmpty()) {
            set(13, Text.icon(Material.PAPER, "<gray>Отдельные зоны не настроены",
                    "<gray>Администратор может добавить их", "<gray>командой /district zone add"));
        }
        set(31, Ui.back(null), e -> new PlotMenu(plugin, player, plot, back).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
