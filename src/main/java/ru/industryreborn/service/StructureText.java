package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.List;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Employee;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/** Панель характеристик предприятия для управляющего: потребление, станки, продукция и общая статистика. */
public final class StructureText {

    private StructureText() { }

    private static String stat(String label, String value) {
        return "<gray>" + label + ": <white>" + value;
    }

    public static List<String> lines(IndustryRebornPlugin plugin, Plot plot) {
        var money = (java.util.function.DoubleFunction<String>) plugin.economy()::format;
        List<String> lines = new ArrayList<>();
        lines.add("<aqua><bold>Панель управляющего");
        switch (plot.category) {
            case WORKSHOP -> workshop(plugin, plot, money, lines);
            case HOTEL -> hotel(plugin, plot, money, lines);
            case ROOM -> room(plugin, plot, money, lines);
            case BANK -> bank(plugin, money, lines);
            case POWER_PLANT -> plant(plugin, plot, lines);
            case HOUSE -> house(plugin, plot, lines);
            default -> business(plugin, plot, money, lines);
        }
        return lines;
    }

    private static void rentLine(Plot plot, List<String> lines) {
        if (plot.status == Plot.Status.RENTED) {
            lines.add(stat("Аренда до", Text.date(plot.rentedUntil) + " (осталось " + Text.duration(plot.rentedUntil - System.currentTimeMillis()) + ")"
                    + (plot.autoRenew ? ", автопродление" : "")));
        } else if (plot.status == Plot.Status.OWNED) {
            lines.add(stat("Участок", "куплен"));
        }
    }

    private static void staffLines(IndustryRebornPlugin plugin, Plot plot, java.util.function.DoubleFunction<String> money,
                                   List<String> lines) {
        if (!plugin.jobs().enabled() || !plugin.jobs().categoryAllowed(plot.category)) {
            return;
        }
        List<Employee> staff = plugin.jobs().employeesOf(plot.id);
        double due = staff.stream().mapToDouble(e -> e.accrued).sum();
        lines.add(stat("Работники", staff.size() + (due > 0 ? " · к выплате " + money.apply(due) : "")
                + " · вакансий " + plugin.jobs().vacanciesOf(plot.id).size()));
    }

    private static void accountLines(IndustryRebornPlugin plugin, Plot plot, java.util.function.DoubleFunction<String> money,
                                     List<String> lines) {
        plugin.power().touch(plot);
        lines.add(stat("Счёт предприятия", money.apply(plugin.power().balance(plot))));
        if (plot.taxDebt > 1e-9) {
            lines.add("<red>Долг по налогу: " + money.apply(plot.taxDebt));
        }
    }

    private static void workshop(IndustryRebornPlugin plugin, Plot plot, java.util.function.DoubleFunction<String> money,
                                 List<String> lines) {
        var power = plugin.power();
        power.touch(plot);
        var counts = plugin.stations().tariffCounts(plot);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        double units = power.unitsFor(plot);
        boolean usage = power.mode() == PowerService.Mode.USAGE;
        lines.add("<yellow>— Электричество —");
        lines.add(stat("Потребление", String.format(java.util.Locale.ROOT, "%.2f", units) + " V/сут"));
        lines.add(stat("Стоимость", usage ? "по использованию станков" : money.apply(plot.account.ratePerDay()) + " / сутки"));
        lines.add(stat("Баланс счёта", money.apply(power.balance(plot))
                + (!usage && plot.account.ratePerDay() > 0 && power.isPowered(plot)
                ? ", хватит на " + Text.duration(plot.account.secondsLeft(power.nowSec()) * 1000L) : "")));
        String off = plugin.grid().offReason(plot);
        Plot plant = plugin.grid().plantOf(plot);
        lines.add(stat("Сеть", off != null ? "<red>" + off : plant == null ? "станций нет" : plant.displayName()
                + (plant.plantMultiplier > 1.0 ? " <red>(перегрузка ×" + plant.plantMultiplier + ")" : "")));
        lines.add("<yellow>— Станки —");
        lines.add(stat("Всего", total + (total == 0 ? "" : " (плавилен " + counts.getOrDefault("smelter", 0) + ", наковален "
                + counts.getOrDefault("anvil", 0) + ", стоек " + counts.getOrDefault("alchemy", 0) + ", столов "
                + counts.getOrDefault("enchanting", 0) + ")")));
        var stats = power.stats().total(plot.id);
        lines.add(stat("Операций / потрачено", stats.operations() + " / " + money.apply(stats.spent())));
        lines.add("<yellow>— Производство —");
        var factory = plugin.factory();
        int product = factory.views(plot).stream().mapToInt(ProductionService.View::output).sum();
        long working = factory.views(plot).stream().filter(v -> v.status() == ProductionService.Status.WORKING).count();
        lines.add(stat("Станков с заданием", factory.assignedCount(plot) + " из " + factory.slots(plot)
                + " · работают " + working + " · уровень " + plot.level));
        lines.add(stat("Склад сырья", factory.stockTotal(plot) + " / " + factory.stockCapacity(plot)));
        lines.add(stat("Продукция в буферах", String.valueOf(product)));
        lines.add(stat("Сундуки сырья / продукции", plot.rawChests.size() + " / " + plot.productChests.size()));
        long orders = plugin.orders().ofPlot(plot.id).size();
        lines.add(stat("Заказов услуг", String.valueOf(orders)));
        staffLines(plugin, plot, money, lines);
        lines.add(stat("Выручка всего", money.apply(plot.revenue)));
        rentLine(plot, lines);
    }

