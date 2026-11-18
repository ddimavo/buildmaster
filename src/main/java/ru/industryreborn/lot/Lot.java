package ru.industryreborn.lot;

import java.util.UUID;
import ru.industryreborn.holo.ClickZones;
import ru.industryreborn.holo.Holo;

/** Лот: участок, выставленный на аукцион. Деньги последней ставки удерживаются у плагина (списаны со ставившего). */
public final class Lot {

    final String plotId;
    /** Продавец: игрок, продающий свой участок; null - продаёт администрация (деньги целиком в казну). */
    public final UUID seller;
    public final String sellerName;
    public final double start;
    public final double step;
    /** Цена мгновенного выкупа; 0 - выкупа нет. */
    public final double buyout;
    public final long startedAt;
    public long endAt;
    public double bid;
    public UUID bidder;
    public String bidderName;
    public int bids;
    /** Где висит голограмма лота. */
    public String world;
    public double x;
    public double y;
    public double z;

    // ---- состояние показа
    transient Holo holo;
    transient ClickZones.Zone zone;
    transient String shownText = "";

    public String plotId() {
        return plotId;
    }

    public Lot(String plotId, UUID seller, String sellerName, double start, double step, double buyout, long startedAt, long endAt) {
        this.plotId = plotId;
        this.seller = seller;
        this.sellerName = sellerName;
        this.start = start;
        this.step = step;
        this.buyout = buyout;
        this.startedAt = startedAt;
        this.endAt = endAt;
    }

    /** Наименьшая допустимая следующая ставка. */
    public double minNext() {
        return bidder == null ? start : round(bid + step);
    }

    public boolean hasBids() {
        return bidder != null;
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
