package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.GridService;
import ru.industryreborn.util.Text;

/** Панель электросети (право industryreborn.grid): станции, нагрузка, цена электроэнергии, отчёты. */
public final class GridMenu extends Menu {

    private static final int PAGE_SIZE = 36;

    public GridMenu(IndustryRebornPlugin plugin, Player player, int page) {
        super(plugin, player, "<dark_gray>Район › Электросеть", 6);
        if (!player.hasPermission("industryreborn.grid")) {
            set(22, Text.icon(Material.BARRIER, "<red>Недостаточно прав"));
            fill(Material.GRAY_STAINED_GLASS_PANE);
            return;
        }
        GridService grid = plugin.grid();
        List<Plot> plants = grid.plants();
        long loose = plugin.plots().all().stream().filter(p -> p.category == Category.WORKSHOP && p.plantId == null).count();

        set(4, Text.icon(Material.LIGHTNING_ROD, "<gold>Электросеть",
                "<gray>Цена за 1 V: <white>" + plugin.economy().format(plugin.power().base()),
                "<gray>Режим тарифа: <white>" + plugin.power().mode(),
                "<gray>Станций: <white>" + plants.size(),
                loose > 0 && !plants.isEmpty() ? "<red>Не подключено мастерских: " + loose : "<gray>Все мастерские подключены",
                "<yellow>Нажмите, чтобы изменить цену"),
                e -> plugin.chatPrompt().ask(player, "Введите цену за 1 V (или reset):", text -> {
                    if (!text.equalsIgnoreCase("отмена")) {
                        try {
                            Double v = text.equalsIgnoreCase("reset") ? null : Double.parseDouble(text.replace(',', '.'));
                            if (v != null && (v < 0 || v.isNaN() || v.isInfinite())) {
                                throw new NumberFormatException();
                            }
                            plugin.power().overrides().setBase(v);
                            plugin.power().saveOverrides();
                            plugin.power().reloadTariffs(player.getName(), "цена за 1 V " + (v == null ? "сброшена" : "= " + v));
                        } catch (NumberFormatException ex) {
                            Text.err(player, "Нужно число не меньше нуля или reset.");
                        }
                    }
                    new GridMenu(plugin, player, page).open();
                }));

        int pages = Math.max(1, (plants.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= plants.size()) {
                break;
            }
            Plot plant = plants.get(index);
            double load = grid.load(plant);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Мощность: <white>" + plant.plantCapacity + " V/сут");
            lore.add("<gray>Потребление: <white>" + Math.round(grid.demand(plant) * 100) / 100.0 + " V/сут ("
                    + (Double.isInfinite(load) ? "перегрузка" : Math.round(load * 1000) / 10.0 + "%") + ")");
            lore.add("<gray>Тариф станции: ×<white>" + plant.plantMultiplier);
            lore.add("<gray>Предприятий: <white>" + grid.connected(plant).size());
            lore.add("");
            lore.add("<yellow>Нажмите, чтобы открыть");
            set(9 + i, Text.icon(plant.plantMultiplier > 1.0 ? Material.REDSTONE_BLOCK : Material.EMERALD_BLOCK,
                    "<gold>" + Text.esc(plant.displayName()) + " <gray>№ " + Text.esc(plant.id), lore.toArray(new String[0])),
                    e -> new PlantMenu(plugin, player, plant, 0).open());
        }
        if (plants.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>Электростанций нет",
                    "<gray>Создайте: /district create <номер> power_plant - -"));
        }
        set(2, Text.icon(Material.REPEATER, "<aqua>Подключить все свободные мастерские",
                "<gray>К наименее загруженным станциям"), e -> {
                    Text.ok(player, "Подключено: " + grid.autoConnectAll(player.getName()) + ".");
                    new GridMenu(plugin, player, current).open();
                });
        set(6, Text.icon(Material.MAP, "<aqua>Потребление по районам"), e -> new ReportMenu(plugin, player, null).open());
        navBar(null, null, current, pages, p -> new GridMenu(plugin, player, p).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
