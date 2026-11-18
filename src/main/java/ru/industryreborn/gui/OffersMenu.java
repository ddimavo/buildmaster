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

/** Прайс услуг мастерской (для управляющего): ЛКМ - включить/выключить, ПКМ - изменить цену. */
public final class OffersMenu extends Menu {

    public OffersMenu(IndustryRebornPlugin plugin, Player player, Plot plot, PlotFilter back) {
        super(plugin, player, "<dark_gray>Район › Участки › " + Text.esc(plot.displayName()) + " › Прайс услуг", 6);
        set(4, Text.icon(Material.WRITABLE_BOOK, "<gold>Прайс услуг", "<gray>ЛКМ - включить или выключить услугу", "<gray>ПКМ - изменить цену"));
        set(9, Ui.section("Услуги", Material.ORANGE_STAINED_GLASS_PANE));
        int slot = 11;
        for (ServiceCatalog.Offer def : plugin.catalog().offers()) {
            if (slot > 17) {
                break;
            }
            Plot.Offer offer = plugin.orders().offerOf(plot, def);
            int stations = plugin.stations().countByTariffKey(plot, def.stationKey());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + ServicesMenu.describe(def));
            lore.add(offer.enabled ? "<green>Включено" : "<red>Выключено");
            lore.add("<gray>Цена за единицу: <white>" + plugin.economy().format(offer.price)
                    + " <dark_gray>(макс. " + plugin.economy().format(def.maxPrice()) + ")");
            lore.add("<gray>Станков для услуги: <white>" + stations + (stations == 0 ? " <red>(услуга не заработает)" : ""));
            lore.add("");
            lore.add("<yellow>ЛКМ - включить или выключить");
            lore.add("<yellow>ПКМ - изменить цену");
            set(slot++, Text.icon(ServicesMenu.icon(def), "<gold>" + Text.esc(def.title()), lore.toArray(new String[0])), e -> {
                if (e.isRightClick()) {
                    plugin.chatPrompt().ask(player, "Введите цену за единицу услуги «" + def.title() + "»:", text -> {
                        if (!text.equalsIgnoreCase("отмена")) {
                            try {
                                String error = plugin.orders().configureOffer(player, plot, def.key(),
                                        Double.parseDouble(text.replace(',', '.')), null);
                                if (error != null) {
                                    Text.err(player, error);
                                }
                            } catch (NumberFormatException ex) {
                                Text.err(player, "Нужно число.");
                            }
                        }
                        new OffersMenu(plugin, player, plot, back).open();
                    });
                } else {
                    String error = plugin.orders().configureOffer(player, plot, def.key(), null, !offer.enabled);
                    if (error != null) {
                        Text.err(player, error);
                    }
                    new OffersMenu(plugin, player, plot, back).open();
                }
            });
        }
        navBar("Участок", () -> new PlotMenu(plugin, player, plot, back).open(), 0, 1, p -> { });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
