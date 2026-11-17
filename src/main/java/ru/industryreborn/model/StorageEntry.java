package ru.industryreborn.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Партия вещей в хранилище игрока (например, всё, что вернулось с одного участка). */
public final class StorageEntry {

    public enum State {
        /** Игрок может забрать. */
        AVAILABLE,
        /** Арестовано до решения администратора. */
        ARRESTED,
        /** Передано на аукцион (интеграция позже). */
        AUCTION
    }

    public final UUID id;
    public final String plotId;
    public final long createdAt;
    /** Идентификатор партии: повторные добавления в рамках одного сброса попадают в одну запись. */
    public final String batch;
    public State state;
    public String reason;
    /** Предметы в виде ItemStack.serializeAsBytes. */
    public final List<byte[]> stacks = new ArrayList<>();

    public StorageEntry(UUID id, String plotId, long createdAt, String batch, State state, String reason) {
        this.id = id;
        this.plotId = plotId;
        this.createdAt = createdAt;
        this.batch = batch;
        this.state = state;
        this.reason = reason;
    }

    /** Хранилище очищается через заданное число суток; арестованное и аукционное не истекает само. */
    public boolean expired(long now, int storageDays) {
        return state == State.AVAILABLE && storageDays > 0 && now - createdAt > storageDays * 86_400_000L;
    }
}
