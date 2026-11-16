package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

/**
 * Производство в мастерских: линии перерабатывают сырьё со склада в продукцию (буфер ограничен),
 * работают сами, пока есть электричество. Улучшения мастерской дают больше линий, скорость и вместимость.
 * Сырьё не появляется из ничего: каждая партия потребляет входной материал.
 */
public final class FactoryService {

    private final BuildMasterPlugin plugin;
    private long lastTickSec = Long.MIN_VALUE;

    public FactoryService(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ вместимость и скорость

    private double timeFactor() {
        return Math.max(0, plugin.getConfig().getDouble("production.time-factor", 1.0));
    }

    public int stockCapacity(Plot plot) {
        return ProductionMath.capacity(plugin.getConfig().getInt("production.stock-items", 256),
                plugin.upgrades().of(plot).stock());
    }

    /** Вместимость буфера продукции в предметах для линии. */
    public int bufferCapacity(Plot plot, ProductionCatalog.Line def) {
        int baseBatches = plugin.getConfig().getInt("production.buffer-batches", 64);
        return ProductionMath.capacity(baseBatches * def.outputAmount(), plugin.upgrades().of(plot).buffer());
    }

    /** Время партии на линии с учётом станков и улучшений. */
    public double secondsPerBatch(Plot plot, ProductionCatalog.Line def) {
        return ProductionMath.secondsPerBatch(def.seconds(), plugin.stations().countByTariffKey(plot, def.station()),
                plugin.upgrades().of(plot).speed(), timeFactor());
    }

    // ------------------------------------------------------------ проверки

    private String checkManager(Player manager, Plot plot) {
        if (plot.category != Category.WORKSHOP) {
            return "Производство есть только у мастерских.";
        }
        if (!plot.isOwner(manager.getUniqueId())) {
            return "Производством управляет только управляющий мастерской.";
        }
        if (plugin.service().isLocked(plot)) {
            return "Мастерская сейчас обрабатывается, попробуйте позже.";
        }
        return null;
    }

    /** Платёж со счёта предприятия в казну. Возвращает ошибку или null. */
    private String payFromAccount(Plot plot, double price, String reason) {
        if (price <= 0) {
            return null;
        }
        plugin.power().touch(plot);
        if (plot.account.rawBalance() + 1e-9 < price) {
            return "На счёте участка не хватает денег: нужно " + plugin.economy().format(price)
                    + ", есть " + plugin.economy().format(plot.account.rawBalance())
                    + ". Пополните счёт: /district account deposit <сумма>";
        }
        plot.account.withdraw(price);
        plugin.treasury().deposit(price, reason);
        plugin.saveTreasury();
        return null;
    }

    // ------------------------------------------------------------ линии

    /** Устанавливает линию. Нужен станок для неё и свободный слот (зависит от уровня). */
    public String install(Player manager, Plot plot, String lineId) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        ProductionCatalog.Line def = plugin.production().get(lineId);
        if (def == null) {
            return "Неизвестная линия. Список: /district factory info";
        }
        if (plot.lines.containsKey(def.id())) {
            return "Эта линия уже установлена.";
        }
        int max = plugin.upgrades().of(plot).maxLines();
        if (plot.lines.size() >= max) {
            return "Все слоты линий заняты (" + max + "). Улучшите мастерскую: /district factory upgrade";
        }
        if (plugin.stations().countByTariffKey(plot, def.station()) <= 0) {
            return "Для этой линии нужен станок типа «" + def.station() + "».";
        }
        String pay = payFromAccount(plot, def.installPrice(), "Установка линии " + def.id() + ", участок " + plot.id);
        if (pay != null) {
            return pay;
        }
        plot.lines.put(def.id(), new Plot.Line());
        plugin.plots().save();
        plugin.journal().add(plot.id, manager.getUniqueId(), manager.getName(), Journal.Action.FACTORY,
                "Установлена линия «" + def.title() + "» за " + plugin.economy().format(def.installPrice()));
        Text.ok(manager, "Линия «" + Text.esc(def.title()) + "» установлена. Загрузите сырьё: /district factory load " + plot.id + " " + def.id());
        return null;
    }

