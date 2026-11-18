package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.RoomClass;
import ru.industryreborn.util.Text;

/**
 * Отели: номера - вложенные участки типа ROOM со своей областью (интерьер), дверью и защитой.
 * Стены остаются за отелем, поэтому гость меняет всё внутри номера, кроме стен и двери.
 * Аренда номера оплачивается гостем со счёта и поступает на счёт управляющего отеля за вычетом налога на аренду.
 */
public final class HotelService {

    private final IndustryRebornPlugin plugin;
    private Map<String, Plot> doorIndex;
    private int indexVersion = -1;

    public HotelService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ номера

    public List<Plot> rooms(Plot hotel) {
        List<Plot> list = plugin.plots().childrenOf(hotel.id);
        list.sort(Comparator.comparing(p -> p.id.toLowerCase()));
        return list;
    }

    public Plot hotelOf(Plot room) {
        return room.parentId == null ? null : plugin.plots().get(room.parentId);
    }

    public boolean hasOccupiedRooms(Plot hotel) {
        for (Plot r : plugin.plots().childrenOf(hotel.id)) {
            if (!r.isFree() || r.restorePending || r.busy) {
                return true;
            }
        }
        return false;
    }

    public int freeRooms(Plot hotel) {
        int n = 0;
        for (Plot r : plugin.plots().childrenOf(hotel.id)) {
            if (r.isFree() && !r.restorePending) {
                n++;
            }
        }
        return n;
    }

