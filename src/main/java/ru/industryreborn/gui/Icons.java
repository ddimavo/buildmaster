package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

final class Icons {

    private Icons() { }

    /** Предмет-иконка для блока (даже если у блока нет предметной формы, например стенной факел). */
    static ItemStack block(Material material) {
        Material shown = material;
        if (!shown.isItem()) {
            try {
                shown = material.createBlockData().getPlacementMaterial();
            } catch (Exception ignored) {
                shown = Material.PAPER;
            }
            if (!shown.isItem() || shown.isAir()) {
                shown = Material.PAPER;
            }
        }
        return new ItemStack(shown);
    }

    static Material statusMaterial(Plot plot, boolean mine) {
        if (mine) {
            return Material.EMERALD_BLOCK;
        }
        return switch (plot.status) {
            case FREE -> Material.LIME_CONCRETE;
            case OWNED -> Material.RED_CONCRETE;
            case RENTED -> Material.YELLOW_CONCRETE;
        };
    }

    static String[] plotLore(IndustryRebornPlugin plugin, Plot plot) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Тип: <white>" + plot.category.title() + " <gray>· размер: <white>" + plot.box.describe());
        if (plot.isPlayerPlot()) {
            lore.add("<gray>Арендодатель: <white>" + Text.esc(String.valueOf(plot.landlordName)));
            if (plot.approval == Plot.Approval.PENDING) {
                lore.add("<gold>⏳ Ожидает одобрения администратора");
            }
        }
        if (plot.description != null && !plot.description.isBlank()) {
            lore.add("<gray><italic>" + Text.esc(plot.description));
        }
        switch (plot.status) {
            case FREE -> {
                lore.add("<green>● Свободен");
                lore.add("<gray>Цена: <white>" + (plot.price < 0 ? "не продаётся" : plugin.economy().format(plot.price)));
                lore.add("<gray>Аренда: <white>" + (plot.rentPerDay < 0 ? "не сдаётся"
                        : plugin.economy().format(plot.rentPerDay) + " / сутки"));
                if (plot.category == ru.industryreborn.model.Category.HOTEL) {
                    lore.add("<gray>Номеров: <white>" + plugin.plots().childrenOf(plot.id).size());
                }
            }
            case OWNED -> {
                lore.add("<red>● Занят <gray>(куплен)");
                lore.add("<gray>Владелец: <white>" + Text.esc(String.valueOf(plot.ownerName)));
            }
            case RENTED -> {
                long left = plot.rentedUntil - System.currentTimeMillis();
                lore.add(plot.isFrozen() ? "<red>● Заморожен" : "<yellow>● Арендован");
                lore.add("<gray>Арендатор: <white>" + Text.esc(String.valueOf(plot.ownerName)));
                lore.add("<gray>Осталось: <white>" + Text.duration(left));
                lore.add(ru.industryreborn.util.Style.bar((double) left / (plugin.service().maxRentDays()
                        * plugin.service().dayMillis()), 12, left < 86_400_000L ? "red" : "yellow"));
                lore.add("<gray>Автопродление: " + (plot.autoRenew ? "<green>включено" : "<red>выключено"));
                if (plot.isFrozen()) {
                    lore.add("<red>Платёж просрочен, осталось " + Text.duration(plot.graceUntil - System.currentTimeMillis()));
                }
            }
        }
        if (!plot.isFree()) {
            if (plot.category == ru.industryreborn.model.Category.WORKSHOP) {
                lore.add("<gray>Станков: <white>" + plugin.stations().tariffCounts(plot).values().stream().mapToInt(Integer::intValue).sum()
                        + (plugin.power().isPowered(plot) ? " <green>⚡ есть ток" : " <red>⚡ обесточена"));
            }
            if (plot.category == ru.industryreborn.model.Category.HOTEL) {
                lore.add("<gray>Номеров свободно: <white>" + plugin.hotels().freeRooms(plot) + " из "
                        + plugin.plots().childrenOf(plot.id).size());
            }
            int staff = plugin.jobs().employeesOf(plot.id).size();
            int vacancies = plugin.jobs().vacanciesOf(plot.id).size();
            if (staff + vacancies > 0) {
                lore.add("<gray>Работники: <white>" + staff + " <gray>· вакансий: <white>" + vacancies);
            }
        }
        return lore.toArray(new String[0]);
    }
}
