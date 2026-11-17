package ru.industryreborn.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/** Тексты голограмм банка: большая доска налогов, комиссий и казны и отдельная доска последних операций казны. */
public final class BankBoardText {

    private BankBoardText() { }

    private static String pct(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + "%";
    }

    /** Налоги, комиссии и казна. */
    public static List<String> board(IndustryRebornPlugin plugin, Plot bank) {
        var money = (java.util.function.DoubleFunction<String>) plugin.economy()::format;
        List<String> lines = new ArrayList<>();
        lines.add("<gold><bold>" + Text.esc(bank.displayName()));
        lines.add("<aqua>— Налоги —");
        lines.add("<white>Налог на аренду: <yellow>" + pct(plugin.bank().rentTax()));
        lines.add("<white>Налог с продаж: <yellow>" + pct(plugin.getConfig().getDouble("goods.sales-tax-percent", 0)));
        int days = Math.max(1, plugin.getConfig().getInt("bank.property-tax-period-days", 7));
        StringBuilder property = new StringBuilder();
        for (Category c : Category.values()) {
            double tax = plugin.bank().propertyTax(c);
            if (tax > 0) {
                property.append("<gray>").append(c.title()).append(": <yellow>").append(money.apply(tax)).append("\n");
            }
        }
        if (property.length() > 0) {
            lines.add("<white>Налог на имущество <gray>(раз в " + days + " сут.)<white>:");
            for (String l : property.toString().split("\n")) {
                lines.add(l);
            }
        } else {
            lines.add("<white>Налог на имущество: <green>не взимается");
        }
        lines.add("<aqua>— Комиссии банка —");
        lines.add("<white>Пополнение счёта: <yellow>" + pct(plugin.bank().depositCommission()));
        lines.add("<white>Снятие со счёта: <yellow>" + pct(plugin.bank().withdrawCommission()));
        lines.add("<white>Штраф при изъятии: <yellow>" + pct(plugin.bank().seizurePenalty()));
        lines.add("<aqua>— Казна —");
        lines.add("<white>Баланс: <green>" + money.apply(plugin.treasury().balance()));
        return lines;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Последние операции казны, новые сверху. */
    public static List<String> log(IndustryRebornPlugin plugin, Plot bank) {
        var money = (java.util.function.DoubleFunction<String>) plugin.economy()::format;
        int max = Math.max(1, Math.min(20, plugin.getConfig().getInt("holograms.bank-log-lines", 8)));
        List<Treasury.Tx> all = plugin.treasury().log();
        List<String> lines = new ArrayList<>();
        lines.add("<gold><bold>Последние операции казны");
        if (all.isEmpty()) {
            lines.add("<gray>Операций ещё не было");
            return lines;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());
        for (int i = all.size() - 1; i >= 0 && lines.size() <= max; i--) {
            Treasury.Tx tx = all.get(i);
            boolean in = tx.amount() >= 0;
            lines.add("<dark_gray>" + fmt.format(Instant.ofEpochMilli(tx.time())) + " " + (in ? "<green>+" : "<red>-")
                    + money.apply(Math.abs(tx.amount())) + " <gray>" + Text.esc(shorten(tx.reason(), 44)));
        }
        return lines;
    }
}
