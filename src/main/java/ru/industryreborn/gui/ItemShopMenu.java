package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.items.CustomItem;
import ru.industryreborn.items.ItemKind;
import ru.industryreborn.util.Text;

/** Магазин предметов администрации: инструменты, скины и другие предметы плагина. */
public final class ItemShopMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private static final int FIRST = 9;

    public ItemShopMenu(IndustryRebornPlugin plugin, Player player, Runnable back, int page) {
        super(plugin, player, "<dark_gray>Район › Магазин предметов", 6);
        List<CustomItem> list = plugin.itemShop().listed();
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= list.size()) {
                break;
            }
            CustomItem d = list.get(index);
            double price = plugin.itemShop().price(d.id());
            String name = Text.esc(plugin.customItems().displayName(d.ref()));
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Тип: <white>" + d.kind().title());
            lore.add("<gray>Цена за штуку: <white>" + plugin.economy().format(price));
            if (d.kind() == ItemKind.SKIN) {
                lore.add("<gray>Ставится на: <white>" + String.join(", ", d.skinFor()));
                lore.add("<gray>Одноразовый: расходуется при применении");
                lore.add("<gray>на один инструмент (/district skin)");
            }
            lore.add(plugin.economy().has(player, price) ? "<yellow>ЛКМ - купить 1, Shift+ЛКМ - 8" : "<red>Не хватает денег");
            ItemStack shown = plugin.customItems().build(d.ref(), 1);
            Material icon = shown == null ? plugin.customItems().baseMaterial(d.ref()) : shown.getType();
            set(FIRST + i, Text.icon(icon, "<gold>" + name, lore.toArray(new String[0])), e -> {
                String error = plugin.itemShop().buy(player, d.id(), e.isShiftClick() ? 8 : 1);
                if (error != null) {
                    Text.err(player, error);
                }
                new ItemShopMenu(plugin, player, back, current).open();
            });
        }
        if (list.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>В продаже пока ничего нет"));
        }
        set(4, Text.icon(Material.EMERALD, "<gold>Магазин предметов", "<gray>В продаже: <white>" + list.size(),
                "<gray>Скин применяется командой /district skin", "<gray>Чертежи рецептов: /district blueprint shop"));
        navBar(back == null ? null : "Назад", back, current, pages, p -> new ItemShopMenu(plugin, player, back, p).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
