package ru.industryreborn.model;

import java.util.UUID;

/** Заявка игрока на публикацию названия или описания участка. */
public final class Request {

    public enum Type {
        NAME("Название"), DESCRIPTION("Описание"), PLOT("Участок игрока"), HOLO("Позиция голограммы"), ENCHANT("Зачарование для станка"), ROOM_CLASS("Класс номера"), RECIPE("Рецепт станка");

        private final String title;

        Type(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public enum Status { PENDING, APPROVED, REJECTED }

    public final UUID id;
    public final Type type;
    public final String plotId;
    public final UUID requester;
    public final String requesterName;
    public final String text;
    public final long createdAt;
    public Status status = Status.PENDING;
    public String reason;
    public String decidedBy;
    public long decidedAt;
    /** Игрок уже узнал о решении (оно показывается при входе, если он был оффлайн). */
    public boolean notified;

    public Request(UUID id, Type type, String plotId, UUID requester, String requesterName, String text, long createdAt) {
        this.id = id;
        this.type = type;
        this.plotId = plotId;
        this.requester = requester;
        this.requesterName = requesterName;
        this.text = text;
        this.createdAt = createdAt;
    }
}