    /** Блок лежит внутри одного из номеров отеля (эта область принадлежит номерам, а не отелю). */
    public boolean inChildRoom(Plot hotel, int x, int y, int z) {
        for (Plot r : plugin.plots().childrenOf(hotel.id)) {
            if (r.box.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ деньги

    /**
     * Доход от платежа за участок. Обычные участки платят в казну. Номер платит управляющему отеля
     * (на счёт отеля, за вычетом налога на аренду); если управляющего нет - в казну.
     */
    public void income(Plot plot, double amount, String reason) {
        if (plot.isPlayerPlot() && plot.landlord != null) {
            landlordIncome(plot, amount, reason);
            return;
        }
        Plot hotel = plot.parentId == null ? null : plugin.plots().get(plot.parentId);
        if (hotel == null || hotel.owner == null) {
            plugin.treasury().deposit(amount, reason);
            plugin.saveTreasury();
            return;
        }
        plugin.power().touch(hotel);
        double net = plugin.bank().applyRentTax(hotel.id, hotel.owner, amount);
        hotel.account.deposit(net);
        hotel.revenue += net;
        plot.roomRevenue += net;
        plugin.jobs().onIncome(hotel, net);
        plugin.plots().save();
        plugin.journal().add(hotel.id, hotel.owner, "система", Journal.Action.HOTEL,
                reason + ": на счёт отеля " + plugin.economy().format(net)
                        + (net < amount ? " (налог на аренду " + plugin.economy().format(amount - net) + ")" : ""));
        plugin.service().warn(hotel.owner, hotel.id, "<green>Поступила оплата за номер <white>" + Text.esc(plot.id)
                + "</white>: " + plugin.economy().format(net) + " на счёт отеля"
                + (net < amount ? " (налог на аренду " + plugin.economy().format(amount - net) + ")" : "") + ".");
    }

    /** Аренда участка игрока: доход арендодателю на кошелёк за вычетом налога на аренду. */
    private void landlordIncome(Plot plot, double amount, String reason) {
        double net = plugin.bank().applyRentTax(plot.id, plot.landlord, amount);
        var seller = org.bukkit.Bukkit.getOfflinePlayer(plot.landlord);
        if (!plugin.economy().deposit(seller, net)) {
            plugin.treasury().deposit(net, "Не зачислена аренда участка " + plot.id + " арендодателю");
            plugin.saveTreasury();
            plugin.getLogger().warning("Не удалось зачислить арендодателю аренду участка " + plot.id);
            return;
        }
        plugin.journal().add(plot.id, plot.landlord, "система", Journal.Action.PLAYER_PLOT,
                reason + ": арендодателю " + plugin.economy().format(net)
                        + (net < amount ? " (налог на аренду " + plugin.economy().format(amount - net) + ")" : ""));
        plugin.service().warn(plot.landlord, plot.id, "<green>Поступила оплата аренды участка <white>" + Text.esc(plot.id)
                + "</white>: " + plugin.economy().format(net) + (net < amount
                ? " (налог на аренду " + plugin.economy().format(amount - net) + ")" : "") + ".");
    }

    // ------------------------------------------------------------ цены

    /** Цена номера за сутки: администратор - любая, управляющий отеля - не выше maxRoomRate отеля. */
    public String setRate(Player who, Plot room, double rate) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.HOTELS);
        if (moduleOff != null) {
            return moduleOff;
        }
        if (room.category != Category.ROOM) {
            return "«" + room.id + "» не номер отеля.";
        }
        if (rate < 0 || Double.isNaN(rate) || Double.isInfinite(rate)) {
            return "Цена должна быть числом не меньше нуля.";
        }
        Plot hotel = hotelOf(room);
        boolean admin = who.hasPermission("industryreborn.admin");
        boolean manager = hotel != null && hotel.isOwner(who.getUniqueId());
        if (!admin && !manager) {
            return "Цену номера меняет администратор или управляющий отеля.";
        }
        if (!admin && (hotel.maxRoomRate <= 0 || rate > hotel.maxRoomRate)) {
            return hotel.maxRoomRate <= 0 ? "Администратор не разрешил управляющему менять цены номеров."
                    : "Цена номера не может быть выше " + plugin.economy().format(hotel.maxRoomRate) + " за сутки.";
        }
        if (!admin && (rate < minRate(room.roomClass) || (maxRate(room.roomClass) > 0 && rate > maxRate(room.roomClass)))) {
            return "Для класса «" + room.roomClass.title() + "» цена за сутки: " + rangeText(room.roomClass) + ".";
        }
        room.rentPerDay = rate;
        plugin.plots().save();
        plugin.holograms().update(room);
        plugin.journal().add(room.id, null, who.getName(), Journal.Action.HOTEL,
                "Цена номера: " + plugin.economy().format(rate) + " за сутки");
        return null;
    }

    // ------------------------------------------------------------ классы номеров

    /**
     * Смена класса номера. Администратор меняет сразу и без проверки; управляющий отеля подаёт заявку, которую одобряет администратор.
     * Возвращает ошибку, null при немедленной смене и пустую строку, если заявка подана.
     */
    public String changeClass(Player who, Plot room, RoomClass target) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.HOTELS);
        if (moduleOff != null) {
            return moduleOff;
        }
        if (room.category != Category.ROOM) {
            return "«" + room.id + "» не номер отеля.";
        }
        if (target == null) {
            return "Неизвестный класс. Доступны: " + classList();
        }
        boolean admin = who.hasPermission("industryreborn.admin");
        Plot hotel = hotelOf(room);
        if (!admin && (hotel == null || !hotel.isOwner(who.getUniqueId()))) {
            return "Класс номера меняет администратор; управляющий отеля может подать заявку.";
        }
        if (room.roomClass == target && (admin || plugin.moderation().pendingRoomClass(room.id) == null)) {
            return "Номер " + room.id + " уже имеет класс «" + target.title() + "».";
        }
        if (admin) {
            applyClass(room, target, who.getName(), "администратор");
            return null;
        }
        if (plugin.moderation().pendingRoomClass(room.id) != null) {
            return "Заявка на смену класса этого номера уже ждёт решения администратора.";
        }
        plugin.moderation().submitRoomClass(who, room, target);
        return "";
    }

    /** Ставит класс и обновляет всё, что его показывает. */
    public void applyClass(Plot room, RoomClass target, String by, String how) {
        applyClass(room, target, by, how, null);
    }

    /** subject - игрок, чью заявку исполнили: запись попадёт и в его журнал. */
    public void applyClass(Plot room, RoomClass target, String by, String how, java.util.UUID subject) {
        RoomClass old = room.roomClass;
        room.roomClass = target;
        double fitted = room.rentPerDay >= 0 ? clampRate(target, room.rentPerDay) : room.rentPerDay;
        String note = "";
        if (fitted != room.rentPerDay) {
            note = ", цена подогнана под класс: " + plugin.economy().format(room.rentPerDay) + " → " + plugin.economy().format(fitted);
            room.rentPerDay = fitted;
        }
        plugin.plots().save();
        plugin.holograms().update(room);
        plugin.journal().add(room.id, subject, by, Journal.Action.HOTEL,
                "Класс номера: " + old.title() + " → " + target.title() + " (" + how + ")" + note);
    }

    private boolean classesOn() {
        return plugin.getConfig().getBoolean("hotel-classes.enabled", true);
    }

    private static final double[][] DEFAULT_RANGE = {{0, 100}, {50, 250}, {150, 500}, {300, 0}};
    private static final int[] DEFAULT_DAYS = {3, 7, 14, 14};
    private static final double[] DEFAULT_POWER = {1.0, 1.0, 1.2, 1.5};

    /** Нижняя граница цены за сутки для класса (0 - нет). */
    public double minRate(RoomClass c) {
        return !classesOn() ? 0 : Math.max(0, plugin.getConfig().getDouble("hotel-classes." + c.key() + ".min-rate", DEFAULT_RANGE[c.ordinal()][0]));
    }

    /** Верхняя граница цены за сутки для класса (0 - нет). */
    public double maxRate(RoomClass c) {
        return !classesOn() ? 0 : Math.max(0, plugin.getConfig().getDouble("hotel-classes." + c.key() + ".max-rate", DEFAULT_RANGE[c.ordinal()][1]));
    }

    /** Максимальный срок аренды номера этого класса, сутки (не больше общего max-rent-days). */
    public int maxDays(RoomClass c) {
        int global = Math.max(1, plugin.getConfig().getInt("max-rent-days", 14));
        if (!classesOn()) {
            return global;
        }
        return Math.max(1, Math.min(global, plugin.getConfig().getInt("hotel-classes." + c.key() + ".max-days", DEFAULT_DAYS[c.ordinal()])));
    }

    /** Множитель потребления электричества номера этого класса. */
    public double powerFactor(RoomClass c) {
        return !classesOn() ? 1.0 : Math.max(0, plugin.getConfig().getDouble("hotel-classes." + c.key() + ".power", DEFAULT_POWER[c.ordinal()]));
    }

    /** Цена в границах класса. */
    public double clampRate(RoomClass c, double rate) {
        double min = minRate(c);
        double max = maxRate(c);
        double r = Math.max(rate, min);
        return max > 0 ? Math.min(r, max) : r;
    }

    /** «от 50 до 250» / «от 300» / «до 100» / «без границ» для подсказок. */
    public String rangeText(RoomClass c) {
        double min = minRate(c);
        double max = maxRate(c);
        if (min <= 0 && max <= 0) {
            return "без границ";
        }
        if (max <= 0) {
            return "от " + plugin.economy().format(min);
        }
        return min <= 0 ? "до " + plugin.economy().format(max) : plugin.economy().format(min) + " - " + plugin.economy().format(max);
    }

    /** Сводка по классам отеля: [класс] -> {номеров, занято, доход}. */
    public Map<RoomClass, double[]> classSummary(Plot hotel) {
        Map<RoomClass, double[]> map = new java.util.EnumMap<>(RoomClass.class);
        for (RoomClass c : RoomClass.values()) {
            map.put(c, new double[3]);
        }
        for (Plot room : rooms(hotel)) {
            double[] v = map.get(room.roomClass);
            v[0]++;
            if (!room.isFree()) {
                v[1]++;
            }
            v[2] += room.roomRevenue;
        }
        return map;
    }

    public static String classList() {
        StringBuilder sb = new StringBuilder();
        for (RoomClass c : RoomClass.values()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(c.key()).append(" (").append(c.title()).append(")");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ двери

    private static String doorKey(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    /** Перестраивает индекс дверей (после создания и удаления номеров, смены дверей, загрузки). */
    public void invalidate() {
        doorIndex = null;
    }

    private Map<String, Plot> index() {
        if (doorIndex == null || indexVersion != plugin.plots().modCount()) {
            indexVersion = plugin.plots().modCount();
            Map<String, Plot> map = new HashMap<>();
            for (Plot p : plugin.plots().all()) {
                for (int[] d : p.doors) {
                    map.put(doorKey(p.world, d[0], d[1], d[2]), p);
                }
            }
            doorIndex = map;
        }
        return doorIndex;
    }

    /** Номер, которому принадлежит дверь в этом блоке (учитывается и верхняя половина двери). */
    public Plot roomAtDoor(Block block) {
        Map<String, Plot> map = index();
        if (map.isEmpty()) {
            return null;
        }
        String world = block.getWorld().getName();
        Plot room = map.get(doorKey(world, block.getX(), block.getY(), block.getZ()));
        if (room == null) {
            room = map.get(doorKey(world, block.getX(), block.getY() - 1, block.getZ()));
        }
        return room;
    }

    /** Свободный номер открыт всем (можно осмотреть), занятый - гостю, его доверенным и управляющему отеля. */
    public boolean canUseDoor(Player player, Plot room) {
        if (player.hasPermission("industryreborn.bypass") || room.isFree()) {
            return true;
        }
        if (room.involves(player.getUniqueId())) {
            return true;
        }
        Plot hotel = hotelOf(room);
        return hotel != null && hotel.isOwner(player.getUniqueId());
    }

    /** Добавляет дверь в номер: блок должен лежать внутри отеля, но вне интерьера номера. */
    public String addDoor(Plot room, Block block) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.HOTELS);
        if (moduleOff != null) {
            return moduleOff;
        }
        if (room.category != Category.ROOM) {
            return "«" + room.id + "» не номер отеля.";
        }
        Plot hotel = hotelOf(room);
        if (hotel == null || !hotel.world.equals(block.getWorld().getName())
                || !hotel.box.contains(block.getX(), block.getY(), block.getZ())) {
            return "Дверь должна находиться внутри отеля.";
        }
        if (room.box.contains(block.getX(), block.getY(), block.getZ())) {
            return "Дверь должна быть в стене номера, а не внутри него.";
        }
        for (int[] d : room.doors) {
            if (d[0] == block.getX() && d[1] == block.getY() && d[2] == block.getZ()) {
                return "Эта дверь уже добавлена.";
            }
        }
        if (room.doors.size() >= 4) {
            return "У номера не больше четырёх дверей.";
        }
        room.doors.add(new int[] {block.getX(), block.getY(), block.getZ()});
        plugin.plots().save();
        invalidate();
        return null;
    }

    public void clearDoors(Plot room) {
        room.doors.clear();
        plugin.plots().save();
        invalidate();
    }

    // ------------------------------------------------------------ создание и удаление номеров

    /** Проверка границ нового номера. Возвращает ошибку или null. */
    public String validateRoomBox(Plot hotel, Box box, String roomId) {
        if (hotel.category != Category.HOTEL) {
            return "«" + hotel.id + "» не отель.";
        }
        if (!roomId.matches("^[A-Za-z0-9_]{1,16}$")) {
            return "Номер комнаты: латиница, цифры и _, до 16 символов.";
        }
        if (!hotel.box.contains(box)) {
            return "Номер должен целиком находиться внутри отеля.";
        }
        String id = hotel.id + "-" + roomId;
        if (plugin.plots().get(id) != null || plugin.service().isCreating(id)) {
            return "Номер «" + id + "» уже есть.";
        }
        Plot overlap = plugin.plots().overlapping(hotel.world, box, hotel.id);
        if (overlap != null) {
            return "Область пересекается с участком " + overlap.id + ".";
        }
        long max = plugin.getConfig().getLong("max-plot-volume", 2_000_000);
        if (box.volume() > max) {
            return "Слишком большая область номера.";
        }
        return null;
    }

    /**
     * Создаёт несколько номеров по шаблону: первый номер - выделенная область, остальные сдвинуты на step блоков
     * вдоль оси (x или z). Номера называются prefix1, prefix2... Дверь задаётся смещением от угла первого номера.
     * Сначала проверяются все области, и только потом начинается создание.
     */
    public String template(Player admin, Plot hotel, String prefix, Box first, boolean axisX, int step, int count,
                           double rate, int[] doorOffset, Consumer<Integer> done) {
        return template(admin, hotel, prefix, first, axisX, step, count, rate, doorOffset, RoomClass.ECONOMY, done);
    }

    public String template(Player admin, Plot hotel, String prefix, Box first, boolean axisX, int step, int count,
                           double rate, int[] doorOffset, RoomClass roomClass, Consumer<Integer> done) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.HOTELS);
        if (moduleOff != null) {
            return moduleOff;
        }
        if (count < 1 || count > 100) {
            return "Количество номеров: от 1 до 100.";
        }
        if (step < 1) {
            return "Шаг должен быть положительным.";
        }
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int shift = i * step;
            Box b = axisX ? new Box(first.minX() + shift, first.minY(), first.minZ(), first.maxX() + shift, first.maxY(), first.maxZ())
                    : new Box(first.minX(), first.minY(), first.minZ() + shift, first.maxX(), first.maxY(), first.maxZ() + shift);
            String error = validateRoomBox(hotel, b, prefix + (i + 1));
            if (error != null) {
                return "Номер " + prefix + (i + 1) + ": " + error;
            }
            for (Box other : boxes) {
                if (other.intersects(b)) {
                    return "Шаблон даёт пересекающиеся номера: увеличьте шаг (размер номера "
                            + (axisX ? first.sizeX() : first.sizeZ()) + " блоков по оси).";
                }
            }
            boxes.add(b);
        }
        createChain(admin, hotel, prefix, boxes, 0, rate, first, doorOffset, 0, roomClass, done);
        return null;
    }

    private void createChain(Player admin, Plot hotel, String prefix, List<Box> boxes, int index, double rate, Box first,
                             int[] doorOffset, int created, RoomClass roomClass, Consumer<Integer> done) {
        if (index >= boxes.size()) {
            done.accept(created);
            return;
        }
        Box b = boxes.get(index);
        List<int[]> doors = new ArrayList<>();
        if (doorOffset != null) {
            doors.add(new int[] {b.minX() + doorOffset[0], b.minY() + doorOffset[1], b.minZ() + doorOffset[2]});
        }
        plugin.service().createRoom(admin, hotel, prefix + (index + 1), b, rate, doors, roomClass, ok ->
                createChain(admin, hotel, prefix, boxes, index + 1, rate, first, doorOffset, created + (ok ? 1 : 0), roomClass, done));
    }

    /** Удаляет свободный номер вместе со снимком и книгой учёта. */
    public String deleteRoom(Plot room) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.HOTELS);
        if (moduleOff != null) {
            return moduleOff;
        }
        if (room.category != Category.ROOM) {
            return "«" + room.id + "» не номер отеля.";
        }
        if (!room.isFree() || plugin.service().isLocked(room)) {
            return "Удалять можно только свободный номер. Сначала выселите гостя: /district evict " + room.id + " <причина>";
        }
        plugin.plots().remove(room);
        plugin.snapshots().delete(plugin.plots().snapshotDir(room.id));
        plugin.ledgers().delete(room.id);
        plugin.holograms().remove(room);
        plugin.stations().forget(room);
        plugin.plots().save();
        invalidate();
        plugin.journal().add(room.id, null, "администратор", Journal.Action.HOTEL, "Номер удалён");
        return null;
    }
}
