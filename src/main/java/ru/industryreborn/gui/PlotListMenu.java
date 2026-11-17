package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.util.Text;

/** Список участков с фильтром по типу, сортировкой и поиском. */
public final class PlotListMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    public PlotListMenu(IndustryRebornPlugin plugin, Player player, PlotFilter filter, int page) {
        super(plugin, player, filter.mode == PlotFilter.Mode.FREE ? "<dark_gray>Свободные участки" : "<dark_gray>Мои участки", 6);
        List<Plot> plots = filter.apply(plugin.plots().all().stream().filter(p -> p.approval == Plot.Approval.APPROVED || p.isLandlord(player.getUniqueId())).toList(), player.getUniqueId());
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
            set(i, Text.icon(Icons.statusMaterial(plot, plot.involves(player.getUniqueId())),
                    "<gold>" + Text.esc(plot.displayName()) + " <gray>№ " + Text.esc(plot.id),
                    lore.toArray(new String[0])),
                    e -> new PlotMenu(plugin, player, plot, filter).open());
        }
        if (plots.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<red>Ничего не найдено", "<gray>Измените фильтры или поиск"));
        }
        if (current > 0) {
            set(45, Ui.prev(current, pages), e -> new PlotListMenu(plugin, player, filter, current - 1).open());
        }
        Category cat = filter.category;
        set(46, Text.icon(Material.HOPPER, "<aqua>Тип: <white>" + (cat == null ? "все" : cat.title()),
                "<yellow>Нажмите, чтобы переключить"), e -> {
                    PlotFilter f = filter.copy();
                    f.category = filter.nextCategory();
                    new PlotListMenu(plugin, player, f, 0).open();
                });
        set(47, Text.icon(Material.COMPARATOR, "<aqua>Сортировка: <white>" + filter.sort.title()
                + (filter.descending ? " ↓" : " ↑"),
                "<yellow>ЛКМ - сменить поле", "<yellow>ПКМ - сменить направление"), e -> {
                    PlotFilter f = filter.copy();
                    if (e.isRightClick()) {
                        f.descending = !f.descending;
                    } else {
                        f.sort = f.sort.next();
                    }
                    new PlotListMenu(plugin, player, f, 0).open();
                });
        set(48, Text.icon(Material.COMPASS, "<aqua>Поиск",
                filter.search.isEmpty() ? "<gray>По номеру или названию" : "<gray>Сейчас: <white>" + Text.esc(filter.search),
                "<yellow>ЛКМ - ввести, ПКМ - сбросить"), e -> {
                    if (e.isRightClick()) {
                        PlotFilter f = filter.copy();
                        f.search = "";
                        new PlotListMenu(plugin, player, f, 0).open();
                    } else {
                        plugin.chatPrompt().ask(player, "Введите номер или часть названия участка:", text -> {
                            PlotFilter f = filter.copy();
                            f.search = text.equalsIgnoreCase("отмена") ? filter.search : text;
                            new PlotListMenu(plugin, player, f, 0).open();
                        });
                    }
                });
        set(49, Ui.home(), e -> new MainMenu(plugin, player).open());
        if (current < pages - 1) {
            set(53, Ui.next(current, pages), e -> new PlotListMenu(plugin, player, filter, current + 1).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
