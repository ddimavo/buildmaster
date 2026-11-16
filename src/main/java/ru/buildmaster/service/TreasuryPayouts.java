package ru.buildmaster.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

/**
 * Расходы казны: субсидии на развитие, призы за мероприятия и прочие выплаты. Казна - фонд администрации: деньги не печатаются,
 * тратятся только имеющиеся. Каждая выплата проверяется (сумма, причина, лимиты), записывается в казну и в журнал.
 */
public final class TreasuryPayouts {

    public enum Kind {
        SUBSIDY("Субсидия"), PRIZE("Приз"), PAYOUT("Выплата");

        private final String title;

        Kind(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** Отчёт казны за период: приходы и расходы по статьям. */
    public record Report(int days, Map<String, Double> income, Map<String, Double> expense) {
        public double totalIncome() {
            return income.values().stream().mapToDouble(Double::doubleValue).sum();
        }

        public double totalExpense() {
            return expense.values().stream().mapToDouble(Double::doubleValue).sum();
        }
    }

    private static final String[] ARTICLES = {"Электричество", "Комиссия", "Налог", "Аренда", "Покупка", "Штраф", "Субсидия", "Приз",
        "Выплата", "Погашение", "Возврат", "Аукцион"};

    private final BuildMasterPlugin plugin;

    public TreasuryPayouts(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    public double maxSingle() {
        return plugin.getConfig().getDouble("treasury.max-single-payout", 0);
    }

    public double dailyLimit() {
        return plugin.getConfig().getDouble("treasury.daily-limit", 0);
    }

    public int minReason() {
        return Math.max(1, plugin.getConfig().getInt("treasury.min-reason-length", 3));
    }

    /** Расходы казны за последние сутки. */
    public double spentToday() {
        long since = System.currentTimeMillis() - 86_400_000L;
        double sum = 0;
        for (Treasury.Tx tx : plugin.treasury().log()) {
            if (tx.amount() < 0 && tx.time() >= since) {
                sum -= tx.amount();
            }
        }
        return sum;
    }

    /** Проверка выплаты: null - можно. */
    public String check(double amount, String reason) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            return "Сумма должна быть больше нуля.";
        }
        if (reason == null || reason.trim().length() < minReason()) {
            return "Укажите причину выплаты (не короче " + minReason() + " символов): она попадает в журнал.";
        }
        if (maxSingle() > 0 && amount > maxSingle() + 1e-9) {
            return "Одна выплата не может превышать " + plugin.economy().format(maxSingle()) + ".";
        }
        if (dailyLimit() > 0 && spentToday() + amount > dailyLimit() + 1e-9) {
            return "Суточный лимит расходов казны " + plugin.economy().format(dailyLimit()) + " (уже потрачено "
                    + plugin.economy().format(spentToday()) + ").";
        }
        if (amount > plugin.treasury().balance() + 1e-9) {
            return "В казне недостаточно средств: " + plugin.economy().format(plugin.treasury().balance()) + ".";
        }
        return null;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Субсидия на счёт предприятия. Возвращает ошибку или null. */
    public String subsidy(String actor, Plot plot, double amount, String reason) {
        amount = round(amount);
        if (plot == null) {
            return "Участок не найден.";
        }
        if (plot.isFree()) {
            return "У свободного участка нет счёта: субсидию получает только предприятие с управляющим.";
        }
        String error = check(amount, reason);
        if (error != null) {
            return error;
        }
        reason = reason.trim();
        if (!plugin.treasury().withdraw(amount, actor, "Субсидия участку " + plot.id + ": " + reason)) {
            return "В казне недостаточно средств.";
        }
        plugin.power().touch(plot);
        plot.account.deposit(amount);
        plot.powerWarnLevel = 0;
        plugin.power().recalc(plot);
        plugin.saveTreasury();
        plugin.journal().add(plot.id, plot.owner, actor, Journal.Action.SUBSIDY,
                plugin.economy().format(amount) + ": " + reason);
        plugin.service().warn(plot.owner, plot.id, "<green>Администрация выделила участку <white>" + Text.esc(plot.id)
                + "</white> субсидию " + plugin.economy().format(amount) + ": " + Text.esc(reason));
        return null;
    }

    /** Приз игроку за мероприятие. Возвращает ошибку или null. */
    public String prize(String actor, OfflinePlayer target, double amount, String event) {
        return pay(actor, target, amount, event, Kind.PRIZE);
    }

    /** Прочая выплата игроку. */
    public String payout(String actor, OfflinePlayer target, double amount, String reason) {
        return pay(actor, target, amount, reason, Kind.PAYOUT);
    }

    private String pay(String actor, OfflinePlayer target, double amount, String reason, Kind kind) {
        amount = round(amount);
        if (target == null || target.getName() == null) {
            return "Игрок не найден.";
        }
        String error = check(amount, reason);
        if (error != null) {
            return error;
        }
        reason = reason.trim();
        if (!plugin.economy().available()) {
            return "Экономика недоступна: не найден плагин с поддержкой Vault.";
        }
        if (!plugin.treasury().withdraw(amount, actor, kind.title() + " " + target.getName() + ": " + reason)) {
            return "В казне недостаточно средств.";
        }
        if (!plugin.economy().deposit(target, amount)) {
            plugin.treasury().deposit(amount, "Возврат: " + kind.title().toLowerCase() + " не прошла");
            plugin.saveTreasury();
            return "Не удалось зачислить деньги игроку.";
        }
        plugin.saveTreasury();
        plugin.journal().add("-", target.getUniqueId(), actor, kind == Kind.PRIZE ? Journal.Action.PRIZE : Journal.Action.TREASURY,
                plugin.economy().format(amount) + ": " + reason);
        Player online = Bukkit.getPlayer(target.getUniqueId());
        if (online != null) {
            Text.send(online, "<green>" + (kind == Kind.PRIZE ? "Вы получили приз " : "Вам выплачено ") + plugin.economy().format(amount)
                    + " из казны: " + Text.esc(reason));
        }
        return null;
    }

    /** Статья отчёта по тексту причины. */
    public static String article(String reason) {
        for (String a : ARTICLES) {
            if (reason.startsWith(a)) {
                return a;
            }
        }
        return "Прочее";
    }

    /** Приходы и расходы казны за последние days суток по статьям. */
    public Report report(int days) {
        long since = System.currentTimeMillis() - Math.max(1, days) * 86_400_000L;
        Map<String, Double> income = new LinkedHashMap<>();
        Map<String, Double> expense = new LinkedHashMap<>();
        List<Treasury.Tx> log = plugin.treasury().log();
        for (Treasury.Tx tx : log) {
            if (tx.time() < since) {
                continue;
            }
            Map<String, Double> bucket = tx.amount() >= 0 ? income : expense;
            bucket.merge(article(tx.reason()), Math.abs(tx.amount()), Double::sum);
        }
        return new Report(days, income, expense);
    }
}
