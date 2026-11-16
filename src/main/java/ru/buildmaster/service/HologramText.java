package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

/** Содержимое голограммы участка (строки в формате MiniMessage). */
public final class HologramText {

    private HologramText() { }

    public static List<String> lines(Plot plot, DoubleFunction<String> money) {
        return lines(plot, money, null);
    }

    /** @param billingNowSec текущее биллинговое время или null (строка электричества не показывается) */
    public static List<String> lines(Plot plot, DoubleFunction<String> money, Long billingNowSec) {
        List<String> lines = new ArrayList<>();
        lines.add("<gold><bold>" + Text.esc(plot.displayName()));
        lines.add("<gray>" + plot.category.title() + " · № " + Text.esc(plot.id)
                + " · " + plot.box.describe());
        if (plot.isFree()) {
            lines.add("<green>Свободен");
            if (plot.price >= 0) {
                lines.add("<yellow>Продажа: <white>" + money.apply(plot.price));
            }
            if (plot.rentPerDay >= 0) {
                lines.add("<yellow>Аренда: <white>" + money.apply(plot.rentPerDay) + " / сутки");
            }
        } else {
            lines.add("<gray>Управляющий: <white>" + Text.esc(String.valueOf(plot.ownerName)));
            if (plot.isFrozen()) {
                lines.add("<red>Платёж просрочен");
            }
            if (billingNowSec != null && plot.category == ru.buildmaster.model.Category.WORKSHOP) {
                if (!plot.account.powered(billingNowSec)) {
                    lines.add("<red>Обесточено");
                } else if (plot.account.ratePerDay() > 0) {
                    lines.add("<yellow>Электричество: <white>"
                            + Text.duration(Math.min(plot.account.secondsLeft(billingNowSec), 3650L * 86400) * 1000L));
                }
            }
            if (plot.description != null && !plot.description.isBlank()) {
                for (String l : TextRules.wrap(plot.description, 32)) {
                    lines.add("<gray>" + Text.esc(l));
                }
            }
        }
        return lines;
    }
}