    /** Снимает линию: запас сырья и продукция возвращаются управляющему. Стоимость установки не возвращается. */
    public String remove(Player manager, Plot plot, String lineId) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        ProductionCatalog.Line def = plugin.production().get(lineId);
        Plot.Line line = def == null ? null : plot.lines.get(def.id());
        if (line == null) {
            return "Такая линия не установлена.";
        }
        plot.lines.remove(def.id());
        plugin.plots().save();
        List<ItemStack> items = new ArrayList<>();
        items.addAll(ServiceCatalog.split(new ItemStack(def.input()), line.input));
        items.addAll(ServiceCatalog.split(new ItemStack(def.output()), line.output));
        plugin.orders().deliver(manager.getUniqueId(), plot.id, items, "Снятие линии «" + def.title() + "»");
        plugin.journal().add(plot.id, manager.getUniqueId(), manager.getName(), Journal.Action.FACTORY,
                "Линия «" + def.title() + "» снята, возвращено сырья " + line.input + " и продукции " + line.output);
        Text.ok(manager, "Линия снята. Сырьё и продукция возвращены (в инвентарь или хранилище).");
        return null;
    }

    /** Загружает сырьё из инвентаря на склад линии (только обычные предметы без названий и свойств). */
    public String load(Player manager, Plot plot, String lineId, int requested) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        ProductionCatalog.Line def = plugin.production().get(lineId);
        Plot.Line line = def == null ? null : plot.lines.get(def.id());
        if (line == null) {
            return "Такая линия не установлена.";
        }
        int space = stockCapacity(plot) - line.input;
        if (space <= 0) {
            return "Склад линии полон (" + stockCapacity(plot) + ").";
        }
        PlayerInventory inv = manager.getInventory();
        // подходят только обычные предметы: без названий, чар и других свойств (иначе они потеряются при переработке);
        // сырьём считается и сама руда (например, сырое железо), и блок руды
        int available = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s != null && def.accepts(s.getType()) && s.isSimilar(new ItemStack(s.getType()))) {
                available += s.getAmount();
            }
        }
        int amount = Math.min(space, available);
        if (requested > 0) {
            amount = Math.min(amount, requested);
        }
        if (amount <= 0) {
            return "В инвентаре нет подходящего сырья: " + def.inputNames() + " (обычные предметы без названий и свойств).";
        }
        // сначала списываем основное сырьё линии, потом остальные виды
        int left = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (Material m : def.inputs()) {
            ItemStack plain = new ItemStack(m);
            for (int i = 0; i < contents.length && left > 0; i++) {
                ItemStack s = contents[i];
                if (s != null && s.isSimilar(plain)) {
                    int take = Math.min(left, s.getAmount());
                    s.setAmount(s.getAmount() - take);
                    inv.setItem(i, s.getAmount() <= 0 ? null : s);
                    left -= take;
                }
            }
        }
        line.input += amount;
        plugin.plots().save();
        Text.ok(manager, "На склад линии «" + Text.esc(def.title()) + "» загружено " + amount + ". Всего: " + line.input
                + " из " + stockCapacity(plot) + ".");
        return null;
    }

    /** Забирает готовую продукцию линии (или всех линий, если lineId = null). */
    public String collect(Player manager, Plot plot, String lineId) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        List<ItemStack> items = new ArrayList<>();
        int total = 0;
        for (var e : plot.lines.entrySet()) {
            if (lineId != null && !e.getKey().equalsIgnoreCase(lineId)) {
                continue;
            }
            ProductionCatalog.Line def = plugin.production().get(e.getKey());
            if (def == null || e.getValue().output <= 0) {
                continue;
            }
            items.addAll(ServiceCatalog.split(new ItemStack(def.output()), e.getValue().output));
            total += e.getValue().output;
            e.getValue().output = 0;
        }
        if (total == 0) {
            return "Готовой продукции пока нет.";
        }
        plugin.plots().save();
        plugin.orders().deliver(manager.getUniqueId(), plot.id, items, "Продукция производства, участок " + plot.id);
        Text.ok(manager, "Забрано продукции: " + total + ". Если не хватило места, остальное в хранилище: /district storage");
        return null;
    }

    // ------------------------------------------------------------ улучшения

    /** Повышает уровень мастерской; оплата со счёта предприятия в казну. */
    public String upgrade(Player manager, Plot plot) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        UpgradeCatalog.Level next = plugin.upgrades().next(plot);
        if (next == null) {
            return "Достигнут максимальный уровень мастерской.";
        }
        String pay = payFromAccount(plot, next.price(), "Улучшение мастерской " + plot.id + " до уровня " + next.level());
        if (pay != null) {
            return pay;
        }
        plot.level = next.level();
        plugin.plots().save();
        plugin.journal().add(plot.id, manager.getUniqueId(), manager.getName(), Journal.Action.UPGRADE,
                "Уровень " + next.level() + " за " + plugin.economy().format(next.price()));
        Text.ok(manager, "Мастерская улучшена до уровня <white>" + next.level() + "</white>: линий " + next.maxLines()
                + ", скорость ×" + next.speed() + ", склад ×" + next.stock() + ", буфер ×" + next.buffer() + ".");
        return null;
    }

    // ------------------------------------------------------------ закрытие мастерской

    /**
     * Мастерская закрывается: сырьё и продукция возвращаются управляющему (при изъятии за нарушение - арестовываются),
     * улучшения сбрасываются.
     */
    public void closeWorkshop(Plot plot, UUID manager, boolean arrest, String reason) {
        List<ItemStack> items = new ArrayList<>();
        for (var e : plot.lines.entrySet()) {
            ProductionCatalog.Line def = plugin.production().get(e.getKey());
            if (def == null) {
                continue;
            }
            items.addAll(ServiceCatalog.split(new ItemStack(def.input()), e.getValue().input));
            items.addAll(ServiceCatalog.split(new ItemStack(def.output()), e.getValue().output));
        }
        if (manager != null && !items.isEmpty()) {
            if (arrest) {
                plugin.storage().add(manager, plot.id, items, ru.buildmaster.model.StorageEntry.State.ARRESTED, reason,
                        "factory-" + UUID.randomUUID());
            } else {
                plugin.orders().deliver(manager, plot.id, items, "Производство закрыто: мастерская " + plot.id);
            }
        }
        plot.lines.clear();
        plot.level = 0;
    }

    // ------------------------------------------------------------ работа линий

    /** Периодическая задача: линии работают по биллинговому времени, пока в мастерской есть электричество. */
    public void tick() {
        long now = plugin.power().nowSec();
        double dt = lastTickSec == Long.MIN_VALUE ? 0 : Math.max(0, now - lastTickSec);
        lastTickSec = now;
        boolean changed = false;
        for (Plot plot : plugin.plots().all()) {
            if (plot.lines.isEmpty() || plot.restorePending || plugin.service().isLocked(plot)
                    || plot.isFree() || !plugin.power().isPowered(plot)) {
                continue;
            }
            for (var e : plot.lines.entrySet()) {
                ProductionCatalog.Line def = plugin.production().get(e.getKey());
                if (def != null) {
                    changed |= run(plot, def, e.getValue(), dt);
                }
            }
        }
        if (changed) {
            plugin.plots().save();
        }
    }

    private boolean run(Plot plot, ProductionCatalog.Line def, Plot.Line line, double dt) {
        double eff = secondsPerBatch(plot, def);
        int free = bufferCapacity(plot, def) - line.output;
        if (Double.isInfinite(eff) || line.input < def.inputAmount() || free < def.outputAmount()) {
            boolean idle = line.progress != 0;
            line.progress = 0; // простой время не копит
            return idle;
        }
        line.progress += dt;
        int possible = ProductionMath.batches(line.progress, eff, line.input, def.inputAmount(), free, def.outputAmount());
        int done = 0;
        while (done < possible && plugin.power().useStation(plot, def.station())) {
            done++;
        }
        line.input -= done * def.inputAmount();
        line.output += done * def.outputAmount();
        line.progress -= done * eff;
        boolean exhausted = line.input < def.inputAmount() || line.output + def.outputAmount() > bufferCapacity(plot, def);
        // время не копится впрок: без сырья или места в буфере линия простаивает, после остановки - не больше одной партии
        line.progress = exhausted ? 0 : Math.min(line.progress, eff);
        return done > 0 || dt > 0;
    }
}
