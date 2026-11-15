package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

public final class PlotListMenu extends Menu {

    public enum Mode { FREE, MINE }

    private static final int PAGE_SIZE = 45;

    public PlotListMenu(BuildMasterPlugin plugin, Player player, Mode mode, int page) {
        super(plugin, player, mode == Mode.FREE ? "<dark_gray>Свободные участки" : "<dark_gray>Мои участки", 6);
        List<Plot> plots = new ArrayList<>();
        for (Plot p : plugin.plots().all()) {
            boolean match = mode == Mode.FREE ? p.isFree() : p.isMember(player.getUniqueId());
            if (match) {
                plots.add(p);
            }
        }
        plots.sort(Comparator.comparing(p -> p.id.toLowerCase()));
        int pages = Math.max(1, (plots.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= plots.size()) {
                break;
            }
            Plot plot = plots.get(index);
            List<String> lore = new ArrayList<>(List.of(Icons.plotLore(plugin, plot)));
            lore.add("");
            lore.add("<yellow>Нажмите, чтобы открыть");
            set(i, Text.icon(Icons.statusMaterial(plot, plot.isMember(player.getUniqueId())),
                    "<gold>Участок <white>" + Text.esc(plot.id), lore.toArray(new String[0])),
                    e -> new PlotMenu(plugin, player, plot, mode).open());
        }
        if (plots.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<red>Здесь пока пусто"));
        }
        if (current > 0) {
            set(45, Text.icon(Material.ARROW, "<yellow>Назад"), e -> new PlotListMenu(plugin, player, mode, current - 1).open());
        }
        set(49, Text.icon(Material.OAK_DOOR, "<gray>В главное меню"), e -> new MainMenu(plugin, player).open());
        if (current < pages - 1) {
            set(53, Text.icon(Material.ARROW, "<yellow>Вперёд"), e -> new PlotListMenu(plugin, player, mode, current + 1).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
