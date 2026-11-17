package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.util.Text;

/** Витрина магазина или мастерской для покупателей: товар, цена, наличие и покупка. */
public final class StorefrontMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    public StorefrontMenu(IndustryRebornPlugin plugin, Player player, Plot plot, PlotFilter back, int page) {
        super(plugin, player, "<dark_gray>Витрина · " + Text.esc(plot.displayName()), 6);
        var goods = plugin.goods();
        List<Material> listed = goods.listed(plot);
        int pages = Math.max(1, (listed.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= listed.size()) {
                break;
            }
            Material m = listed.get(index);
            int have = goods.stock(plot, m);
            double price = goods.price(plot, m);
            ItemStack icon = Icons.block(m);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Цена за штуку: <white>" + plugin.economy().format(price));
            lore.add("<gray>В наличии: " + (have > 0 ? "<white>" + have : "<red>нет"));
            if (have > 0) {
                lore.add("<yellow>ЛКМ - купить 1");
                lore.add("<yellow>Shift+ЛКМ - купить 16");
                lore.add("<yellow>ПКМ - купить 64");
            }
            icon.editMeta(meta -> {
                meta.displayName(Text.item(have > 0 ? "<gold><bold>" + m.name() : "<gray>" + m.name()));
                meta.lore(ru.industryreborn.util.Style.lore(lore.toArray(new String[0])).stream().map(Text::item).toList());
            });
            set(i, icon, e -> {
                if (have <= 0) {
                    return;
                }
                int want = e.isRightClick() ? 64 : e.isShiftClick() ? 16 : 1;
                int amount = Math.min(want, goods.stock(plot, m));
                String error = goods.buy(player, plot, m, amount);
                if (error != null) {
                    Text.err(player, error);
                }
                new StorefrontMenu(plugin, player, plot, back, current).open();
            });
        }
        if (listed.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>На витрине пока ничего нет", "<gray>Загляните позже"));
        }
        if (current > 0) {
            set(45, Ui.prev(current, pages), e -> new StorefrontMenu(plugin, player, plot, back, current - 1).open());
        }
        set(47, Text.icon(Material.GOLD_NUGGET, "<gold>Ваш баланс", "<gray>Денег: <white>" + plugin.economy().format(plugin.economy().balance(player)),
                "<gray>Налог с продаж: <white>" + goods.salesTaxPercent() + "%"));
        set(49, Ui.back("Участок"), e -> new PlotMenu(plugin, player, plot, back).open());
        if (current < pages - 1) {
            set(53, Ui.next(current, pages), e -> new StorefrontMenu(plugin, player, plot, back, current + 1).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
