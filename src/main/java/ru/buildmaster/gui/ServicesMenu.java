package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.PlotFilter;
import ru.buildmaster.service.ServiceCatalog;
import ru.buildmaster.util.Text;

/** Услуги мастерской для клиентов: заказ из руки (ЛКМ) или всех таких же предметов (Shift+ЛКМ). */
public final class ServicesMenu extends Menu {

    public ServicesMenu(BuildMasterPlugin plugin, Player player, Plot plot, PlotFilter back) {
        super(plugin, player, "<dark_gray>Услуги · " + Text.esc(plot.displayName()), 4);
        int slot = 10;
        boolean any = false;
        for (ServiceCatalog.Offer def : plugin.catalog().offers()) {
            Plot.Offer offer = plot.offers.get(def.key());
            if (offer == null || !offer.enabled || slot > 16) {
                continue;
            }
            any = true;
            int stations = plugin.stations().countByTariffKey(plot, def.stationKey());
            long queue = plugin.orders().ofPlot(plot.id).stream().filter(o -> o.state != ru.buildmaster.model.ServiceOrder.State.READY).count();
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Цена: <white>" + plugin.economy().format(offer.price) + " за единицу");
            lore.add("<gray>" + describe(def));
            lore.add("<gray>Станков: <white>" + stations + "<gray>, в очереди заказов: <white>" + queue);
            if (stations <= 0) {
                lore.add("<red>Нет нужного станка");
            }
            if (!plugin.power().isPowered(plot)) {
                String reason = plugin.power().gridOffReason(plot);
                lore.add(reason != null ? "<red>Отключено от электростанции: " + Text.esc(reason)
                        : "<red>Мастерская обесточена: заказы не принимаются");
            }
            lore.add("");
            lore.add("<yellow>Возьмите предметы в руку:");
            lore.add("<yellow>ЛКМ - заказать из руки");
            lore.add("<yellow>Shift+ЛКМ - все такие же из инвентаря");
            set(slot++, Text.icon(icon(def), "<gold>" + Text.esc(def.title()), lore.toArray(new String[0])), e -> {
                String error = plugin.orders().create(player, plot, def.key(), e.isShiftClick());
                if (error != null) {
                    Text.err(player, error);
                }
                new ServicesMenu(plugin, player, plot, back).open();
            });
        }
        if (!any) {
            set(13, Text.icon(Material.BARRIER, "<gray>Мастерская пока не оказывает услуг"));
        }
        set(30, Text.icon(Material.CHEST, "<aqua>Мои заказы"), e -> new MyOrdersMenu(plugin, player, 0).open());
        set(27, Text.icon(Material.ARROW, "<yellow>Назад"), e -> new PlotMenu(plugin, player, plot, back).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    static Material icon(ServiceCatalog.Offer def) {
        return switch (def.kind()) {
            case SMELT -> Material.FURNACE;
            case REPAIR -> Material.ANVIL;
            case RECIPE -> def.output() != null && def.output().isItem() ? def.output() : Material.CRAFTING_TABLE;
        };
    }

    static String describe(ServiceCatalog.Offer def) {
        return switch (def.kind()) {
            case SMELT -> "Переплавка предметов по рецептам печи";
            case REPAIR -> "Полный ремонт повреждённых предметов";
            case RECIPE -> def.inputAmount() + " × " + def.input().name() + " → " + def.outputAmount() + " × " + def.output().name();
        };
    }
}
