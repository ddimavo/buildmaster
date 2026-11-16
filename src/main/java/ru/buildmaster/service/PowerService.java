package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

/**
 * Счёт предприятия и электричество мастерских. Расход вычисляется лениво по биллинговым часам.
 * Потраченные деньги поступают в казну.
 */
public final class PowerService {

    private final BuildMasterPlugin plugin;
    private final File billingFile;
    private BillingClock clock;
    private java.util.function.LongSupplier wallClock = System::currentTimeMillis;
    private boolean treasuryDirty;

    public PowerService(BuildMasterPlugin plugin) {
        this.plugin = plugin;
        this.billingFile = new File(plugin.getDataFolder(), "billing.yml");
    }

    public void load() {
        long saved = billingFile.exists() ? YamlConfiguration.loadConfiguration(billingFile).getLong("millis") : 0;
        clock = new BillingClock(saved, wallClock.getAsLong(), maxStepSeconds() * 1000L);
    }

    /** Только для тестов: подмена источника времени (биллинговые часы сохраняют накопленное). */
    public void setTimeSource(java.util.function.LongSupplier source) {
        this.wallClock = source;
        this.clock = new BillingClock(clock.millis(), source.getAsLong(), maxStepSeconds() * 1000L);
    }

    private long maxStepSeconds() {
        return Math.max(1, plugin.getConfig().getLong("electricity.max-clock-step-seconds", 120));
    }

    /** Текущее биллинговое время в секундах. */
    public long nowSec() {
        clock.advance(wallClock.getAsLong());
        return clock.seconds();
    }

