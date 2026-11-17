package ru.industryreborn.model;

import java.util.UUID;

/** Отклик игрока на вакансию. */
public final class JobApplication {

    public final int id;
    public final int vacancyId;
    public final String plotId;
    public final UUID applicant;
    public final String applicantName;
    public final long createdAt;

    public JobApplication(int id, int vacancyId, String plotId, UUID applicant, String applicantName, long createdAt) {
        this.id = id;
        this.vacancyId = vacancyId;
        this.plotId = plotId;
        this.applicant = applicant;
        this.applicantName = applicantName;
        this.createdAt = createdAt;
    }
}
