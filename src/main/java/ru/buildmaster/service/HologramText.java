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
            if (plot.description != null && !plot.description.isBlank()) {
                for (String l : TextRules.wrap(plot.description, 32)) {
                    lines.add("<gray>" + Text.esc(l));
                }
            }
        }
        return lines;
    }
}
