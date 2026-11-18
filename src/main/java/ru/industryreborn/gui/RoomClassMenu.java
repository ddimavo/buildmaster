package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.RoomClass;
import ru.industryreborn.util.Text;

/** Выбор класса номера: администратор меняет сразу, управляющий отеля подаёт заявку. */
public final class RoomClassMenu extends Menu {

    public RoomClassMenu(IndustryRebornPlugin plugin, Player player, Plot room, Supplier<Menu> back) {
        super(plugin, player, "<dark_gray>Район › Номера › " + Text.esc(room.displayName()) + " › Класс", 3);
        boolean admin = player.hasPermission("industryreborn.admin");
        var pending = plugin.moderation().pendingRoomClass(room.id);
        int[] slots = {10, 12, 14, 16};
        for (RoomClass c : RoomClass.values()) {
            boolean current = room.roomClass == c;
            boolean requested = pending != null && RoomClass.parse(pending.text) == c;
            List<String> lore = new ArrayList<>();
            lore.add(c.badge());
            lore.add("<gray>Цена за сутки: <white>" + plugin.hotels().rangeText(c));
            lore.add("<gray>Срок аренды: <white>до " + plugin.hotels().maxDays(c) + " сут.");
            double power = plugin.hotels().powerFactor(c);
            lore.add("<gray>Электричество: <white>×" + (power == Math.rint(power) ? String.valueOf((int) power) : String.valueOf(power)));
            lore.add("");
            if (current) {
                lore.add("<green>● Текущий класс");
            } else if (requested) {
                lore.add("<yellow>● Заявка подана, ждёт администратора");
            } else if (admin) {
                lore.add("<yellow>Нажмите: назначить сразу");
            } else {
                lore.add("<yellow>Нажмите: подать заявку администратору");
            }
            var icon = Text.icon(c.icon(), (current ? "<green>✔ " : "<white>") + c.title(), lore.toArray(new String[0]));
            set(slots[c.ordinal()], icon, current || requested ? null : e -> {
                String result = plugin.hotels().changeClass(player, room, c);
                if (result == null) {
                    Text.ok(player, "Класс номера " + room.id + ": " + c.plain() + ".");
                } else if (result.isEmpty()) {
                    Text.ok(player, "Заявка на класс «" + c.title() + "» отправлена администратору. Решение придёт в чат и в журнал.");
                } else {
                    Text.err(player, result);
                }
                new RoomClassMenu(plugin, player, room, back).open();
            });
        }
        set(4, Text.icon(Material.RED_BED, "<gold>Класс номера " + Text.esc(room.id),
                "<gray>Сейчас: " + room.roomClass.badge(),
                admin ? "<gray>Администратор меняет класс без заявки" : "<gray>Смену класса одобряет администратор"));
        navBar("Назад", back == null ? null : () -> back.get().open(), 0, 1, i -> { });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
