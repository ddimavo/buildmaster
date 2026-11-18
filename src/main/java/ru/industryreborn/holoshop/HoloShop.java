package ru.industryreborn.holoshop;

import java.util.ArrayList;
import java.util.List;
import ru.industryreborn.holo.ClickZones;
import ru.industryreborn.holo.Holo;

/** Витрина-голограмма предприятия: место в мире, привязка к участку и текущее состояние показа. */
public final class HoloShop {

    public final String id;
    public final String plotId;
    public final String world;
    public final double x;
    public final double y;
    public final double z;
    /** Куда смотрит витрина (в градусах, кратно 90): в эту сторону идут покупатели. */
    public final float yaw;

    // ---- состояние показа
    int page;
    long lastClick;
    String layoutKey = "";
    final List<Holo> parts = new ArrayList<>();
    final List<ClickZones.Zone> zones = new ArrayList<>();
    final List<Runnable> refresh = new ArrayList<>();   // обновление текстов (название, цена, остаток) без пересоздания голограмм
    final List<org.bukkit.Material> pageItems = new ArrayList<>();
    /** Вторая доска с обратной стороны: null - как в config.yml (holo-shop.two-sided). */
    Boolean backSide;

    public HoloShop(String id, String plotId, String world, double x, double y, double z, float yaw) {
        this.id = id;
        this.plotId = plotId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    public Boolean backSide() {
        return backSide;
    }

    public int page() {
        return page;
    }

    public boolean shown() {
        return !parts.isEmpty();
    }
}
