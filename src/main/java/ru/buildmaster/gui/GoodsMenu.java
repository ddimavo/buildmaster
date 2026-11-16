package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.PlotFilter;
import ru.buildmaster.util.Style;
import ru.buildmaster.util.Text;

/** Товары предприятия для управляющего: склад товаров, цены, связи с другими бизнесами. */
public final class GoodsMenu extends Menu {

    private static final int PAGE_SIZE = 36;

    public GoodsMenu(BuildMasterPlugin plugin, Player player, Plot plot, PlotFilter back, int page) {
        super(plugin, player, "<dark_gray>Товары · " + Text.esc(plot.displayName()), 6);
        var goods = plugin.goods();
        List<Material> items = new ArrayList<>(plot.goods.keySet());
        items.sort(java.util.Comparator.comparing(Material::name));
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= items.size()) {
                break;
            }
            Material m = items.get(index);
            double price = goods.price(plot, m);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>На складе: <white>" + goods.stock(plot, m));
            lore.add("<gray>Цена: " + (price > 0 ? "<white>" + plugin.economy().format(price) : "<red>не задана (не продаётся)"));
            lore.add("<yellow>ЛКМ - назначить цену");
            lore.add("<yellow>ПКМ - забрать 64");
            lore.add("<yellow>Shift+ПКМ - забрать всё");
            lore.add("<red>Shift+ЛКМ - снять с продажи");
            ItemStack icon = Icons.block(m);
            icon.editMeta(meta -> {
                meta.displayName(Text.item("<gold><bold>" + m.name()));
                meta.lore(Style.lore(lore.toArray(new String[0])).stream().map(Text::item).toList());
            });
            set(i, icon, e -> {
                if (e.isShiftClick() && e.isLeftClick()) {
                    String error = goods.setPrice(player, plot, m, null);
                    if (error != null) {
                        Text.err(player, error);
                    }
                    new GoodsMenu(plugin, player, plot, back, current).open();
                } else if (e.isRightClick()) {
                    String error = goods.withdraw(player, plot, m, e.isShiftClick() ? 0 : 64);
                    if (error != null) {
                        Text.err(player, error);
                    }
                    new GoodsMenu(plugin, player, plot, back, current).open();
                } else {
                    plugin.chatPrompt().ask(player, "Введите цену за штуку для " + m.name() + " (число):", text -> {
                        if (!text.equalsIgnoreCase("отмена")) {
                            try {
                                String error = goods.setPrice(player, plot, m, Double.parseDouble(text.replace(',', '.')));
                                if (error != null) {
                                    Text.err(player, error);
                                }
                            } catch (NumberFormatException ex) {
                                Text.err(player, "Нужно число.");
                            }
                        }
                        new GoodsMenu(plugin, player, plot, back, current).open();
                    });
                }
            });
        }
        if (items.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>Склад товаров пуст",
                    "<gray>Положите предметы из инвентаря (кнопка внизу)", "<gray>или привяжите мастерскую: /district link"));
        }
        // ---- нижняя панель ----
        if (current > 0) {
            set(45, Ui.prev(current, pages), e -> new GoodsMenu(plugin, player, plot, back, current - 1).open());
        }
        set(46, Text.icon(Material.BARREL, "<aqua>Склад товаров",
                "<gray>Занято: <white>" + goods.total(plot) + " из " + goods.capacity(plot),
                Style.bar((double) goods.total(plot) / Math.max(1, goods.capacity(plot)), 12, "aqua"),
                "<gray>Продаёт: <white>" + (goods.sells(plot) ? "да, через витрину" : "нет, только хранит"),
                "<gray>Налог с продаж: <white>" + goods.salesTaxPercent() + "%"));
        set(47, Text.icon(Material.HOPPER, "<green>Положить из инвентаря", "<gray>Все обычные предметы без названий",
                "<yellow>Нажмите, чтобы положить"), e -> {
            String error = goods.deposit(player, plot, null, 0);
            if (error != null) {
                Text.err(player, error);
            }
            new GoodsMenu(plugin, player, plot, back, current).open();
        });
        List<String> links = new ArrayList<>();
        if (plot.category == Category.WORKSHOP) {
            Plot target = plugin.links().target(plot);
            links.add("<gray>Связь: " + (target == null ? "<dark_gray>нет" : "<white>" + Text.esc(target.id) + " (" + target.category.title() + ")"));
            links.add("<gray>Своя витрина: " + (plot.storefront ? "<green>продукция идёт на витрину" : "<dark_gray>выключена"));
            links.add("<gray>Привязать: <white>/district link " + Text.esc(plot.id) + " <участок>");
            links.add("<gray>Витрина: <white>/district storefront on|off");
        } else {
            List<Plot> sources = plugin.links().sourcesOf(plot);
            links.add("<gray>Связанные мастерские: <white>" + (sources.isEmpty() ? "нет"
                    : String.join(", ", sources.stream().map(s -> Text.esc(s.id)).toList())));
            if (!plot.linkRequests.isEmpty()) {
                links.add("<yellow>Ждут подтверждения: <white>" + Text.esc(String.join(", ", plot.linkRequests)));
                links.add("<gray>/district link accept|decline <мастерская> " + Text.esc(plot.id));
            }
        }
        set(48, Text.icon(Material.LEAD, "<gold>Связи между бизнесами", links.toArray(new String[0])));
        set(49, Ui.back("Участок"), e -> new PlotMenu(plugin, player, plot, back).open());
        if (goods.sells(plot)) {
            set(51, Text.icon(Material.EMERALD, "<green>Посмотреть витрину", "<gray>Как её видят покупатели",
                    "<yellow>Нажмите, чтобы открыть"), e -> new StorefrontMenu(plugin, player, plot, back, 0).open());
        }
        if (current < pages - 1) {
            set(53, Ui.next(current, pages), e -> new GoodsMenu(plugin, player, plot, back, current + 1).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
