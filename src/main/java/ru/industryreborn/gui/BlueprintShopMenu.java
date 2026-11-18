package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.util.Text;

/** Магазин чертежей администрации: чертёж открывает мастерской рецепт; деньги идут в казну. */
public final class BlueprintShopMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private static final int FIRST = 9;

    public BlueprintShopMenu(IndustryRebornPlugin plugin, Player player, Runnable back, int page) {
        super(plugin, player, "<dark_gray>Район › Магазин чертежей", 6);
        List<RecipeBook.Recipe> list = new ArrayList<>();
        for (RecipeBook.Recipe r : plugin.recipes().customRecipes()) {
            if (!r.free() && plugin.recipes().blueprintPrice(r.id()) > 0) {
                list.add(r);
            }
        }
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= list.size()) {
                break;
            }
            RecipeBook.Recipe r = list.get(index);
            double price = plugin.recipes().blueprintPrice(r.id());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Цена: <white>" + plugin.economy().format(price));
            lore.add("<gray>Станок: <white>" + RecipeBook.familyTitle(r.family()));
            for (RecipeBook.Ingredient ing : r.inputs()) {
                lore.add("<gray>Сырьё: <white>" + ing.count() + " × " + Text.esc(String.join(" / ", plugin.factory().ingredientNames(ing))));
            }
            lore.add("<gray>Результат: <white>" + r.outputAmount() + " × " + Text.esc(plugin.recipes().outputName(r)));
            lore.add("<gray>Время партии: <white>" + r.seconds() + " с");
            lore.add("");
            lore.add("<gray>Одноразовый. Изучает управляющий");
            lore.add("<gray>или работник мастерской: рецепт");
            lore.add("<gray>остаётся за предприятием навсегда.");
            lore.add(plugin.economy().has(player, price) ? "<yellow>Нажмите, чтобы купить" : "<red>Не хватает денег");
            set(FIRST + i, Text.icon(plugin.blueprints().create(r).getType(),
                    "<aqua>Чертёж: <white>" + Text.esc(plugin.recipes().outputName(r)), lore.toArray(new String[0])), e -> {
                        String error = plugin.blueprints().buy(player, r.id().substring(7));
                        if (error != null) {
                            Text.err(player, error);
                        }
                        new BlueprintShopMenu(plugin, player, back, current).open();
                    });
        }
        if (list.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>Чертежей в продаже пока нет"));
        }
        set(4, Text.icon(Material.MAP, "<gold>Магазин чертежей", "<gray>В продаже: <white>" + list.size(),
                "<gray>Чертёж читает управляющий или работник:", "<gray>/district blueprint use (с чертежом в руке)"));
        navBar(back == null ? null : "Назад", back, current, pages, p -> new BlueprintShopMenu(plugin, player, back, p).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
