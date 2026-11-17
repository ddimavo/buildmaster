package ru.industryreborn.plan;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.industryreborn.service.StatsService;

/** Данные для Plan, собранные на основном потоке: Plan читает их из своих потоков. */
public record PlanSnapshot(StatsService.Stats stats, Map<UUID, PlayerView> players, List<TreasuryRow> treasury) {

    /** Строка таблицы казны. */
    public record TreasuryRow(String time, String amount, String reason) { }

    /** Показатели игрока. */
    public record PlayerView(int plots, int businesses, double revenue, String nearestRent, int jobs, int ordersActive, int ordersReady,
                             int stored, double accounts, List<PlotRow> plotRows) { }

    /** Строка таблицы участков игрока. */
    public record PlotRow(String name, String type, String until, String account) { }

    public double value(String section, String metric) {
        StatsService.Metric m = stats.metric(section, metric);
        return m == null ? 0 : m.value();
    }

    public String text(String section, String metric) {
        StatsService.Metric m = stats.metric(section, metric);
        return m == null ? "-" : m.text();
    }
}
