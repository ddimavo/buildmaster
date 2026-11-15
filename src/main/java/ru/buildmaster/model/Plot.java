package ru.buildmaster.model;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Участок: область мира со зданием, которое можно купить или арендовать. */
public final class Plot {

    public enum Status { FREE, OWNED, RENTED }

    /** FULL - здание полностью возвращается к исходному виду; ITEMS_ONLY - только забираются вещи игроков. */
    public enum RestoreMode { FULL, ITEMS_ONLY }

    /** Именованная часть здания (фасад, крыша, интерьер...), внутри участка. */
    public record Zone(String name, Box box, String icon) { }

    public final String id;
    public final String world;
    public final Box box;

    /** Цена покупки; отрицательная - участок не продаётся. */
    public double price = -1;
    /** Аренда за сутки; отрицательная - участок не сдаётся. */
    public double rentPerDay = -1;

    /** Тип участка (дом, магазин...). */
    public Category category = Category.SHOP;
    /** Администратор, создавший участок: он остаётся высшим владельцем. */
    public UUID createdBy;
    /** Одобренные администратором название и описание (публикуются на голограмме). */
    public String name;
    public String description;
    /** Точка голограммы (центр). */
    public boolean hasHolo;
    public double holoX, holoY, holoZ;
    /** Сбрасывать здание при освобождении (решает владелец). */
    public boolean resetOnRelease = true;
    /** До какого момента длится отсрочка платежа; 0 - отсрочки нет. В это время участок заморожен. */
    public long graceUntil;

    public Status status = Status.FREE;
    public UUID owner;
    public String ownerName;
    public long rentedUntil;
    public boolean autoRenew;

    public final Set<UUID> trusted = new HashSet<>();
    public final Map<String, Zone> zones = new LinkedHashMap<>();

    public boolean hasSpawn;
    public double spawnX, spawnY, spawnZ;
    public float spawnYaw, spawnPitch;

    /** Здание нужно привести к исходному виду (сохраняется, чтобы пережить перезапуск). */
    public boolean restorePending;
    /** Кому вернуть предметы, не принадлежащие никому конкретно (из исходных контейнеров и т.п.). */
    public UUID restoreFor;
    public RestoreMode restoreMode = RestoreMode.FULL;
    /** Вещи арестовываются (изъятие участка) - до решения администратора. */
    public boolean restoreArrest;
    public String restoreReason;
    public String restoreBatch;

    /** Идёт операция над блоками (сброс, замена, снимок) - участок временно заблокирован. */
    public transient boolean busy;
    public transient boolean warned;
    /** Неудачные попытки сброса подряд (после трёх ждём перезапуска). */
    public transient int failures;

    public Plot(String id, String world, Box box) {
        this.id = id;
        this.world = world;
        this.box = box;
    }

    public boolean isFrozen() {
        return graceUntil > System.currentTimeMillis();
    }

    public String displayName() {
        return name != null && !name.isBlank() ? name : category.title() + " " + id;
    }

    public boolean isFree() {
        return status == Status.FREE;
    }

    public boolean isOwner(UUID uuid) {
        return owner != null && owner.equals(uuid);
    }

    public boolean isMember(UUID uuid) {
        return isOwner(uuid) || trusted.contains(uuid);
    }
}
