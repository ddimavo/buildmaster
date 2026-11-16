package ru.buildmaster.service;

import ru.buildmaster.model.Category;
import ru.buildmaster.model.Station;

/** Проверка лимита станков в здании данного типа. */
public final class StationLimits {

    public record Result(boolean allowed, String message) {
        static final Result OK = new Result(true, null);
    }

    private StationLimits() { }

    /**
     * @param groupCount сколько станков с тем же ключом лимита уже стоит
     * @param limit      лимит из config: -1 без ограничений, 0 запрещено, N не больше N
     */
    public static Result check(Category category, Station station, int groupCount, int limit) {
        if (limit < 0) {
            return Result.OK;
        }
        String where = "В здании типа «" + category.title() + "»";
        if (limit == 0) {
            return new Result(false, where + " нельзя ставить: " + station.title() + ". Для станков нужна мастерская.");
        }
        if (groupCount >= limit) {
            return new Result(false, where + " можно не больше " + limit + " шт. (" + station.title()
                    + "). Для большего количества станков нужна мастерская.");
        }
        return Result.OK;
    }
}
