package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.service.ServiceCatalog;
import ru.industryreborn.util.Text;

/** Услуги мастерской для клиентов: заказ из руки (ЛКМ) или всех таких же предметов (Shift+ЛКМ). */
public final class ServicesMenu extends Menu {

    public ServicesMenu(IndustryRebornPlugin plugin, Player player, Plot plot, PlotFilter back) {
        super(plugin, player, "<dark_gray>Район › Участки › " + Text.esc(plot.displayName()) + " › Услуги", 6);
        set(4, Text.icon(Material.FURNACE, "<gold>Услуги мастерской", "<gray>Возьмите предметы в руку и нажмите на услугу"));
        set(9, Ui.section("Услуги", Material.ORANGE_STAINED_GLASS_PANE));
        int slot = 11;
        boolean any = false;
        for (ServiceCatalog.Offer def : plugin.catalog().offers()) {
            Plot.Offer offer = plot.offers.get(def.key());
            if (offer == null || !offer.enabled || slot > 17) {
                continue;
            }
            any = true;
            int stations = plugin.stations().countByTariffKey(plot, def.stationKey());
            long queue = plugin.orders().ofPlot(plot.id).stream().filter(o -> o.state != ru.industryreborn.model.ServiceOrder.State.READY).count();
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
            set(22, Text.icon(Material.BARRIER, "<gray>Мастерская пока не оказывает услуг"));
        }
        set(7, Text.icon(Material.CHEST, "<aqua>Мои заказы"), e -> new MyOrdersMenu(plugin, player, 0).open());
        navBar("Участок", () -> new PlotMenu(plugin, player, plot, back).open(), 0, 1, p -> { });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    static Material iconFor(Material m) {
        return m != null && m.isItem() ? m : Material.CRAFTING_TABLE;
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
