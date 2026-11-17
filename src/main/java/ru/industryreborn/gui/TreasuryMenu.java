package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Treasury;
import ru.industryreborn.service.TreasuryPayouts;
import ru.industryreborn.util.Text;

/** Казна (право industryreborn.bank): баланс, отчёт, выплаты (субсидия, приз) и последние операции. */
public final class TreasuryMenu extends Menu {

    public TreasuryMenu(IndustryRebornPlugin plugin, Player player) {
        super(plugin, player, "<dark_gray>Казна", 5);
        if (!player.hasPermission("industryreborn.bank")) {
            set(22, Text.icon(Material.BARRIER, "<red>Недостаточно прав"));
            fill(Material.GRAY_STAINED_GLASS_PANE);
            return;
        }
        var money = (java.util.function.DoubleFunction<String>) plugin.economy()::format;
        TreasuryPayouts payouts = plugin.payouts();
        List<String> limits = new ArrayList<>();
        limits.add("<gray>Расход за сутки: <white>" + money.apply(payouts.spentToday())
                + (payouts.dailyLimit() > 0 ? "<gray> из <white>" + money.apply(payouts.dailyLimit()) : ""));
        limits.add(payouts.maxSingle() > 0 ? "<gray>Одна выплата не больше: <white>" + money.apply(payouts.maxSingle())
                : "<gray>Предел одной выплаты: <white>не задан");
        limits.add("<dark_gray>Деньги не печатаются: тратится только казна");
        List<String> head = new ArrayList<>();
        head.add("<gray>Баланс: <green>" + money.apply(plugin.treasury().balance()));
        head.addAll(limits);
        set(4, Text.icon(Material.EMERALD_BLOCK, "<green>Казна", head.toArray(new String[0])));

        set(10, Text.icon(Material.GOLD_BLOCK, "<gold>Субсидия предприятию",
                "<gray>Деньги из казны на счёт предприятия",
                "<yellow>Нажмите: участок, сумма, причина"), e -> subsidyFlow());
        set(12, Text.icon(Material.NETHER_STAR, "<light_purple>Приз за мероприятие",
                "<gray>Деньги из казны игроку",
                "<yellow>Нажмите: игрок, сумма, мероприятие"), e -> prizeFlow());

        TreasuryPayouts.Report report = payouts.report(7);
        List<String> rep = new ArrayList<>();
        rep.add("<gray>Приход: <green>" + money.apply(report.totalIncome()) + "<gray>, расход: <red>"
                + money.apply(report.totalExpense()));
        report.income().forEach((k, v) -> rep.add("<green>+ <white>" + k + "<gray>: " + money.apply(v)));
        report.expense().forEach((k, v) -> rep.add("<red>- <white>" + k + "<gray>: " + money.apply(v)));
        rep.add("<dark_gray>Подробнее: /district treasury report [дней]");
        set(14, Text.icon(Material.BOOK, "<aqua>Отчёт за 7 суток", rep.toArray(new String[0])));

        List<Treasury.Tx> log = plugin.treasury().log();
        int slot = 19;
        for (int i = log.size() - 1; i >= 0 && slot <= 25; i--, slot++) {
            Treasury.Tx tx = log.get(i);
            boolean in = tx.amount() >= 0;
            set(slot, Text.icon(in ? Material.LIME_DYE : Material.RED_DYE,
                    (in ? "<green>+" : "<red>-") + money.apply(Math.abs(tx.amount())),
                    "<gray>" + Text.esc(tx.reason()), "<dark_gray>" + Text.date(tx.time()) + " · " + Text.esc(tx.actor())));
        }
        set(36, Ui.back("Банк"), e -> new BankMenu(plugin, player).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private static boolean cancelled(String text) {
        return text.equalsIgnoreCase("отмена");
    }

    private void back() {
        new TreasuryMenu(plugin, player).open();
    }

    private void subsidyFlow() {
        plugin.chatPrompt().ask(player, "Введите номер участка для субсидии:", id -> {
            Plot plot = cancelled(id) ? null : plugin.plots().get(id);
            if (plot == null) {
                if (!cancelled(id)) {
                    Text.err(player, "Участок «" + Text.esc(id) + "» не найден.");
                }
                back();
                return;
            }
            plugin.chatPrompt().ask(player, "Введите сумму субсидии:", amount -> {
                if (cancelled(amount)) {
                    back();
                    return;
                }
                double value = parse(amount);
                plugin.chatPrompt().ask(player, "Введите причину (на что субсидия):", reason -> {
                    if (!cancelled(reason)) {
                        String error = plugin.payouts().subsidy(player.getName(), plot, value, reason);
                        if (error != null) {
                            Text.err(player, error);
                        } else {
                            Text.ok(player, "Субсидия " + plugin.economy().format(value) + " выделена участку " + plot.id + ".");
                        }
                    }
                    back();
                });
            });
        });
    }

    private void prizeFlow() {
        plugin.chatPrompt().ask(player, "Введите ник игрока-победителя:", name -> {
            OfflinePlayer target = cancelled(name) ? null : Bukkit.getOfflinePlayerIfCached(name);
            if (target == null) {
                if (!cancelled(name)) {
                    Text.err(player, "Игрок «" + Text.esc(name) + "» не найден.");
                }
                back();
                return;
            }
            plugin.chatPrompt().ask(player, "Введите сумму приза:", amount -> {
                if (cancelled(amount)) {
                    back();
                    return;
                }
                double value = parse(amount);
                plugin.chatPrompt().ask(player, "Введите название мероприятия:", event -> {
                    if (!cancelled(event)) {
                        String error = plugin.payouts().prize(player.getName(), target, value, event);
                        if (error != null) {
                            Text.err(player, error);
                        } else {
                            Text.ok(player, "Приз " + plugin.economy().format(value) + " выплачен игроку " + target.getName() + ".");
                        }
                    }
                    back();
                });
            });
        });
    }

    private static double parse(String text) {
        try {
            return Double.parseDouble(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
