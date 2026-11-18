package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.RoomClass;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.util.Text;

/** Номера отеля: свободные можно снять прямо из меню. */
public final class RoomsMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private static final int FIRST = 9;

    public RoomsMenu(IndustryRebornPlugin plugin, Player player, Plot hotel, PlotFilter back, int page) {
        this(plugin, player, hotel, back, page, null);
    }

    /** filter - показывать только номера этого класса (null - все). */
    public RoomsMenu(IndustryRebornPlugin plugin, Player player, Plot hotel, PlotFilter back, int page, RoomClass filter) {
        super(plugin, player, "<dark_gray>Район › Участки › " + Text.esc(hotel.displayName()) + " › Номера", 6);
        List<Plot> allRooms = plugin.hotels().rooms(hotel);
        List<Plot> rooms = filter == null ? allRooms : allRooms.stream().filter(r -> r.roomClass == filter).toList();
        boolean admin = player.hasPermission("industryreborn.admin");
        boolean manager = hotel.isOwner(player.getUniqueId());
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
            lore.add("<gray>Класс: " + room.roomClass.badge());
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
            lore.add("<gray>Срок аренды: <white>до " + plugin.service().maxRentDays(room) + " сут.");
            boolean canClass = admin || manager;
            if (canClass) {
                lore.add("<aqua>ПКМ - класс номера");
            }
            Material icon = mine ? Material.EMERALD_BLOCK : room.isFree() ? room.roomClass.icon() : Material.RED_BED;
            int pageNow = current;
            set(FIRST + i, Text.icon(icon, "<gold>" + Text.esc(room.displayName()) + " <gray>№ " + Text.esc(room.id),
                    lore.toArray(new String[0])), e -> {
                        if (canClass && e.isRightClick()) {
                            new RoomClassMenu(plugin, player, room, () -> new RoomsMenu(plugin, player, hotel, back, pageNow, filter)).open();
                        } else {
                            new PlotMenu(plugin, player, room, back).open();
                        }
                    });
        }
        if (rooms.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>В отеле пока нет номеров"));
        }
        List<String> summary = new ArrayList<>();
        summary.add("<gray>Свободно: <white>" + plugin.hotels().freeRooms(hotel) + "<gray> из <white>" + allRooms.size());
        for (var e : plugin.hotels().classSummary(hotel).entrySet()) {
            if (e.getValue()[0] > 0) {
                summary.add(e.getKey().badge() + "<gray>: <white>" + (int) e.getValue()[1] + "<gray> занято из <white>" + (int) e.getValue()[0]);
            }
        }
        set(4, Text.icon(Material.RED_BED, "<gold>Номера отеля", summary.toArray(new String[0])));
        RoomClass next = filter == null ? RoomClass.values()[0]
                : filter.ordinal() + 1 < RoomClass.values().length ? RoomClass.values()[filter.ordinal() + 1] : null;
        set(2, Text.icon(filter == null ? Material.COMPASS : filter.icon(), "<gold>Фильтр по классу: " + (filter == null ? "<white>все" : filter.badge()),
                "<yellow>Нажмите: " + (next == null ? "показать все" : "только " + next.title())),
                e -> new RoomsMenu(plugin, player, hotel, back, 0, next).open());
        navBar("Отель", () -> new PlotMenu(plugin, player, hotel, back).open(), current, pages, p -> new RoomsMenu(plugin, player, hotel, back, p, filter).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
