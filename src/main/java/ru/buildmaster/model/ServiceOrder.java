package ru.buildmaster.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Заказ клиента в мастерской. Материалы и деньги находятся в эскроу до выдачи или возврата. */
public final class ServiceOrder {

    public enum State {
        /** Ждёт своей очереди на станке. */
        PENDING,
        /** Выполняется. */
        ACTIVE,
        /** Готов, ждёт, пока клиент заберёт. */
        READY
    }

    public final UUID id;
    public final String plotId;
    public final UUID client;
    public final String clientName;
    public final String offerKey;
    /** Сколько единиц работы в заказе (предметов или партий). */
    public final int units;
    /** Деньги клиента в эскроу: уйдут на счёт мастерской при выдаче, вернутся клиенту при отмене. */
    public final double paid;
    public final long createdAt;

    public State state = State.PENDING;
    public int unitsDone;
    /** Накопленное время работы в секундах (идёт только при электричестве). */
    public double progressSeconds;
    public long readyAt;

    /** Материалы клиента (ItemStack.serializeAsBytes) и готовый результат. */
    public final List<byte[]> input = new ArrayList<>();
    public final List<byte[]> output = new ArrayList<>();

    public ServiceOrder(UUID id, String plotId, UUID client, String clientName, String offerKey, int units,
                        double paid, long createdAt) {
        this.id = id;
        this.plotId = plotId;
        this.client = client;
        this.clientName = clientName;
        this.offerKey = offerKey;
        this.units = units;
        this.paid = paid;
        this.createdAt = createdAt;
    }

    /** Короткий номер для команд: первые 8 символов идентификатора. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
