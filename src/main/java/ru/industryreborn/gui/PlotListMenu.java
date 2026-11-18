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

/**
 * Список участков. Шапка: назад (0), тип (2), поиск (4), сортировка (6); карточки в строках 1-4 (36 на странице), внизу навигация.
 * ЛКМ по карточке открывает участок, ПКМ по своему арендованному - окно продления.
 */
public final class PlotListMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private static final int FIRST = 9;

    public PlotListMenu(IndustryRebornPlugin plugin, Player player, PlotFilter filter, int page) {
        super(plugin, player, "<dark_gray>Район › " + (filter.mode == PlotFilter.Mode.FREE ? "Свободные участки" : "Мои участки"), 6);
        var id = player.getUniqueId();
        List<Plot> plots = filter.apply(plugin.plots().all().stream().filter(p -> p.approval == Plot.Approval.APPROVED || p.isLandlord(id)).toList(), id);
        int pages = Math.max(1, (plots.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= plots.size()) {
                break;
            }
            Plot plot = plots.get(index);
            boolean mine = plot.involves(id);
            boolean extendable = plot.isOwner(id) && plot.status == Plot.Status.RENTED && plot.rentPerDay >= 0 && !plugin.service().isLocked(plot);
            List<String> lore = new ArrayList<>(List.of(Icons.plotLore(plugin, plot)));
            lore.add("");
            lore.add("<yellow>ЛКМ - открыть");
            if (extendable) {
                lore.add("<yellow>ПКМ - продлить аренду");
            }
            set(FIRST + i, Text.icon(Icons.categoryMaterial(plot.category),
                    Icons.dot(plugin, plot, mine) + "<gold>" + Text.esc(plot.displayName()) + " <gray>№ " + Text.esc(plot.id),
                    lore.toArray(new String[0])),
                    e -> {
                        if (e.isRightClick() && extendable) {
                            PlotMenu.extendDialog(plugin, player, plot, () -> new PlotListMenu(plugin, player, filter, current).open());
                        } else {
                            new PlotMenu(plugin, player, plot, filter).open();
                        }
                    });
        }
        if (plots.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<red>Ничего не найдено", "<gray>Измените фильтры или поиск"));
        }

        // ---- шапка: фильтры ----
        Category cat = filter.category;
        set(2, Text.icon(cat == null ? Material.HOPPER : Icons.categoryMaterial(cat), "<aqua>Тип: <white>" + (cat == null ? "все" : cat.title()),
                "<yellow>Нажмите, чтобы переключить"), e -> {
                    PlotFilter f = filter.copy();
                    f.category = filter.nextCategory();
                    new PlotListMenu(plugin, player, f, 0).open();
                });
        set(4, Text.icon(Material.COMPASS, "<aqua>Поиск",
                filter.search.isEmpty() ? "<gray>По номеру или названию" : "<gray>Сейчас: <white>" + Text.esc(filter.search),
                "<yellow>ЛКМ - ввести, ПКМ - сбросить"), e -> {
                    if (e.isRightClick()) {
                        PlotFilter f = filter.copy();
                        f.search = "";
                        new PlotListMenu(plugin, player, f, 0).open();
                    } else {
                        plugin.dialogs().text(player, "Поиск участка", List.of("<gray>Номер или часть названия"), "Что искать", filter.search, 32, text -> {
                            PlotFilter f = filter.copy();
                            f.search = text;
                            new PlotListMenu(plugin, player, f, 0).open();
                        }, () -> new PlotListMenu(plugin, player, filter, 0).open());
                    }
                });
        set(6, Text.icon(Material.COMPARATOR, "<aqua>Сортировка: <white>" + filter.sort.title()
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

        navBar(null, null, current, pages, p -> new PlotListMenu(plugin, player, filter, p).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
