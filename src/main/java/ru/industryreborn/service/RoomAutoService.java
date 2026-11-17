package ru.industryreborn.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/**
 * Автоматическое создание номера по двери: администратор смотрит на дверь номера, плагин находит замкнутое помещение за ней,
 * подсвечивает его частицами и ждёт подтверждения или отмены.
 */
public final class RoomAutoService {

    /** Предложенный номер, ожидающий подтверждения. */
    public record Pending(UUID admin, String hotelId, String roomId, double rate, Box box, int[] door, int cells,
                          long createdAt) { }

    private final IndustryRebornPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private Function<Player, Block> targetFinder = p -> p.getTargetBlockExact(6);

    public RoomAutoService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    /** Только для тестов: подмена определения блока, на который смотрит игрок. */
    public void setTargetFinder(Function<Player, Block> finder) {
        this.targetFinder = finder == null ? p -> p.getTargetBlockExact(6) : finder;
    }

    private long ttlMillis() {
        return plugin.getConfig().getLong("hotel-auto.confirm-seconds", 120) * 1000L;
    }

    /** Ожидающее подтверждения предложение игрока (просроченные удаляются). */
    public Pending pending(Player player) {
        Pending p = pending.get(player.getUniqueId());
        if (p != null && System.currentTimeMillis() - p.createdAt() > ttlMillis()) {
            pending.remove(player.getUniqueId());
            return null;
        }
        return p;
    }

    public boolean cancel(Player player) {
        return pending.remove(player.getUniqueId()) != null;
    }

    private java.util.function.Predicate<Block> passable = Block::isPassable;

    /** Только для тестов: MockBukkit не реализует Block.isPassable. */
    public void setPassable(java.util.function.Predicate<Block> passable) {
        this.passable = passable == null ? Block::isPassable : passable;
    }

    private boolean fillable(Block b) {
        Material type = b.getType();
        return type.isAir() || type.name().endsWith("_CARPET") || passable.test(b);
    }

    /** Ищет номер за дверью, на которую смотрит игрок. Возвращает сообщение об ошибке или null. */
    public String analyze(Player player, String roomId, double rate) {
        Block target = targetFinder.apply(player);
        if (target == null) {
            return "Наведите взгляд на дверь номера (не дальше 6 блоков).";
        }
        if (!Tag.DOORS.isTagged(target.getType())) {
            return "Нужно смотреть на дверь, а вы смотрите на «" + target.getType().name() + "».";
        }
        BlockData data = target.getBlockData();
        if (data instanceof Bisected bisected && bisected.getHalf() == Bisected.Half.TOP) {
            target = target.getRelative(0, -1, 0);
            data = target.getBlockData();
        }
        if (!(data instanceof Directional directional)) {
            return "Не удалось определить сторону двери.";
        }
        World world = target.getWorld();
        Plot at = plugin.plots().at(world.getName(), target.getX(), target.getY(), target.getZ());
        if (at == null || at.category != Category.HOTEL) {
            return "Дверь должна находиться внутри отеля" + (at == null ? "." : " (здесь участок «" + at.id + "»: " + at.category.title() + ").");
        }
        Plot hotel = at;
        var face = directional.getFacing();
        int fx = face.getModX();
        int fz = face.getModZ();
        Block a = target.getRelative(fx, 0, fz);
        Block b = target.getRelative(-fx, 0, -fz);
        // игрок стоит в коридоре, номер - с противоположной стороны двери
        var loc = player.getLocation();
        double da = a.getLocation().add(0.5, 0, 0.5).distanceSquared(loc);
        double db = b.getLocation().add(0.5, 0, 0.5).distanceSquared(loc);
        Block start = da >= db ? a : b;
        int maxCells = plugin.getConfig().getInt("hotel-auto.max-cells", 4000);
        RoomScanner.Result scan = RoomScanner.scan((x, y, z) -> fillable(world.getBlockAt(x, y, z)), start.getX(), start.getY(),
                start.getZ(), hotel.box, maxCells);
        if (!scan.ok()) {
            return scan.error();
        }
        String error = plugin.hotels().validateRoomBox(hotel, scan.box(), roomId);
        if (error != null) {
            return error;
        }
        Pending p = new Pending(player.getUniqueId(), hotel.id, roomId, rate, scan.box(),
                new int[] {target.getX(), target.getY(), target.getZ()}, scan.cells(), System.currentTimeMillis());
        pending.put(player.getUniqueId(), p);
        Text.send(player, "<yellow>Найден номер <white>" + Text.esc(hotel.id + "-" + roomId) + "</white>: размер <white>"
                + scan.box().describe() + "</white>, объём <white>" + scan.box().volume() + "</white> блоков. "
                + "Область подсвечена частицами. <click:run_command:/district room confirm><green>[Подтвердить]</green></click> "
                + "<click:run_command:/district room cancel><red>[Отменить]</red></click> "
                + "<gray>(или /district room confirm | cancel)");
        return null;
    }

    /** Создаёт ожидающий номер. Возвращает сообщение об ошибке или null. */
    public String confirm(Player player) {
        Pending p = pending(player);
        if (p == null) {
            return "Нет номера, ожидающего подтверждения. Посмотрите на дверь и выполните /district room auto <номер> <цена>.";
        }
        Plot hotel = plugin.plots().get(p.hotelId());
        if (hotel == null) {
            pending.remove(player.getUniqueId());
            return "Отель больше не существует.";
        }
        // за время ожидания область могла измениться: проверяем заново
        String error = plugin.hotels().validateRoomBox(hotel, p.box(), p.roomId());
        if (error != null) {
            pending.remove(player.getUniqueId());
            return error;
        }
        pending.remove(player.getUniqueId());
        Text.send(player, "<yellow>Создаю номер...");
        plugin.service().createRoom(player, hotel, p.roomId(), p.box(), p.rate(), List.of(p.door()), ok -> { });
        return null;
    }
}
