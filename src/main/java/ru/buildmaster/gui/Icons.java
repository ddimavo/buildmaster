package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

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

    static String[] plotLore(BuildMasterPlugin plugin, Plot plot) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Размер: <white>" + plot.box.describe());
        switch (plot.status) {
            case FREE -> {
                lore.add("<green>Свободен");
                lore.add("<gray>Цена: <white>" + (plot.price < 0 ? "не продаётся" : plugin.economy().format(plot.price)));
                lore.add("<gray>Аренда: <white>" + (plot.rentPerDay < 0 ? "не сдаётся"
                        : plugin.economy().format(plot.rentPerDay) + " / сутки"));
            }
            case OWNED -> {
                lore.add("<red>Занят <gray>(куплен)");
                lore.add("<gray>Владелец: <white>" + Text.esc(String.valueOf(plot.ownerName)));
            }
            case RENTED -> {
                lore.add("<yellow>Арендован");
                lore.add("<gray>Арендатор: <white>" + Text.esc(String.valueOf(plot.ownerName)));
                lore.add("<gray>Осталось: <white>" + Text.duration(plot.rentedUntil - System.currentTimeMillis()));
                lore.add("<gray>Автопродление: " + (plot.autoRenew ? "<green>включено" : "<red>выключено"));
            }
        }
        return lore.toArray(new String[0]);
    }
}
