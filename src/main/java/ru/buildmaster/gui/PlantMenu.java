package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.GridService;
import ru.buildmaster.util.Text;

/** Одна электростанция: мощность, нагрузка и подключённые предприятия. */
public final class PlantMenu extends Menu {

    private static final int PAGE_SIZE = 36;

    public PlantMenu(BuildMasterPlugin plugin, Player player, Plot plant, int page) {
        super(plugin, player, "<dark_gray>Станция " + Text.esc(plant.id), 6);
        if (!player.hasPermission("buildmaster.grid")) {
            set(22, Text.icon(Material.BARRIER, "<red>Недостаточно прав"));
            fill(Material.GRAY_STAINED_GLASS_PANE);
            return;
        }
        GridService grid = plugin.grid();
        String who = player.getName();
        double load = grid.load(plant);
        set(4, Text.icon(Material.LIGHTNING_ROD, "<gold>" + Text.esc(plant.displayName()),
                "<gray>Мощность: <white>" + plant.plantCapacity + " V/сут",
                "<gray>Потребление: <white>" + Math.round(grid.demand(plant) * 100) / 100.0 + " V/сут ("
                        + (Double.isInfinite(load) ? "перегрузка" : Math.round(load * 1000) / 10.0 + "%") + ")",
                "<gray>Тариф станции: ×<white>" + plant.plantMultiplier,
                "<yellow>Нажмите, чтобы задать мощность"),
                e -> plugin.chatPrompt().ask(player, "Введите мощность станции в V/сут:", text -> {
                    if (!text.equalsIgnoreCase("отмена")) {
                        try {
                            String error = grid.setCapacity(plant, Double.parseDouble(text.replace(',', '.')), who);
                            if (error != null) {
                                Text.err(player, error);
                            }
                        } catch (NumberFormatException ex) {
                            Text.err(player, "Нужно число.");
                        }
                    }
                    new PlantMenu(plugin, player, plant, page).open();
                }));
        int[] deltas = {-100, -10, 10, 100};
        int[] slots = {1, 2, 6, 7};
        for (int i = 0; i < deltas.length; i++) {
            int d = deltas[i];
            set(slots[i], Text.icon(d < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                    (d < 0 ? "<red>" : "<green>") + (d > 0 ? "+" : "") + d + " V/сут"), e -> {
                        grid.setCapacity(plant, Math.max(0, plant.plantCapacity + d), who);
                        new PlantMenu(plugin, player, plant, page).open();
                    });
        }

        List<Plot> plots = grid.connected(plant);
        int pages = Math.max(1, (plots.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= plots.size()) {
                break;
            }
            Plot p = plots.get(index);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Управляющий: <white>" + Text.esc(String.valueOf(p.ownerName)));
            lore.add("<gray>Потребление: <white>" + Math.round(p.account.unitsPerDay() * 100) / 100.0 + " V/сут");
            lore.add("<gray>Баланс счёта: <white>" + plugin.economy().format(plugin.power().balance(p)));
            lore.add("");
            lore.add("<yellow>Нажмите, чтобы отключить от сети");
            set(9 + i, Text.icon(Material.REDSTONE_LAMP, "<gold>" + Text.esc(p.displayName()) + " <gray>№ " + Text.esc(p.id),
                    lore.toArray(new String[0])),
                    e -> plugin.chatPrompt().ask(player, "Причина отключения от сети (обязательно):", reason -> {
                        if (reason.isBlank() || reason.equalsIgnoreCase("отмена")) {
                            Text.err(player, "Действие отменено: причина обязательна.");
                        } else {
                            String error = grid.disconnect(p, reason, who);
                            if (error != null) {
                                Text.err(player, error);
                            }
                        }
                        new PlantMenu(plugin, player, plant, current).open();
                    }));
        }
        if (plots.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>К станции никто не подключён"));
        }
        if (current > 0) {
            set(45, Text.icon(Material.ARROW, "<yellow>Назад"), e -> new PlantMenu(plugin, player, plant, current - 1).open());
        }
        set(49, Text.icon(Material.ARROW, "<yellow>К списку станций"), e -> new GridMenu(plugin, player, 0).open());
        if (current < pages - 1) {
            set(53, Text.icon(Material.ARROW, "<yellow>Вперёд"), e -> new PlantMenu(plugin, player, plant, current + 1).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
