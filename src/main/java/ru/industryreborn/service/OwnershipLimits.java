package ru.industryreborn.service;

import java.util.Map;
import ru.industryreborn.model.Category;

/** Лимиты владения: общий и по категориям (0 - без ограничений). */
public final class OwnershipLimits {

    public record Result(boolean allowed, String reason) {
        static final Result OK = new Result(true, null);
    }

    private OwnershipLimits() { }

    public static Result check(int totalOwned, Map<Category, Integer> ownedByCategory, Category wanted,
                               int totalLimit, int categoryLimit) {
        if (totalLimit > 0 && totalOwned >= totalLimit) {
            return new Result(false, "Достигнут общий лимит участков: " + totalLimit + ".");
        }
        int inCategory = ownedByCategory.getOrDefault(wanted, 0);
        if (categoryLimit > 0 && inCategory >= categoryLimit) {
            return new Result(false, "Достигнут лимит категории «" + wanted.title() + "»: " + categoryLimit + ".");
        }
        return Result.OK;
    }
}