    private static void business(IndustryRebornPlugin plugin, Plot plot, java.util.function.DoubleFunction<String> money,
                                 List<String> lines) {
        accountLines(plugin, plot, money, lines);
        int stations = plugin.stations().tariffCounts(plot).values().stream().mapToInt(Integer::intValue).sum();
        lines.add(stat("Станков", String.valueOf(stations)));
        lines.add(stat("Выручка всего", money.apply(plot.revenue)));
        staffLines(plugin, plot, money, lines);
        if (!plot.grants.isEmpty()) {
            lines.add(stat("Игроков с правами", String.valueOf(plot.grants.size())));
        }
        rentLine(plot, lines);
    }

    private static void house(IndustryRebornPlugin plugin, Plot plot, List<String> lines) {
        lines.add(stat("Игроков с правами", String.valueOf(plot.grants.size())));
        lines.add(stat("Публичный доступ", plot.publicPerms.isEmpty() ? "нет"
                : String.join(", ", plot.publicPerms.stream().map(Plot.Perm::title).toList())));
        rentLine(plot, lines);
    }

    private static void hotel(IndustryRebornPlugin plugin, Plot plot, java.util.function.DoubleFunction<String> money,
                              List<String> lines) {
        List<Plot> rooms = plugin.hotels().rooms(plot);
        long occupied = rooms.stream().filter(r -> !r.isFree()).count();
        lines.add(stat("Номера", occupied + " занято из " + rooms.size()));
        double daily = rooms.stream().filter(r -> !r.isFree()).mapToDouble(r -> Math.max(0, r.rentPerDay)).sum();
        lines.add(stat("Доход в сутки (по занятым)", money.apply(daily)));
        accountLines(plugin, plot, money, lines);
        lines.add(stat("Выручка всего", money.apply(plot.revenue)));
        staffLines(plugin, plot, money, lines);
        rentLine(plot, lines);
    }

    private static void room(IndustryRebornPlugin plugin, Plot plot, java.util.function.DoubleFunction<String> money,
                             List<String> lines) {
        lines.add(stat("Гость", plot.ownerName == null ? "свободен" : plot.ownerName));
        lines.add(stat("Цена", money.apply(Math.max(0, plot.rentPerDay)) + " / сутки"));
        rentLine(plot, lines);
    }

    private static void bank(IndustryRebornPlugin plugin, java.util.function.DoubleFunction<String> money, List<String> lines) {
        double deposits = 0;
        double debts = 0;
        int accounts = 0;
        for (Plot p : plugin.plots().all()) {
            if (p.isFree() || p.owner == null || p.category.isAdminOnly()) {
                continue;
            }
            double balance = p.account.rawBalance();
            if (balance > 1e-9) {
                accounts++;
                deposits += balance;
            }
            debts += p.taxDebt;
        }
        lines.add(stat("Счетов с деньгами", String.valueOf(accounts)));
        lines.add(stat("Сумма на счетах", money.apply(deposits)));
        lines.add(stat("Долги по налогам", money.apply(debts)));
        lines.add(stat("Казна", money.apply(plugin.treasury().balance())));
    }

    private static void plant(IndustryRebornPlugin plugin, Plot plot, List<String> lines) {
        double demand = plugin.grid().demand(plot);
        lines.add(stat("Мощность", String.format(java.util.Locale.ROOT, "%.0f", plot.plantCapacity) + " V/сут"));
        lines.add(stat("Нагрузка", String.format(java.util.Locale.ROOT, "%.1f", demand) + " V/сут ("
                + Math.round(plugin.grid().load(plot) * 100) + "%)"));
        lines.add(stat("Подключено", plugin.grid().connected(plot).size() + " предприятий"));
        lines.add(plot.plantMultiplier > 1.0 ? "<red>Перегрузка: тариф ×" + plot.plantMultiplier
                : "<green>Нагрузка в норме");
    }
}
