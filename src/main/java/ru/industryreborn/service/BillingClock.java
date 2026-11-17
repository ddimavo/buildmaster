package ru.industryreborn.service;

/**
 * Биллинговые часы: считают время работы сервера, а не календарное.
 * Простой сервера (выключен или завис) не оплачивается: за один шаг прибавляется не больше maxStep.
 */
public final class BillingClock {

    private long billingMillis;
    private long lastWallMillis;
    private final long maxStepMillis;

    public BillingClock(long billingMillis, long nowWallMillis, long maxStepMillis) {
        this.billingMillis = billingMillis;
        this.lastWallMillis = nowWallMillis;
        this.maxStepMillis = Math.max(1, maxStepMillis);
    }

    public void advance(long nowWallMillis) {
        long elapsed = Math.max(0, nowWallMillis - lastWallMillis);
        billingMillis += Math.min(elapsed, maxStepMillis);
        lastWallMillis = nowWallMillis;
    }

    public long millis() {
        return billingMillis;
    }

    public long seconds() {
        return billingMillis / 1000;
    }
}
