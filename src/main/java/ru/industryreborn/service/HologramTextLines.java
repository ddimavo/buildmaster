package ru.industryreborn.service;

import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;

/** Сколько строк занимает общая голограмма участка (чтобы поставить панель управляющего выше). */
final class HologramTextLines {

    private HologramTextLines() { }

    static int count(IndustryRebornPlugin plugin, Plot plot) {
        int n = HologramText.lines(plot, plugin.economy()::format, plugin.power().nowSec(),
                plugin.power().mode() == PowerService.Mode.USAGE, plugin.grid().offReason(plot)).size();
        return plot.category == Category.HOTEL ? n + 1 : n;
    }
}
