package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

/** Голограмма услуг и товаров: что предприятие оказывает и продаёт, по какой цене, сколько в наличии и как купить. */
public final class ServiceBoardText {

    private static final int MAX_GOODS = 7;

    private ServiceBoardText() { }

    /** Пустой список - показывать нечего. */
    public static List<String> lines(BuildMasterPlugin plugin, Plot plot) {
        List<String> lines = new ArrayList<>();
        var money = (java.util.function.DoubleFunction<String>) plugin.economy()::format;
        List<String> services = new ArrayList<>();
        if (plot.category == Category.WORKSHOP) {
            for (ServiceCatalog.Offer offer : plugin.catalog().offers()) {
                Plot.Offer o = plot.offers.get(offer.key());
                if (o != null && o.enabled) {
                    services.add("<white>" + Text.esc(offer.title()) + " <gray>- <yellow>" + money.apply(o.price) + "<gray> за ед.");
                }
            }
        }
        List<String> goods = new ArrayList<>();
        List<Material> listed = plugin.goods().listed(plot);
        for (int i = 0; i < listed.size() && i < MAX_GOODS; i++) {
            Material m = listed.get(i);
            int have = plugin.goods().stock(plot, m);
            goods.add("<white>" + name(m) + " <gray>- <yellow>" + money.apply(plugin.goods().price(plot, m))
                    + (have > 0 ? " <gray>(в наличии: <white>" + have + "<gray>)" : " <red>(нет в наличии)"));
        }
        if (listed.size() > MAX_GOODS) {
            goods.add("<gray>... и ещё " + (listed.size() - MAX_GOODS));
        }
        if (services.isEmpty() && goods.isEmpty()) {
            return lines;
        }
        lines.add("<gold><bold>" + Text.esc(plot.displayName()));
        if (!services.isEmpty()) {
            lines.add("<aqua>— Услуги —");
            lines.addAll(services);
        }
        if (!goods.isEmpty()) {
            lines.add("<aqua>— Товары —");
            lines.addAll(goods);
        }
        lines.add("<green>Купить: <white>/district shop " + Text.esc(plot.id));
        return lines;
    }

    private static String name(Material m) {
        try {
            return "<lang:" + new org.bukkit.inventory.ItemStack(m).translationKey() + ">";
        } catch (RuntimeException | LinkageError e) {
            return m.name().toLowerCase().replace('_', ' ');
        }
    }
}
