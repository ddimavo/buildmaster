package ru.industryreborn.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/**
 * Счёт предприятия и электричество мастерских.
 * <ul>
 *   <li>DAILY - суточный тариф списывается непрерывно (ленивый расчёт по биллинговым часам);</li>
 *   <li>USAGE - плата за каждую операцию на станке.</li>
 * </ul>
 * Потраченные деньги поступают в казну.
 */
public final class PowerService {

    public enum Mode { DAILY, USAGE }

    private final IndustryRebornPlugin plugin;
    private final File billingFile;
    private final File overridesFile;
    private final File statsFile;
    private BillingClock clock;
    private java.util.function.LongSupplier wallClock = System::currentTimeMillis;
    private boolean treasuryDirty;
    private PowerOverrides overrides = new PowerOverrides();
    private StationStats stats = new StationStats();
    private final File consumptionFile;
    private ConsumptionLog consumption = new ConsumptionLog();

    public PowerService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.billingFile = new File(plugin.getDataFolder(), "billing.yml");
        this.overridesFile = new File(plugin.getDataFolder(), "overrides.yml");
        this.statsFile = new File(plugin.getDataFolder(), "stats.yml");
        this.consumptionFile = new File(plugin.getDataFolder(), "consumption.yml");
    }

    public void load() {
        long saved = billingFile.exists() ? YamlConfiguration.loadConfiguration(billingFile).getLong("millis") : 0;
        clock = new BillingClock(saved, wallClock.getAsLong(), maxStepSeconds() * 1000L);
        overrides = PowerOverrides.load(overridesFile);
        stats = StationStats.load(statsFile);
        consumption = ConsumptionLog.load(consumptionFile);
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

    // ------------------------------------------------------------ настройки: config + переопределения

    public PowerOverrides overrides() {
        return overrides;
    }

    public void saveOverrides() {
        try {
            overrides.save(overridesFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить overrides.yml", e);
        }
    }

    public StationStats stats() {
        return stats;
    }

    public ConsumptionLog consumption() {
        return consumption;
    }

    public Mode mode() {
        String value = overrides.mode() != null ? overrides.mode()
                : plugin.getConfig().getString("electricity.mode", "DAILY");
        try {
            return Mode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mode.DAILY;
        }
    }

    public double base() {
        Double o = overrides.base();
        if (o != null) {
            return o;
        }
        Double auto = plugin.balance().applied("electricity");
        return auto != null ? auto : plugin.getConfig().getDouble("electricity.base-rate-per-day", 100);
    }

    /** Цена электричества задана администратором вручную: автокорректировка её не трогает. */
    public boolean baseIsManual() {
        return overrides.base() != null;
    }

    public double progression() {
        Double o = overrides.progression();
        return o != null ? o : plugin.getConfig().getDouble("electricity.progression-per-extra-same-type", 0.10);
    }

    public double usageBase() {
        Double o = overrides.usageBase();
        return o != null ? o : plugin.getConfig().getDouble("electricity.usage-base", 1.0);
    }

    /** Коэффициент станка: переопределение администратора, иначе значение из config.yml. */
    public double coefficient(String type) {
        Double o = overrides.coefficient(type);
        return o != null ? o : plugin.getConfig().getDouble("electricity.stations." + type, 0);
    }

    public boolean knownType(String type) {
        return overrides.coefficient(type) != null || plugin.getConfig().get("electricity.stations." + type) != null;
    }

    // ------------------------------------------------------------ тариф

    /** Платит ли участок за электричество: мастерская с управляющим. */
    public boolean isBilled(Plot plot) {
        return plugin.modules().enabled(Module.ELECTRICITY) && !plot.isFree()
                && (plot.category == Category.WORKSHOP || isHotel(plot) && hotelElectricity() && plugin.modules().enabled(Module.HOTELS));
    }

    /** Отель (здание, не номер). */
    public static boolean isHotel(Plot plot) {
        return plot.category == Category.HOTEL && plot.parentId == null;
    }

    public boolean hotelElectricity() {
        return plugin.getConfig().getBoolean("hotel-electricity.enabled", true);
    }

    /** Потребление одного занятого номера, V в сутки: начальное значение + рост от площади пола. */
    public double roomUnits(Plot room) {
        double area = (double) room.box.sizeX() * room.box.sizeZ();
        return plugin.getConfig().getDouble("hotel-electricity.base-units", 10)
                + plugin.getConfig().getDouble("hotel-electricity.per-area", 0.5) * area;
    }

    /** Потребление отеля: сумма по занятым номерам. */
    public double hotelUnits(Plot hotel) {
        double sum = 0;
        for (Plot room : plugin.plots().childrenOf(hotel.id)) {
            if (!room.isFree()) {
                sum += roomUnits(room);
            }
        }
        return sum;
    }

    /** Номер заселён или освобождён: тариф отеля пересчитывается. */
    public void roomChanged(Plot room) {
        if (room.parentId == null) {
            return;
        }
        Plot hotel = plugin.plots().get(room.parentId);
        if (hotel != null && isBilled(hotel)) {
            recalc(hotel);
        }
    }

    /** Суточный тариф для набора станков (по ключам тарифа). */
    public double dailyRate(Map<String, Integer> counts, double discountPercent) {
        return PowerTariff.dailyRate(base(), progression(), counts, this::coefficient, discountPercent);
    }

    /** Потребление участка в V в сутки (по установленным станкам). */
    public double unitsFor(Plot plot) {
        if (isHotel(plot)) {
            return isBilled(plot) ? hotelUnits(plot) : 0;
        }
        return isBilled(plot)
                ? PowerTariff.dailyUnits(progression(), plugin.stations().tariffCounts(plot), this::coefficient) : 0;
    }

    /** Подключено ли предприятие к электросети (без электростанций сеть не действует). */
    public boolean isOnGrid(Plot plot) {
        return plugin.grid().onGrid(plot);
    }

    /** Суточный тариф участка с множителем станции; в режиме USAGE непрерывного расхода нет. */
    public double rateFor(Plot plot) {
        if (isHotel(plot)) {
            // у отеля нет станков с операциями: он всегда платит суточный тариф за занятые номера
            if (!isBilled(plot) || !isOnGrid(plot)) {
                return 0;
            }
            double discount = Math.min(100, Math.max(0, plot.discountPercent));
            return base() * hotelUnits(plot) * (1 - discount / 100.0) * plugin.grid().multiplier(plot);
        }
        if (!isBilled(plot) || mode() == Mode.USAGE || !isOnGrid(plot)) {
            return 0;
        }
        return dailyRate(plugin.stations().tariffCounts(plot), plot.discountPercent) * plugin.grid().multiplier(plot);
    }

    /** Цена одной операции на станке (режим USAGE) с учётом скидки участка. */
    public double operationPrice(Plot plot, String tariffKey) {
        double discount = Math.min(100, Math.max(0, plot.discountPercent));
        return usageBase() * coefficient(tariffKey) * (1 - discount / 100.0) * plugin.grid().multiplier(plot);
    }

    // ------------------------------------------------------------ счёт

    /** Фиксирует расход электричества и переводит потраченное в казну. */
    public void touch(Plot plot) {
        long now = nowSec();
        double rate = plot.account.ratePerDay();
        double units = plot.account.unitsPerDay();
        double consumed = plot.account.settle(now);
        if (consumed > 1e-9) {
            plugin.treasury().deposit(consumed, "Электричество участка " + plot.id);
            treasuryDirty = true;
            // потреблённые V пропорциональны потраченным деньгам: тариф = цена за V × потребление × надбавки
            double v = rate > 0 ? consumed * units / rate : 0;
            consumption.record(plot.id, now, v, consumed);
        }
    }

    /** Пересчитывает тариф (после изменения набора станков, смены управляющего и т.п.). */
    public void recalc(Plot plot) {
        recalc(plot, true);
    }

    private void recalc(Plot plot, boolean save) {
        touch(plot);
        plot.account.setUnitsPerDay(unitsFor(plot));
        plot.account.setRate(rateFor(plot));
        if (save) {
            plugin.plots().save();
        }
        plugin.holograms().update(plot);
    }

    /** Работают ли станки участка. Вне мастерских всегда да (обычные блоки). */
    public boolean isPowered(Plot plot) {
        if (!isBilled(plot)) {
            return true;
        }
        if (!isOnGrid(plot)) {
            return false;
        }
        if (mode() == Mode.USAGE && !isHotel(plot)) {
            return plot.account.balanceAt(nowSec()) > 1e-9;
        }
        return plot.account.powered(nowSec());
    }

    /** Почему станки не работают из-за сети (отключено от электростанции), либо null. */
    public String gridOffReason(Plot plot) {
        return plugin.grid().offReason(plot);
    }

    public double balance(Plot plot) {
        return plot.account.balanceAt(nowSec());
    }

    /**
     * Станок собирается выполнить операцию (плавка, варка, ремонт, зачарование).
     * Вне мастерских всегда разрешено. DAILY: только если есть электричество. USAGE: списывает цену операции.
     * Разрешённые операции попадают в статистику.
     */
    public boolean useStation(Plot plot, String tariffKey) {
        if (!isBilled(plot)) {
            return true;
        }
        if (!isOnGrid(plot)) {
            return false;
        }
        if (mode() == Mode.DAILY) {
            if (!plot.account.powered(nowSec())) {
                return false;
            }
            stats.record(plot.id, tariffKey, 0);
            return true;
        }
        double price = operationPrice(plot, tariffKey);
        touch(plot);
        if (plot.account.rawBalance() + 1e-9 < price || (price > 0 && plot.account.rawBalance() <= 1e-9)) {
            return false;
        }
        if (price > 0) {
            plot.account.withdraw(Math.min(price, plot.account.rawBalance()));
            plugin.treasury().deposit(price, "Электричество (операция) участка " + plot.id);
            treasuryDirty = true;
        }
        stats.record(plot.id, tariffKey, price);
        consumption.record(plot.id, nowSec(), coefficient(tariffKey), price);
        return true;
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
        String refusal = plugin.bank().checkOperation(payer);
        if (refusal != null) {
            Text.err(payer, refusal);
            return false;
        }
        if (!plugin.economy().withdraw(payer, amount)) {
            Text.err(payer, "Недостаточно средств. Нужно: " + plugin.economy().format(amount));
            return false;
        }
        double fee = TaxRules.percentOf(amount, plugin.bank().depositCommission());
        if (fee > 0) {
            plugin.treasury().deposit(fee, "Комиссия банка за пополнение, участок " + plot.id);
            treasuryDirty = true;
        }
        touch(plot);
        plot.account.deposit(amount - fee);
        plot.powerWarnLevel = 0;
        plugin.plots().save();
        plugin.holograms().update(plot);
        plugin.journal().add(plot.id, plot.owner, payer.getName(), Journal.Action.ACCOUNT_DEPOSIT,
                plugin.economy().format(amount) + (fee > 0 ? " (комиссия " + plugin.economy().format(fee) + ")" : ""));
        Text.ok(payer, "На счёт участка " + Text.esc(plot.id) + " зачислено " + plugin.economy().format(amount - fee)
                + (fee > 0 ? " (комиссия банка " + plugin.economy().format(fee) + ")" : "")
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
        String refusal = plugin.bank().checkOperation(manager);
        if (refusal != null) {
            Text.err(manager, refusal);
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
        double fee = TaxRules.percentOf(amount, plugin.bank().withdrawCommission());
        if (!plot.account.withdraw(amount)) {
            Text.err(manager, "Не удалось снять деньги.");
            return false;
        }
        if (!plugin.economy().deposit(manager, amount - fee)) {
            plot.account.deposit(amount);   // возврат на счёт: деньги игроку не зачислены
            Text.err(manager, "Не удалось снять деньги.");
            return false;
        }
        if (fee > 0) {
            plugin.treasury().deposit(fee, "Комиссия банка за снятие, участок " + plot.id);
            treasuryDirty = true;
        }
        plugin.plots().save();
        plugin.journal().add(plot.id, manager.getUniqueId(), manager.getName(), Journal.Action.ACCOUNT_WITHDRAW,
                plugin.economy().format(amount) + (fee > 0 ? " (комиссия " + plugin.economy().format(fee) + ")" : ""));
        Text.ok(manager, "Со счёта снято " + plugin.economy().format(amount)
                + (fee > 0 ? ", вы получили " + plugin.economy().format(amount - fee) + " (комиссия банка "
                + plugin.economy().format(fee) + ")" : "") + ".");
        return true;
    }

    /**
     * Подарок или компенсация от администрации: деньги берутся из казны (новые деньги в экономике не создаются).
     * Возвращает текст ошибки или null.
     */
    public String gift(String adminName, Plot plot, double amount, String reason) {
        if (plot.isFree()) {
            return "У свободного участка нет счёта.";
        }
        if (!(amount > 0)) {
            return "Сумма должна быть больше нуля.";
        }
        if (!plugin.treasury().withdraw(amount, adminName, "Подарок на счёт участка " + plot.id + ": " + reason)) {
            return "В казне недостаточно средств (" + plugin.economy().format(plugin.treasury().balance()) + ").";
        }
        plugin.saveTreasury();
        touch(plot);
        plot.account.deposit(amount);
        plot.powerWarnLevel = 0;
        plugin.plots().save();
        plugin.holograms().update(plot);
        plugin.journal().add(plot.id, plot.owner, adminName, Journal.Action.ACCOUNT_GIFT,
                plugin.economy().format(amount) + ": " + reason);
        plugin.service().warn(plot.owner, plot.id, "<green>Администрация зачислила на счёт участка <white>"
                + Text.esc(plot.id) + "</white> " + plugin.economy().format(amount) + ". Причина: " + Text.esc(reason));
        return null;
    }

    /** Индивидуальная скидка участка (0-100 %). Возвращает текст ошибки или null. */
    public String setDiscount(String adminName, Plot plot, double percent) {
        if (percent < 0 || percent > 100 || Double.isNaN(percent)) {
            return "Скидка должна быть от 0 до 100 процентов.";
        }
        touch(plot);
        plot.discountPercent = percent;
        recalc(plot);
        plugin.journal().add(plot.id, plot.owner, adminName, Journal.Action.TARIFF, "Скидка " + percent + "%");
        plugin.service().warn(plot.owner, plot.id, "<green>Для участка <white>" + Text.esc(plot.id)
                + "</white> установлена скидка на электричество: " + percent + "%.");
        return null;
    }

    /**
     * Тарифы изменены администратором: расход по старым тарифам закрывается, включаются новые,
     * владельцы мастерских получают уведомление.
     */
    public void reloadTariffs(String adminName, String what) {
        for (Plot plot : new ArrayList<>(plugin.plots().all())) {
            if (!isBilled(plot)) {
                continue;
            }
            double oldRate = plot.account.ratePerDay();
            recalc(plot, false);
            plot.powerWarnLevel = 0;
            String tariff = mode() == Mode.USAGE ? "оплата по факту использования станков"
                    : plugin.economy().format(plot.account.ratePerDay()) + " в сутки";
            plugin.journal().add(plot.id, plot.owner, adminName, Journal.Action.TARIFF, what);
            plugin.service().warn(plot.owner, plot.id, "<yellow>Тарифы на электричество изменены (" + Text.esc(what)
                    + "). Тариф участка <white>" + Text.esc(plot.id) + "</white>: " + tariff
                    + (oldRate != plot.account.ratePerDay() ? "." : " (без изменений)."));
        }
        plugin.plots().save();
    }

    /**
     * Участок освобождается: остаток счёта возвращается прежнему управляющему.
     * При изъятии за нарушение удерживается штраф (по умолчанию 10%) в пользу казны.
     */
    public void closeAccount(Plot plot, UUID previous, boolean seized) {
        touch(plot);
        double balance = plot.account.rawBalance();
        plot.account.restore(0, 0, plot.account.updatedAtSec());
        plot.account.setUnitsPerDay(0);
        plot.discountPercent = 0;
        // сначала погашается долг по налогам
        if (plot.taxDebt > 1e-9 && balance > 1e-9) {
            double paid = Math.min(balance, plot.taxDebt);
            plugin.treasury().deposit(paid, "Погашение долга по налогу из остатка счёта, участок " + plot.id);
            treasuryDirty = true;
            plugin.journal().add(plot.id, previous, "система", Journal.Action.TAX,
                    "Долг по налогу погашен из остатка счёта: " + plugin.economy().format(paid));
            balance -= paid;
        }
        plot.taxDebt = 0;
        plot.nextTaxAt = 0;
        if (previous == null || balance <= 1e-9) {
            return;
        }
        double penalty = 0;
        if (seized) {
            penalty = TaxRules.percentOf(balance, plugin.bank().seizurePenalty());
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
        for (Plot plot : new ArrayList<>(plugin.plots().all())) {
            if (!isBilled(plot) || plot.restorePending || plot.owner == null) {
                continue;
            }
            touch(plot);
            if (mode() == Mode.USAGE && !isHotel(plot)) {
                warnUsage(plot);
            } else {
                warnDaily(plot);
            }
        }
        if (treasuryDirty) {
            plugin.saveTreasury();
            treasuryDirty = false;
        }
        if (consumption.isDirty()) {
            try {
                consumption.save(consumptionFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Не удалось сохранить consumption.yml", e);
            }
        }
        if (stats.isDirty()) {
            try {
                stats.save(statsFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Не удалось сохранить stats.yml", e);
            }
        }
    }

    private void warnDaily(Plot plot) {
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

    /** USAGE: предупреждаем, когда на счёте остаётся меньше, чем на 20 самых дорогих операций, и когда не хватает на операцию. */
    private void warnUsage(Plot plot) {
        double maxPrice = 0;
        for (String type : plugin.stations().tariffCounts(plot).keySet()) {
            maxPrice = Math.max(maxPrice, operationPrice(plot, type));
        }
        if (maxPrice <= 0) {
            plot.powerWarnLevel = 0;
            return;
        }
        double balance = plot.account.rawBalance();
        if (balance < maxPrice) {
            if (plot.powerWarnLevel < 3) {
                plot.powerWarnLevel = 3;
                plugin.service().warn(plot.owner, plot.id, "<red>На счёте участка <white>" + Text.esc(plot.id)
                        + "</white> не хватает денег на операции станков. Пополните счёт: /district account deposit <сумма>");
            }
        } else if (balance < maxPrice * 20) {
            if (plot.powerWarnLevel < 1) {
                plot.powerWarnLevel = 1;
                plugin.service().warn(plot.owner, plot.id, "<yellow>На счёте участка <white>" + Text.esc(plot.id)
                        + "</white> осталось мало денег на операции станков.");
            }
        } else {
            plot.powerWarnLevel = 0;
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
