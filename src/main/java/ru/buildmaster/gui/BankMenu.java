package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.service.Treasury;
import ru.buildmaster.util.Text;

/** Главное меню банка: свои счета и условия; для банковских работников - казна, все счета и настройки. */
public final class BankMenu extends Menu {

    public BankMenu(BuildMasterPlugin plugin, Player player) {
        super(plugin, player, "<dark_gray>Банк", 4);
        var bank = plugin.bank();
        long mine = plugin.plots().all().stream().filter(p -> p.isOwner(player.getUniqueId())).count();

        set(10, Text.icon(Material.GOLD_INGOT, "<gold>Мои счета", "<gray>Счетов: <white>" + mine,
                "<yellow>Пополнение, снятие, выписка"), e -> new AccountsMenu(plugin, player, false, 0).open());

        List<String> terms = new ArrayList<>();
        terms.add("<gray>Комиссия за пополнение: <white>" + bank.depositCommission() + "%");
        terms.add("<gray>Комиссия за снятие: <white>" + bank.withdrawCommission() + "%");
        terms.add("<gray>Штраф при изъятии: <white>" + bank.seizurePenalty() + "% остатка");
        terms.add("<gray>Налог на доход от аренды: <white>" + bank.rentTax() + "%");
        for (Category c : Category.values()) {
            if (!c.isAdminOnly() && bank.propertyTax(c) > 0) {
                terms.add("<gray>Налог на имущество (" + c.title() + "): <white>" + plugin.economy().format(bank.propertyTax(c)));
            }
        }
        terms.add(bank.onlyInBank() ? "<yellow>Операции только в здании банка" : "<gray>Операции из любого места");
        set(12, Text.icon(Material.BOOK, "<aqua>Условия банка", terms.toArray(new String[0])));

        if (player.hasPermission("buildmaster.bank")) {
            Treasury t = plugin.treasury();
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Баланс: <white>" + plugin.economy().format(t.balance()));
            var log = t.log();
            for (int i = Math.max(0, log.size() - 4); i < log.size(); i++) {
                lore.add("<dark_gray>" + Text.date(log.get(i).time()) + " <white>" + String.format("%+.2f", log.get(i).amount())
                        + " <gray>" + Text.esc(log.get(i).reason()));
            }
            lore.add("");
            lore.add("<gray>Выплаты: /district treasury pay");
            set(14, Text.icon(Material.EMERALD_BLOCK, "<green>Казна", lore.toArray(new String[0])));
            set(21, Text.icon(Material.WRITABLE_BOOK, "<red>Все счета предприятий",
                    "<gray>Баланс, долги, история операций"), e -> new AccountsMenu(plugin, player, true, 0).open());
            set(23, Text.icon(Material.COMPARATOR, "<red>Настройки банка",
                    "<gray>Комиссии, штраф, налоги"), e -> new BankSettingsMenu(plugin, player).open());
        }
        set(27, Ui.home(), e -> new MainMenu(plugin, player).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
