package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.PlotFilter;
import ru.buildmaster.util.Text;

/** Номера отеля: свободные можно снять прямо из меню. */
public final class RoomsMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    public RoomsMenu(BuildMasterPlugin plugin, Player player, Plot hotel, PlotFilter back, int page) {
        super(plugin, player, "<dark_gray>Номера · " + Text.esc(hotel.displayName()), 6);
        List<Plot> rooms = plugin.hotels().rooms(hotel);
        int pages = Math.max(1, (rooms.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= rooms.size()) {
                break;
            }
            Plot room = rooms.get(index);
            boolean mine = room.involves(player.getUniqueId());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Цена: <white>" + plugin.economy().format(room.rentPerDay) + " за сутки");
            lore.add("<gray>Размер: <white>" + room.box.describe());
            if (room.isFree()) {
                lore.add("<green>Свободен");
                lore.add("<yellow>Нажмите, чтобы снять");
            } else {
                lore.add("<red>Занят: <white>" + Text.esc(String.valueOf(room.ownerName)));
                lore.add("<gray>Осталось: <white>" + Text.duration(room.rentedUntil - System.currentTimeMillis()));
                if (mine) {
                    lore.add("<yellow>Нажмите, чтобы управлять");
                }
            }
            Material icon = mine ? Material.EMERALD_BLOCK : room.isFree() ? Material.LIME_BED : Material.RED_BED;
            set(i, Text.icon(icon, "<gold>" + Text.esc(room.displayName()) + " <gray>№ " + Text.esc(room.id),
                    lore.toArray(new String[0])), e -> new PlotMenu(plugin, player, room, back).open());
        }
        if (rooms.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>В отеле пока нет номеров"));
        }
        if (current > 0) {
            set(45, Ui.prev(current, pages), e -> new RoomsMenu(plugin, player, hotel, back, current - 1).open());
        }
        set(49, Text.icon(Material.ARROW, "<yellow>К отелю"), e -> new PlotMenu(plugin, player, hotel, back).open());
        if (current < pages - 1) {
            set(53, Ui.next(current, pages), e -> new RoomsMenu(plugin, player, hotel, back, current + 1).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
