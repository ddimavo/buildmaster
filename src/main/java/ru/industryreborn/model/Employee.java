package ru.industryreborn.model;

import java.util.UUID;

/** Работник предприятия: нанят по вакансии, получает зарплату со счёта участка. */
public final class Employee {

    public final UUID player;
    public String name;
    public final String plotId;
    public final int vacancyId;
    public final long hiredAt;
    /** Начислено, но ещё не выплачено (выплата идёт со счёта предприятия, когда на нём есть деньги). */
    public double accrued;
    public double totalPaid;
    /** Минуты в текущем блоке и за сегодня. */
    public int blockMinutes;
    public int todayMinutes;
    public long day;

    public Employee(UUID player, String name, String plotId, int vacancyId, long hiredAt) {
        this.player = player;
        this.name = name;
        this.plotId = plotId;
        this.vacancyId = vacancyId;
        this.hiredAt = hiredAt;
    }
}
