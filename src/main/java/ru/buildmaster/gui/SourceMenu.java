package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.PlotFilter;
import ru.buildmaster.util.Text;

/** Выбор материала, который нужно заменить (показываются блоки, реально присутствующие в зоне). */
public final class SourceMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Plot plot;
    private final String zoneName;
    private final Box box;
    private final PlotFilter back;

    public SourceMenu(BuildMasterPlugin plugin, Player player, Plot plot, String zoneName, Box box,
                      PlotFilter back, int page) {
        super(plugin, player, "<dark_gray>Заменить блок: " + Text.esc(zoneName), 6);
        this.plot = plot;
        this.zoneName = zoneName;
        this.box = box;
        this.back = back;
        set(22, Text.icon(Material.CLOCK, "<yellow>Сканирую здание..."));
        set(49, Ui.back(null), e -> new ZoneMenu(plugin, player, plot, back).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
        plugin.build().scan(plot, box, counts -> show(counts, page), error ->
                Text.err(player, "Не удалось просканировать здание: " + error.getMessage()));
    }

    private void show(Map<Material, Integer> counts, int page) {
        // игрок мог закрыть меню, пока шло сканирование
        if (player.getOpenInventory().getTopInventory().getHolder() != this) {
            return;
        }
        clear();
        List<Map.Entry<Material, Integer>> entries = new ArrayList<>(counts.entrySet());
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= entries.size()) {
                break;
            }
            Material material = entries.get(index).getKey();
            int count = entries.get(index).getValue();
            ItemStack icon = Icons.block(material);
            icon = Text.icon(icon.getType(), null,
                    "<gray>Блоков: <white>" + count, "<yellow>Нажмите, чтобы заменить");
            set(i, icon, e -> new TargetMenu(plugin, player, plot, zoneName, box, material, "", 0, back).open());
        }
        if (entries.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<red>Здесь нет блоков, которые можно заменить"));
        }
        if (current > 0) {
            set(45, Ui.prev(current, pages), e -> show(counts, current - 1));
        }
        set(49, Ui.back(null), e -> new ZoneMenu(plugin, player, plot, back).open());
        if (current < pages - 1) {
            set(53, Ui.next(current, pages), e -> show(counts, current + 1));
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