    private void saveClock() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("millis", clock.millis());
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(billingFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось сохранить billing.yml", e);
        }
    }

    // ------------------------------------------------------------ тариф

    /** Платит ли участок за электричество: мастерская с управляющим. */
    public boolean isBilled(Plot plot) {
        return plot.category == Category.WORKSHOP && !plot.isFree();
    }

    public double coefficient(String type) {
        return plugin.getConfig().getDouble("electricity.stations." + type, 0);
    }

    /** Суточный тариф для набора станков (по ключам тарифа). */
    public double dailyRate(Map<String, Integer> counts, double discountPercent) {
        return PowerTariff.dailyRate(plugin.getConfig().getDouble("electricity.base-rate-per-day", 100),
                plugin.getConfig().getDouble("electricity.progression-per-extra-same-type", 0.10),
                counts, this::coefficient, discountPercent);
    }

    public double rateFor(Plot plot) {
        return isBilled(plot) ? dailyRate(plugin.stations().tariffCounts(plot), 0) : 0;
    }

    // ------------------------------------------------------------ счёт

    /** Фиксирует расход электричества и переводит потраченное в казну. */
    public void touch(Plot plot) {
        double consumed = plot.account.settle(nowSec());
        if (consumed > 1e-9) {
            plugin.treasury().deposit(consumed, "Электричество участка " + plot.id);
            treasuryDirty = true;
        }
    }

    /** Пересчитывает тариф (после изменения набора станков, смены управляющего и т.п.). */
    public void recalc(Plot plot) {
        touch(plot);
        plot.account.setRate(rateFor(plot));
        plugin.plots().save();
        plugin.holograms().update(plot);
    }

    /** Работают ли станки участка. Вне мастерских всегда да (обычные блоки). */
    public boolean isPowered(Plot plot) {
        return !isBilled(plot) || plot.account.powered(nowSec());
    }

    public double balance(Plot plot) {
        return plot.account.balanceAt(nowSec());
    }

    /** Любой игрок может пополнить счёт предприятия. */
    public boolean deposit(Player payer, Plot plot, double amount) {
        if (!plugin.economy().available()) {
            Text.err(payer, "Экономика недоступна: не найден плагин с поддержкой Vault.");
            return false;
        }
        if (plot.isFree()) {
            Text.err(payer, "У свободного участка нет счёта.");
            return false;
        }
        if (!(amount > 0)) {
            Text.err(payer, "Сумма должна быть больше нуля.");
            return false;
        }
        if (!plugin.economy().withdraw(payer, amount)) {
            Text.err(payer, "Недостаточно средств. Нужно: " + plugin.economy().format(amount));
            return false;
        }
        touch(plot);
        plot.account.deposit(amount);
        plot.powerWarnLevel = 0;
        plugin.plots().save();
        plugin.holograms().update(plot);
        plugin.journal().add(plot.id, plot.owner, payer.getName(), Journal.Action.ACCOUNT_DEPOSIT,
                plugin.economy().format(amount));
        Text.ok(payer, "На счёт участка " + Text.esc(plot.id) + " внесено " + plugin.economy().format(amount)
                + ". Баланс: " + plugin.economy().format(balance(plot)) + ".");
        return true;
    }

    /** Снимать со счёта может управляющий; в мастерской остаётся резерв на сутки электричества. */
    public boolean withdraw(Player manager, Plot plot, double amount) {
        if (!plot.isOwner(manager.getUniqueId())) {
            Text.err(manager, "Распоряжаться счётом может только управляющий.");
            return false;
        }
        if (!(amount > 0)) {
            Text.err(manager, "Сумма должна быть больше нуля.");
            return false;
        }
        touch(plot);
        double reserve = isBilled(plot) ? plot.account.ratePerDay() : 0;
        double available = Math.max(0, plot.account.rawBalance() - reserve);
        if (amount > available + 1e-9) {
            Text.err(manager, "Можно снять не больше " + plugin.economy().format(available)
                    + (reserve > 0 ? " (резерв на сутки электричества: " + plugin.economy().format(reserve) + ")." : "."));
            return false;
        }
        if (!plot.account.withdraw(amount) || !plugin.economy().deposit(manager, amount)) {
            Text.err(manager, "Не удалось снять деньги.");
            return false;
        }
        plugin.plots().save();
        plugin.journal().add(plot.id, manager.getUniqueId(), manager.getName(), Journal.Action.ACCOUNT_WITHDRAW,
                plugin.economy().format(amount));
        Text.ok(manager, "Со счёта снято " + plugin.economy().format(amount) + ".");
        return true;
    }

    /**
     * Участок освобождается: остаток счёта возвращается прежнему управляющему.
     * При изъятии за нарушение удерживается штраф (по умолчанию 10%) в пользу казны.
     */
    public void closeAccount(Plot plot, UUID previous, boolean seized) {
        touch(plot);
        double balance = plot.account.rawBalance();
        plot.account.restore(0, 0, plot.account.updatedAtSec());
        if (previous == null || balance <= 1e-9) {
            return;
        }
        double penalty = 0;
        if (seized) {
            penalty = balance * plugin.getConfig().getDouble("electricity.seizure-penalty-percent", 10) / 100.0;
            plugin.treasury().deposit(penalty, "Штраф при изъятии участка " + plot.id);
            treasuryDirty = true;
        }
        double refund = balance - penalty;
        OfflinePlayer owner = Bukkit.getOfflinePlayer(previous);
        boolean paid = refund <= 0 || plugin.economy().deposit(owner, refund);
        if (!paid) {
            // не удалось вернуть - деньги остаются в казне до разбирательства
            plugin.treasury().deposit(refund, "Не возвращён остаток счёта участка " + plot.id + " (" + owner.getName() + ")");
            treasuryDirty = true;
            plugin.getLogger().warning("Не удалось вернуть остаток счёта " + plot.id + " игроку " + owner.getName());
        }
        if (penalty > 0) {
            plugin.journal().add(plot.id, previous, "система", Journal.Action.ACCOUNT_PENALTY,
                    "Штраф " + plugin.economy().format(penalty) + " при изъятии участка");
        }
        if (refund > 0 && paid) {
            plugin.journal().add(plot.id, previous, "система", Journal.Action.ACCOUNT_REFUND,
                    "Возвращён остаток счёта: " + plugin.economy().format(refund));
            if (owner.getPlayer() != null) {
                Text.send(owner.getPlayer(), "<green>Остаток счёта участка <white>" + Text.esc(plot.id)
                        + "</white> возвращён: " + plugin.economy().format(refund)
                        + (penalty > 0 ? " (удержан штраф " + plugin.economy().format(penalty) + ")" : "") + ".");
            }
        }
        plugin.plots().save();
    }

    // ------------------------------------------------------------ периодические задачи

    public void tick() {
        nowSec();
        saveClock();
        for (Plot plot : new java.util.ArrayList<>(plugin.plots().all())) {
            if (!isBilled(plot) || plot.restorePending || plot.owner == null) {
                continue;
            }
            touch(plot);
            long now = nowSec();
            double hours = plot.account.secondsLeft(now) / 3600.0;
            if (!plot.account.powered(now)) {
                if (plot.powerWarnLevel < 3) {
                    plot.powerWarnLevel = 3;
                    plugin.service().warn(plot.owner, plot.id,
                            "<red>Электричество на участке <white>" + Text.esc(plot.id)
                                    + "</white> закончилось: станки не работают. Пополните счёт: /district account deposit <сумма>");
                }
            } else if (hours <= 6 && plot.powerWarnLevel < 2) {
                plot.powerWarnLevel = 2;
                plugin.service().warn(plot.owner, plot.id, "<red>Электричества на участке <white>" + Text.esc(plot.id)
                        + "</white> хватит меньше чем на 6 часов.");
            } else if (hours <= 24 && plot.powerWarnLevel < 1) {
                plot.powerWarnLevel = 1;
                plugin.service().warn(plot.owner, plot.id, "<yellow>Электричества на участке <white>" + Text.esc(plot.id)
                        + "</white> хватит меньше чем на сутки.");
            } else if (hours > 24) {
                plot.powerWarnLevel = 0;
            }
        }
        if (treasuryDirty) {
            plugin.saveTreasury();
            treasuryDirty = false;
        }
    }

    /** Подсчёт стоимости для команды power calc. */
    public Map<String, Double> breakdown(Map<String, Integer> counts) {
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            result.put(e.getKey(), dailyRate(Map.of(e.getKey(), e.getValue()), 0));
        }
        return result;
    }
}
