package ru.buildmaster.gui;

import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.util.Text;

/** Настройки банка (право buildmaster.bank): комиссии, штраф, налоги, режим «только в банке». */
public final class BankSettingsMenu extends Menu {

    public BankSettingsMenu(BuildMasterPlugin plugin, Player player) {
        super(plugin, player, "<dark_gray>Настройки банка", 4);
        if (!player.hasPermission("buildmaster.bank")) {
            set(13, Text.icon(Material.BARRIER, "<red>Недостаточно прав"));
            fill(Material.GRAY_STAINED_GLASS_PANE);
            return;
        }
        var bank = plugin.bank();
        String who = player.getName();
        percent(10, "Комиссия за пополнение", bank.depositCommission(), v -> bank.setDepositCommission(v, who));
        percent(11, "Комиссия за снятие", bank.withdrawCommission(), v -> bank.setWithdrawCommission(v, who));
        percent(12, "Штраф при изъятии участка", bank.seizurePenalty(), v -> bank.setSeizurePenalty(v, who));
        percent(13, "Налог на доход от аренды", bank.rentTax(), v -> bank.setRentTax(v, who));
        set(14, Text.icon(bank.onlyInBank() ? Material.LIME_DYE : Material.GRAY_DYE,
                "<white>Операции только в банке: " + (bank.onlyInBank() ? "<green>да" : "<red>нет"),
                "<yellow>Нажмите, чтобы переключить"), e -> {
                    bank.setOnlyInBank(!bank.onlyInBank(), who);
                    new BankSettingsMenu(plugin, player).open();
                });
        int slot = 19;
        for (Category c : Category.values()) {
            if (c.isAdminOnly()) {
                continue;
            }
            set(slot++, Text.icon(Material.GOLD_NUGGET, "<aqua>Налог на имущество: " + c.title(),
                    "<gray>За " + plugin.getConfig().getInt("bank.property-tax-period-days", 7) + " сут.: <white>"
                            + plugin.economy().format(bank.propertyTax(c)),
                    "<yellow>Нажмите, чтобы изменить (сумма или reset)"),
                    e -> plugin.chatPrompt().ask(player, "Введите сумму налога для типа «" + c.title() + "» (или reset):", text -> {
                        if (!text.equalsIgnoreCase("отмена")) {
                            try {
                                Double v = text.equalsIgnoreCase("reset") ? null : Double.parseDouble(text.replace(',', '.'));
                                if (v != null && (v < 0 || v.isNaN() || v.isInfinite())) {
                                    throw new NumberFormatException();
                                }
                                bank.setPropertyTax(c, v, who);
                            } catch (NumberFormatException ex) {
                                Text.err(player, "Нужна сумма не меньше нуля или reset.");
                            }
                        }
                        new BankSettingsMenu(plugin, player).open();
                    }));
        }
        set(27, Text.icon(Material.ARROW, "<yellow>Назад"), e -> new BankMenu(plugin, player).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private void percent(int slot, String name, double current, Consumer<Double> apply) {
        set(slot, Text.icon(Material.PAPER, "<aqua>" + name, "<gray>Сейчас: <white>" + current + "%",
                "<yellow>Нажмите, чтобы изменить (0-100 или reset)"),
                e -> plugin.chatPrompt().ask(player, "Введите процент для «" + name + "» (или reset):", text -> {
                    if (!text.equalsIgnoreCase("отмена")) {
                        try {
                            Double v = text.equalsIgnoreCase("reset") ? null
                                    : Double.parseDouble(text.replace(',', '.').replace("%", ""));
                            if (v != null && (v < 0 || v > 100 || v.isNaN())) {
                                throw new NumberFormatException();
                            }
                            apply.accept(v);
                        } catch (NumberFormatException ex) {
                            Text.err(player, "Нужен процент от 0 до 100 или reset.");
                        }
                    }
                    new BankSettingsMenu(plugin, player).open();
                }));
    }
}
