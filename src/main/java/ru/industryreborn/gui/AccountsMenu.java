package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/** Список счетов: свои (пополнение и снятие) или все (проверка банком). */
public final class AccountsMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private static final int FIRST = 9;

    public AccountsMenu(IndustryRebornPlugin plugin, Player player, boolean all, int page) {
        super(plugin, player, all ? "<dark_gray>Район › Банк › Все счета" : "<dark_gray>Район › Банк › Мои счета", 6);
        List<Plot> plots;
        if (all) {
            plots = plugin.bank().accounts();
        } else {
            plots = new ArrayList<>();
            for (Plot p : plugin.plots().all()) {
                if (p.isOwner(player.getUniqueId())) {
                    plugin.power().touch(p);
                    plots.add(p);
                }
            }
        }
        int pages = Math.max(1, (plots.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= plots.size()) {
                break;
            }
            Plot plot = plots.get(index);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Тип: <white>" + plot.category.title());
            lore.add("<gray>Управляющий: <white>" + Text.esc(String.valueOf(plot.ownerName)));
            lore.add("<gray>Баланс: <white>" + plugin.economy().format(plugin.power().balance(plot)));
            if (plot.taxDebt > 1e-9) {
                lore.add("<red>Долг по налогу: " + plugin.economy().format(plot.taxDebt));
            }
            lore.add("");
            if (all) {
                lore.add("<yellow>Нажмите: история и действия");
            } else {
                lore.add("<yellow>ЛКМ - пополнить, ПКМ - снять");
                lore.add("<yellow>Shift+ЛКМ - выписка");
            }
            set(FIRST + i, Text.icon(Material.GOLD_NUGGET, "<gold>" + Text.esc(plot.displayName()) + " <gray>№ " + Text.esc(plot.id),
                    lore.toArray(new String[0])), e -> {
                        if (all) {
                            new AccountMenu(plugin, player, plot).open();
                        } else if (e.isShiftClick()) {
                            new AccountMenu(plugin, player, plot).open();
                        } else {
                            AccountDialogs.money(plugin, player, plot, e.isRightClick(), () -> new AccountsMenu(plugin, player, all, current).open());
                        }
                    });
        }
        if (plots.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>Счетов нет"));
        }
        set(4, Text.icon(Material.GOLD_INGOT, "<gold>" + (all ? "Все счета" : "Мои счета"), "<gray>Счетов: <white>" + plots.size()));
        navBar("Банк", () -> new BankMenu(plugin, player).open(), current, pages, p -> new AccountsMenu(plugin, player, all, p).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
