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

    public static List<String> lines(Plot plot, DoubleFunction<String> money, Long billingNowSec) {
        return lines(plot, money, billingNowSec, false);
    }

    /**
     * @param billingNowSec текущее биллинговое время или null (строка электричества не показывается)
     * @param usageMode     тариф по факту использования: вместо остатка времени показывается способ оплаты
     */
    public static List<String> lines(Plot plot, DoubleFunction<String> money, Long billingNowSec, boolean usageMode) {
        return lines(plot, money, billingNowSec, usageMode, null);
    }

    private static boolean powerClient(Plot plot) {
        return plot.category == ru.buildmaster.model.Category.WORKSHOP
                || plot.category == ru.buildmaster.model.Category.HOTEL && plot.parentId == null;
    }

    /** @param gridOffReason причина, по которой предприятие отключено от электростанции (null - подключено) */
    public static List<String> lines(Plot plot, DoubleFunction<String> money, Long billingNowSec, boolean usageMode,
                                     String gridOffReason) {
        List<String> lines = new ArrayList<>();
        lines.add("<gold><bold>" + Text.esc(plot.displayName()));
        lines.add("<gray>" + plot.category.title() + " · № " + Text.esc(plot.id)
                + " · " + plot.box.describe());
        if (plot.category == ru.buildmaster.model.Category.BANK) {
            lines.add("<yellow>Счета, налоги, казна");
            return lines;
        }
        if (plot.category == ru.buildmaster.model.Category.POWER_PLANT) {
            lines.add("<yellow>Мощность: <white>" + plot.plantCapacity + " V/сут");
            if (plot.plantMultiplier > 1.0) {
                lines.add("<red>Перегрузка: тариф ×" + plot.plantMultiplier);
            }
            return lines;
        }
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
            if (billingNowSec != null && powerClient(plot) && gridOffReason != null) {
                lines.add("<red>Отключено от электростанции");
            } else if (billingNowSec != null && powerClient(plot) && usageMode && plot.category == ru.buildmaster.model.Category.WORKSHOP) {
                lines.add(plot.account.rawBalance() > 1e-9
                        ? "<yellow>Электричество: <white>оплата по использованию"
                        : "<red>Обесточено: на счёте нет средств");
            } else if (billingNowSec != null && powerClient(plot)) {
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
