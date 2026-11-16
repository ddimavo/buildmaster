package ru.buildmaster.model;

import java.util.EnumSet;

/** Вакансия на участке: должность, число мест, ставка за отработанный блок времени и процент от дохода. */
public final class Vacancy {

    public final int id;
    public final String plotId;
    public final JobRole role;
    public int slots;
    /** Плата за каждый полный блок отработанного времени (jobs.work-block-minutes). */
    public double rate;
    /** Процент от дохода предприятия (аренда номеров отеля, продажи магазина); 0 - без процента. */
    public double percent;
    public final EnumSet<Plot.Perm> perms;
    public boolean open = true;
    public final long createdAt;

    public Vacancy(int id, String plotId, JobRole role, int slots, double rate, double percent,
                   EnumSet<Plot.Perm> perms, long createdAt) {
        this.id = id;
        this.plotId = plotId;
        this.role = role;
        this.slots = slots;
        this.rate = rate;
        this.percent = percent;
        this.perms = perms;
        this.createdAt = createdAt;
    }
}
