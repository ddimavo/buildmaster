package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.District;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.GridService;
import ru.industryreborn.util.Text;

/** Потребление электроэнергии по районам; внутри района - по предприятиям, с отключением и подключением района. */
public final class ReportMenu extends Menu {

    public ReportMenu(IndustryRebornPlugin plugin, Player player, District district) {
        super(plugin, player, district == null ? "<dark_gray>Район › Электросеть › Потребление по районам"
                : "<dark_gray>Район › Электросеть › " + Text.esc(district.name), 6);
        if (!player.hasPermission("industryreborn.grid")) {
            set(22, Text.icon(Material.BARRIER, "<red>Недостаточно прав"));
            fill(Material.GRAY_STAINED_GLASS_PANE);
            return;
        }
        GridService grid = plugin.grid();
        if (district == null) {
            int slot = 9;
            List<Plot> assigned = new ArrayList<>();
            for (District d : plugin.districts().all()) {
                if (slot > 44) {
                    break;
                }
                List<Plot> plots = grid.plotsOf(d);
                assigned.addAll(plots);
                set(slot++, Text.icon(Material.FILLED_MAP, "<gold>" + Text.esc(d.name), lore(plugin, grid.report(plots), "<yellow>Нажмите: предприятия района")),
                        e -> new ReportMenu(plugin, player, d).open());
            }
            List<Plot> loose = new ArrayList<>(plugin.plots().all());
            loose.removeAll(assigned);
            if (slot <= 44) {
                set(slot, Text.icon(Material.MAP, "<gray>Без района", lore(plugin, grid.report(loose))));
            }
            navBar("Электросеть", () -> new GridMenu(plugin, player, 0).open(), 0, 1, p -> { });
        } else {
            List<Plot> plots = grid.plotsOf(district);
            int slot = 0;
            for (Plot p : plots) {
                if (slot > 35) {
                    break;
                }
                boolean off = grid.offReason(p) != null;
                set(9 + slot++, Text.icon(off ? Material.REDSTONE_LAMP : Material.SEA_LANTERN,
                        "<gold>" + Text.esc(p.displayName()) + " <gray>№ " + Text.esc(p.id),
                        lore(plugin, grid.report(List.of(p)), off ? "<red>Отключено: " + Text.esc(String.valueOf(grid.offReason(p)))
                                : "<green>Подключено к " + Text.esc(String.valueOf(p.plantId)))));
            }
            set(4, Text.icon(Material.FILLED_MAP, "<gold>" + Text.esc(district.name), lore(plugin, grid.report(plots))));
            set(2, Text.icon(Material.RED_CONCRETE, "<red>Отключить весь район", "<yellow>Потребуется причина"),
                    e -> plugin.chatPrompt().ask(player, "Причина отключения района (обязательно):", reason -> {
                        if (reason.isBlank() || reason.equalsIgnoreCase("отмена")) {
                            Text.err(player, "Действие отменено: причина обязательна.");
                        } else {
                            int n = 0;
                            for (Plot p : grid.plotsOf(district)) {
                                if (grid.disconnect(p, reason, player.getName()) == null) {
                                    n++;
                                }
                            }
                            Text.ok(player, "Отключено предприятий: " + n + ".");
                        }
                        new ReportMenu(plugin, player, district).open();
                    }));
            set(6, Text.icon(Material.LIME_CONCRETE, "<green>Подключить весь район"), e -> {
                int n = 0;
                for (Plot p : grid.plotsOf(district)) {
                    if (grid.restore(p, player.getName()) == null) {
                        n++;
                    }
                }
                Text.ok(player, "Подключено предприятий: " + n + ".");
                new ReportMenu(plugin, player, district).open();
            });
            navBar("Районы", () -> new ReportMenu(plugin, player, null).open(), 0, 1, p -> { });
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private static String[] lore(IndustryRebornPlugin plugin, GridService.Report r, String... extra) {
        List<String> lines = new ArrayList<>();
        lines.add("<gray>Предприятий: <white>" + r.plots());
        lines.add("<gray>Нагрузка: <white>" + Math.round(r.demand() * 100) / 100.0 + " V/сут");
        lines.add("<gray>За сутки: <white>" + Math.round(r.day().units() * 100) / 100.0 + " V ("
                + plugin.economy().format(r.day().money()) + ")");
        lines.add("<gray>За 7 суток: <white>" + Math.round(r.week().units() * 100) / 100.0 + " V ("
                + plugin.economy().format(r.week().money()) + ")");
        for (String e : extra) {
            lines.add(e);
        }
        return lines.toArray(new String[0]);
    }
}
