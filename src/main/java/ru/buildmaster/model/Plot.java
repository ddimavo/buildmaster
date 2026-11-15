package ru.buildmaster.model;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Участок: область мира со зданием, которое можно купить или арендовать. */
public final class Plot {

    public enum Status { FREE, OWNED, RENTED }

    /** Именованная часть здания (фасад, крыша, интерьер...), внутри участка. */
    public record Zone(String name, Box box, String icon) { }

    public final String id;
    public final String world;
    public final Box box;

    /** Цена покупки; отрицательная - участок не продаётся. */
    public double price = -1;
    /** Аренда за сутки; отрицательная - участок не сдаётся. */
    public double rentPerDay = -1;

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
    /** Кому вернуть предметы при сбросе здания. */
    public UUID restoreFor;

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
