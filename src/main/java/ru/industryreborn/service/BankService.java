package ru.industryreborn.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/**
 * Банк: комиссии за пополнение и снятие, штраф при изъятии, налоги (на аренду и на имущество),
 * проверка счетов. Значения: настройка администратора (bank.yml), иначе config.yml.
 */
public final class BankService {

    private final IndustryRebornPlugin plugin;
    private final File file;
    private BankSettings settings = new BankSettings();

    public BankService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bank.yml");
    }

    public void load() {
        settings = BankSettings.load(file);
    }

    public BankSettings settings() {
        return settings;
    }

    public void save() {
        try {
            settings.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить bank.yml", e);
        }
    }

    // ------------------------------------------------------------ значения

    public double depositCommission() {
        Double o = settings.depositCommission();
        return o != null ? o : plugin.getConfig().getDouble("bank.deposit-commission-percent", 0);
    }

    public double withdrawCommission() {
        Double o = settings.withdrawCommission();
        return o != null ? o : plugin.getConfig().getDouble("bank.withdraw-commission-percent", 0);
    }

    /** Штраф при изъятии участка (процент от остатка на счёте). */
    public double seizurePenalty() {
        Double o = settings.seizurePenalty();
        return o != null ? o : plugin.getConfig().getDouble("bank.seizure-penalty-percent", 10);
    }

    /** Налог на доход от сдачи в аренду (процент выручки, платит арендодатель). */
    public double rentTax() {
        Double o = settings.rentTax();
        return o != null ? o : plugin.getConfig().getDouble("bank.rent-tax-percent", 0);
    }

    /** Недельный налог на имущество для типа участка (сумма за период, 0 - не взимается). */
    public double propertyTax(Category category) {
        Double o = settings.propertyTax(category.key());
        return o != null ? o : plugin.getConfig().getDouble("bank.property-tax." + category.key(), 0);
    }

    public boolean onlyInBank() {
        Boolean o = settings.onlyInBank();
        return o != null ? o : plugin.getConfig().getBoolean("bank.only-in-bank", false);
    }

    public long taxPeriodMillis() {
        return Math.max(1, plugin.getConfig().getInt("bank.property-tax-period-days", 7)) * plugin.service().dayMillis();
    }

    // ------------------------------------------------------------ доступ

    /** Стоит ли игрок внутри здания банка. */
    public boolean inBank(Player player) {
        Plot plot = plugin.plots().at(player.getLocation());
        return plot != null && plot.category == Category.BANK;
    }

    /** Можно ли выполнять операции со счётом: null - да, иначе текст отказа. */
    public String checkOperation(Player player) {
        if (!onlyInBank() || player.hasPermission("industryreborn.bank") || inBank(player)) {
            return null;
        }
        return "Операции со счётом сейчас доступны только в здании банка.";
    }

    // ------------------------------------------------------------ налог на аренду

    /**
     * Арендодатель получил доход от сдачи в аренду: налог удерживается из выручки и уходит в казну.
     * Возвращает сумму, которую арендодатель получает на руки.
     */
    public double applyRentTax(String plotId, java.util.UUID landlord, double income) {
        double tax = TaxRules.percentOf(income, rentTax());
        if (tax > 0) {
            plugin.treasury().deposit(tax, "Налог на аренду, участок " + plotId);
            plugin.saveTreasury();
            plugin.journal().add(plotId, landlord, "система", Journal.Action.TAX,
                    "Налог на доход от аренды: " + plugin.economy().format(tax));
        }
        return income - tax;
    }

    // ------------------------------------------------------------ налог на имущество

    /** Платящие налог участки: с управляющим, кроме административных зданий. */
    private boolean taxable(Plot plot) {
        return !plot.isFree() && plot.owner != null && !plot.isPlayerPlot() && !plot.category.isAdminOnly() && !plot.category.isRentOnly()
                && !plot.restorePending;
    }

    /**
     * Забирает сумму со счёта предприятия, а недостающее - с кошелька управляющего.
     * Возвращает фактически списанное.
     */
    private double collect(Plot plot, double amount) {
        plugin.power().touch(plot);
        double fromAccount = Math.min(plot.account.rawBalance(), amount);
        if (fromAccount > 1e-9) {
            plot.account.withdraw(fromAccount);
        }
        double remaining = amount - fromAccount;
        double fromWallet = 0;
        if (remaining > 1e-9 && plugin.economy().available()) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(plot.owner);
            if (plugin.economy().withdraw(owner, remaining)) {
                fromWallet = remaining;
            }
        }
        return fromAccount + fromWallet;
    }

    /** Периодическая задача: начисление налога на имущество и повторные попытки взыскать долг. */
    public void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Plot plot : new ArrayList<>(plugin.plots().all())) {
            if (!taxable(plot) || plot.busy) {
                continue;
            }
            int periods = TaxRules.periodsDue(now, plot.nextTaxAt, taxPeriodMillis(), 4);
            if (periods > 0) {
                plot.nextTaxAt = TaxRules.advance(plot.nextTaxAt, taxPeriodMillis(), periods);
                double weekly = propertyTax(plot.category);
                if (weekly > 0) {
                    chargeProperty(plot, weekly * periods);
                }
                changed = true;
            } else if (plot.taxDebt > 1e-9 && plugin.economy().available()) {
                double paid = collect(plot, plot.taxDebt);
                if (paid > 1e-9) {
                    plot.taxDebt = Math.max(0, plot.taxDebt - paid);
                    plugin.treasury().deposit(paid, "Погашение долга по налогу, участок " + plot.id);
                    plugin.journal().add(plot.id, plot.owner, "система", Journal.Action.TAX,
                            "Погашен долг по налогу: " + plugin.economy().format(paid));
                    changed = true;
                }
            }
        }
        if (changed) {
            plugin.plots().save();
            plugin.saveTreasury();
        }
    }

    private void chargeProperty(Plot plot, double due) {
        double collected = plugin.economy().available() ? collect(plot, due) : 0;
        double debt = due - collected;
        plot.taxDebt += debt;
        if (collected > 0) {
            plugin.treasury().deposit(collected, "Налог на имущество, участок " + plot.id);
        }
        plugin.journal().add(plot.id, plot.owner, "система", Journal.Action.TAX,
                "Налог на имущество " + plugin.economy().format(due) + ": списано "
                        + plugin.economy().format(collected)
                        + (debt > 1e-9 ? ", долг " + plugin.economy().format(plot.taxDebt) : ""));
        plugin.service().warn(plot.owner, plot.id, (debt > 1e-9 ? "<red>" : "<yellow>")
                + "Налог на имущество участка <white>" + Text.esc(plot.id) + "</white>: "
                + plugin.economy().format(due) + ", списано " + plugin.economy().format(collected)
                + (debt > 1e-9 ? ". Не хватило средств, долг: " + plugin.economy().format(plot.taxDebt)
                        + ". Пополните счёт: /district account deposit <сумма>" : "."));
    }

    /** Администратор списывает долг по налогу. */
    public String forgiveDebt(Plot plot, String admin, String reason) {
        if (plot.taxDebt <= 1e-9) {
            return "У участка нет долга по налогу.";
        }
        double debt = plot.taxDebt;
        plot.taxDebt = 0;
        plugin.plots().save();
        plugin.journal().add(plot.id, plot.owner, admin, Journal.Action.TAX,
                "Долг по налогу списан (" + plugin.economy().format(debt) + "): " + reason);
        plugin.service().warn(plot.owner, plot.id, "<green>Долг по налогу участка <white>" + Text.esc(plot.id)
                + "</white> списан администрацией. Причина: " + Text.esc(reason));
        return null;
    }

    // ------------------------------------------------------------ изменение условий (команды и меню)

    private String changed(String actor, String what) {
        save();
        announce(actor, what);
        return what;
    }

    private static String pct(Double v) {
        return v == null ? "сброшено к значению конфига" : v + "%";
    }

    public String setDepositCommission(Double v, String actor) {
        settings.setDepositCommission(v);
        return changed(actor, "комиссия за пополнение счёта: " + pct(v));
    }

    public String setWithdrawCommission(Double v, String actor) {
        settings.setWithdrawCommission(v);
        return changed(actor, "комиссия за снятие со счёта: " + pct(v));
    }

    public String setSeizurePenalty(Double v, String actor) {
        settings.setSeizurePenalty(v);
        return changed(actor, "штраф при изъятии участка: " + pct(v));
    }

    public String setRentTax(Double v, String actor) {
        settings.setRentTax(v);
        return changed(actor, "налог на доход от сдачи в аренду: " + pct(v));
    }

    public String setOnlyInBank(Boolean v, String actor) {
        settings.setOnlyInBank(v);
        return changed(actor, "операции со счётом: " + (v == null ? "по значению конфига"
                : v ? "только в здании банка" : "из любого места"));
    }

    public String setPropertyTax(Category category, Double amount, String actor) {
        settings.setPropertyTax(category.key(), amount);
        return changed(actor, "налог на имущество (" + category.title() + "): "
                + (amount == null ? "сброшен к значению конфига" : plugin.economy().format(amount) + " за период"));
    }

    /** Условия банка изменились: запись в журнал и уведомление всех управляющих. */
    public void announce(String actor, String change) {
        plugin.journal().add("-", null, actor, Journal.Action.BANK_SETTINGS, change);
        java.util.Set<java.util.UUID> owners = new java.util.HashSet<>();
        for (Plot p : plugin.plots().all()) {
            if (!p.isFree() && p.owner != null && owners.add(p.owner)) {
                plugin.service().warn(p.owner, "bank", "<yellow>Банк изменил условия: " + Text.esc(change));
            }
        }
    }

    // ------------------------------------------------------------ проверка счетов

    /** Все счета предприятий (участки с управляющим), по убыванию баланса. */
    public List<Plot> accounts() {
        List<Plot> list = new ArrayList<>();
        for (Plot p : plugin.plots().all()) {
            if (!p.isFree() && p.owner != null) {
                plugin.power().touch(p);
                list.add(p);
            }
        }
        list.sort(Comparator.comparingDouble((Plot p) -> p.account.rawBalance()).reversed());
        return list;
    }

    /** Записи журнала, относящиеся к счёту участка. */
    public List<Journal.Entry> statement(String plotId, int limit) {
        List<Journal.Entry> result = new ArrayList<>();
        for (Journal.Entry e : plugin.journal().forPlot(plotId)) {
            switch (e.action()) {
                case ACCOUNT_DEPOSIT, ACCOUNT_WITHDRAW, ACCOUNT_REFUND, ACCOUNT_PENALTY, ACCOUNT_GIFT, TAX, COMMISSION, TARIFF ->
                        result.add(e);
                default -> { }
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }
}
