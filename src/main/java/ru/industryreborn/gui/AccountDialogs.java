package ru.industryreborn.gui;

import java.util.List;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.RentRules;
import ru.industryreborn.service.TaxRules;
import ru.industryreborn.util.Text;
import org.bukkit.entity.Player;

/** Окна со степпером для денег и сроков: цена и остаток пересчитываются при каждом нажатии кнопки. */
final class AccountDialogs {

    private AccountDialogs() { }

    /** Продление аренды: срок в сутках, стоимость и остаток денег для каждого значения. */
    static void extend(IndustryRebornPlugin plugin, Player player, Plot plot, Runnable after) {
        long now = System.currentTimeMillis();
        int allowed = RentRules.maxExtendDays(now, plot.rentedUntil, plugin.service().dayMillis(), plugin.service().maxRentDays(plot));
        if (allowed < 1) {
            Text.err(player, "Срок аренды уже максимальный (" + plugin.service().maxRentDays(plot) + " сут.).");
            after.run();
            return;
        }
        double cash = plugin.economy().balance(player);
        var stepper = new DialogService.Stepper("Продлить аренду", 1, allowed, Math.min(7, allowed), 1, 7,
                List.of(1.0, 3.0, 7.0, 14.0),
                days -> {
                    double cost = RentRules.cost(plot.rentPerDay, (int) Math.round(days));
                    long until = Math.max(plot.rentedUntil, now) + (long) Math.round(days) * plugin.service().dayMillis();
                    List<String> lines = new java.util.ArrayList<>();
                    lines.add("<white>Участок № " + Text.esc(plot.id) + " · " + Text.esc(plot.displayName()));
                    lines.add("<gray>Срок: <white>" + Math.round(days) + " сут. <gray>(можно до " + allowed + ")");
                    lines.add("<gray>Стоимость: <gold>" + plugin.economy().format(cost) + " <gray>(" + plugin.economy().format(plot.rentPerDay) + " за сутки)");
                    lines.add("<gray>Аренда до: <white>" + Text.date(until));
                    lines.add(cash >= cost ? "<gray>Ваши деньги: <white>" + plugin.economy().format(cash) + " <gray>→ <white>" + plugin.economy().format(cash - cost)
                            : "<red>Не хватает " + plugin.economy().format(cost - cash));
                    return lines;
                },
                days -> cash >= RentRules.cost(plot.rentPerDay, (int) Math.round(days)) - 1e-9,
                d -> Math.round(d) + " сут.", "Продлить");
        plugin.dialogs().stepper(player, stepper, days -> {
            plugin.service().extend(player, plot, (int) Math.round(days));
            after.run();
        }, null, after);
    }

    /** Пополнение или снятие со счёта предприятия: сумма кнопками, комиссия банка и остатки пересчитываются. */
    static void money(IndustryRebornPlugin plugin, Player player, Plot plot, boolean withdraw, Runnable after) {
        plugin.power().touch(plot);
        double cash = plugin.economy().balance(player);
        double reserve = plugin.power().isBilled(plot) ? plot.account.ratePerDay() : 0;
        double available = Math.max(0, plot.account.rawBalance() - reserve);
        double max = Math.floor(withdraw ? available : cash);
        if (max < 1) {
            Text.err(player, withdraw ? "На счёте нечего снимать" + (reserve > 0 ? " (резерв на сутки электричества: " + plugin.economy().format(reserve) + ")." : ".")
                    : "У вас нет денег для пополнения.");
            after.run();
            return;
        }
        double fee = withdraw ? plugin.bank().withdrawCommission() : plugin.bank().depositCommission();
        double[] scale = max >= 100_000 ? new double[] {1000, 10_000} : max >= 5000 ? new double[] {100, 1000} : max >= 500 ? new double[] {10, 100} : new double[] {1, 10};
        double account = plugin.power().balance(plot);
        var stepper = new DialogService.Stepper(withdraw ? "Снять со счёта" : "Пополнить счёт", 1, max, Math.min(max, scale[1]), scale[0], scale[1],
                List.of(Math.max(1, Math.floor(max / 4)), Math.max(1, Math.floor(max / 2)), max),
                v -> {
                    double commission = TaxRules.percentOf(v, fee);
                    List<String> lines = new java.util.ArrayList<>();
                    lines.add("<white>Участок № " + Text.esc(plot.id) + " · " + Text.esc(plot.displayName()));
                    lines.add("<gray>Сумма: <gold>" + plugin.economy().format(v));
                    if (commission > 0) {
                        lines.add("<gray>Комиссия банка " + fee + "%: <white>" + plugin.economy().format(commission));
                    }
                    if (withdraw) {
                        lines.add("<gray>Вы получите: <white>" + plugin.economy().format(v - commission));
                        lines.add("<gray>На счёте останется: <white>" + plugin.economy().format(account - v));
                    } else {
                        lines.add("<gray>На счёт поступит: <white>" + plugin.economy().format(v - commission));
                        lines.add("<gray>Ваши деньги: <white>" + plugin.economy().format(cash) + " <gray>→ <white>" + plugin.economy().format(cash - v));
                    }
                    return lines;
                },
                v -> v >= 1 && v <= max + 1e-9, v -> plugin.economy().format(v), withdraw ? "Снять" : "Внести");
        plugin.dialogs().stepper(player, stepper, v -> {
            if (withdraw) {
                plugin.power().withdraw(player, plot, v);
            } else {
                plugin.power().deposit(player, plot, v);
            }
            after.run();
        }, () -> plugin.dialogs().amount(player, withdraw ? "Снять со счёта" : "Пополнить счёт",
                List.of("<gray>Можно до " + plugin.economy().format(max)),
                withdraw ? "Сумма, чтобы снять" : "Сумма, чтобы внести", v -> {
                    if (withdraw) {
                        plugin.power().withdraw(player, plot, v);
                    } else {
                        plugin.power().deposit(player, plot, v);
                    }
                    after.run();
                }, after), after);
    }
}
