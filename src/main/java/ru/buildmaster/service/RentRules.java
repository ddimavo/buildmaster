package ru.buildmaster.service;

/** Правила срока аренды (чистая логика без обращения к серверу). */
public final class RentRules {

    private RentRules() { }

    /** Остаток аренды в миллисекундах (не меньше 0). */
    public static long remaining(long now, long rentedUntil) {
        return Math.max(0, rentedUntil - now);
    }

    /**
     * Сколько целых суток можно ещё оплатить, чтобы суммарный остаток не превысил максимум.
     * Пример: максимум 14, прошло 2 суток из 14 - можно продлить на 2.
     */
    public static int maxExtendDays(long now, long rentedUntil, long dayMillis, int maxDays) {
        long room = maxDays * dayMillis - remaining(now, rentedUntil);
        return (int) Math.max(0, room / dayMillis);
    }

    /** Допустимый срок новой аренды. */
    public static boolean validNewRent(int days, int maxDays) {
        return days >= 1 && days <= maxDays;
    }

    public static double cost(double rentPerDay, int days) {
        return rentPerDay * days;
    }

    /** Лимит цены суточной аренды для игрока-арендодателя; max <= 0 - без лимита. */
    public static boolean rentPriceAllowed(double rentPerDay, double max) {
        return max <= 0 || rentPerDay <= max;
    }
}
