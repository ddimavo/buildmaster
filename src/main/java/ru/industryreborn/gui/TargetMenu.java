package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.util.Text;

/** Выбор материала, на который заменяем. Есть поиск по названию (английский ключ блока). */
public final class TargetMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    public TargetMenu(IndustryRebornPlugin plugin, Player player, Plot plot, String zoneName, Box box,
                      Material from, String filter, int page, PlotFilter back) {
        super(plugin, player, "<dark_gray>Заменить на...", 6);
        List<Material> all = new ArrayList<>();
        String needle = filter.toLowerCase(Locale.ROOT).replace(' ', '_');
        for (Material m : plugin.rules().replaceTargets()) {
            if (needle.isEmpty() || m.getKey().getKey().contains(needle)) {
                all.add(m);
            }
        }
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= all.size()) {
                break;
            }
            Material to = all.get(index);
            ItemStack icon = new ItemStack(to);
            icon.editMeta(meta -> meta.lore(List.of(Text.item("<yellow>Нажмите, чтобы заменить"))));
            set(i, icon, e -> {
                player.closeInventory();
                plugin.build().replace(player, plot, box, from, to);
            });
        }
        if (all.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<red>Ничего не найдено"));
        }
        if (current > 0) {
            set(45, Ui.prev(current, pages),
                    e -> new TargetMenu(plugin, player, plot, zoneName, box, from, filter, current - 1, back).open());
        }
        set(47, Text.icon(Material.COMPASS, "<aqua>Поиск по названию",
                filter.isEmpty() ? "<gray>Например: oak, stone, glass" : "<gray>Сейчас: <white>" + Text.esc(filter)),
                e -> plugin.chatPrompt().ask(player, "Введите часть названия блока (например oak, brick, glass):",
                        text -> new TargetMenu(plugin, player, plot, zoneName, box, from,
                                text.equalsIgnoreCase("отмена") ? filter : text, 0, back).open()));
        if (!filter.isEmpty()) {
            set(48, Text.icon(Material.BARRIER, "<red>Сбросить поиск"),
                    e -> new TargetMenu(plugin, player, plot, zoneName, box, from, "", 0, back).open());
        }
        set(49, Ui.back(null),
                e -> new SourceMenu(plugin, player, plot, zoneName, box, back, 0).open());
        if (current < pages - 1) {
            set(53, Ui.next(current, pages),
                    e -> new TargetMenu(plugin, player, plot, zoneName, box, from, filter, current + 1, back).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
