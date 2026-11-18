package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/** Голограмма над дверью номера: номер, статус, гость, срок, цена. Чистое форматирование. */
public final class DoorText {

    private DoorText() { }

    /** Номер комнаты без префикса отеля: «h-101» -> «101». */
    public static String number(Plot room, Plot hotel) {
        if (hotel != null && room.id.regionMatches(true, 0, hotel.id + "-", 0, hotel.id.length() + 1)) {
            return room.id.substring(hotel.id.length() + 1);
        }
        return room.id;
    }

    public static List<String> lines(Plot room, Plot hotel, DoubleFunction<String> money, long now) {
        List<String> lines = new ArrayList<>();
        lines.add("<gold><bold>Номер " + Text.esc(number(room, hotel)));
        if (hotel != null) {
            lines.add("<gray>" + Text.esc(hotel.displayName()));
        }
        lines.add(room.roomClass.badge());
        if (room.name != null && !room.name.isBlank()) {
            lines.add("<white>" + Text.esc(room.name));
        }
        if (room.restorePending) {
            lines.add("<yellow>● Идёт уборка");
        } else if (room.isFree()) {
            lines.add("<green>● Свободен");
            lines.add("<yellow>" + money.apply(Math.max(0, room.rentPerDay)) + " <gray>/ сутки");
            lines.add("<gray>" + room.box.sizeX() + "×" + room.box.sizeY() + "×" + room.box.sizeZ() + " блоков");
        } else {
            lines.add(room.isFrozen() ? "<gold>● Ожидает оплаты" : "<red>● Занят");
            lines.add("<gray>Гость: <white>" + Text.esc(String.valueOf(room.ownerName)));
            long left = room.rentedUntil - now;
            lines.add("<gray>До: <white>" + Text.date(room.rentedUntil) + " <dark_gray>(" + Text.duration(left) + ")");
        }
        return lines;
    }

    /**
     * Куда встать, чтобы оказаться в коридоре перед дверью лицом к ней: {x, y, z, yaw}. Дверь стоит в стене между интерьером номера и коридором;
     * возвращает null, если сторону определить нельзя.
     */
    public static double[] standSpot(Box interior, int dx, int dy, int dz) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            boolean outside = !interior.contains(dx + d[0], dy, dz + d[1]) && !interior.contains(dx + d[0], dy + 1, dz + d[1]);
            boolean inside = interior.contains(dx - d[0], dy, dz - d[1]) || interior.contains(dx - d[0], dy + 1, dz - d[1]);
            if (outside && inside) {
                double yaw = Math.toDegrees(Math.atan2(d[0], -d[1]));
                return new double[] {dx + 0.5 + d[0] * 1.5, dy, dz + 0.5 + d[1] * 1.5, yaw};
            }
        }
        return null;
    }

    /**
     * Точка над дверью со стороны коридора: дверь стоит в стене, интерьер номера с одной стороны, коридор с другой.
     * Возвращает {x, y, z} или null, если сторону определить нельзя.
     */
    public static double[] anchor(Box interior, int dx, int dy, int dz) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            boolean outside = !interior.contains(dx + d[0], dy, dz + d[1]) && !interior.contains(dx + d[0], dy + 1, dz + d[1]);
            boolean inside = interior.contains(dx - d[0], dy, dz - d[1]) || interior.contains(dx - d[0], dy + 1, dz - d[1]);
            if (outside && inside) {
                // у стены со стороны коридора, чуть выше двери
                return new double[] {dx + 0.5 + d[0] * 0.55, dy + 2.65, dz + 0.5 + d[1] * 0.55};
            }
        }
        return null;
    }
}
