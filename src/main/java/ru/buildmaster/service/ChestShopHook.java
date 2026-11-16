package ru.buildmaster.service;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;

/**
 * Учёт выручки магазинов через события ChestShop (необязательная зависимость, через рефлексию).
 * Покупка клиентом в магазине участка засчитывается как доход магазина для процента работников.
 * Не проверено на настоящем ChestShop: имена методов события могут отличаться в разных версиях.
 */
public final class ChestShopHook implements Listener {

    private final BuildMasterPlugin plugin;
    private boolean failed;

    public ChestShopHook(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** Подписывается на TransactionEvent, если ChestShop установлен. Возвращает, получилось ли. */
    @SuppressWarnings("unchecked")
    public boolean register() {
        if (Bukkit.getPluginManager().getPlugin("ChestShop") == null) {
            return false;
        }
        try {
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("com.Acrobot.ChestShop.Events.TransactionEvent");
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR,
                    (listener, event) -> {
                        if (type.isInstance(event)) {
                            handle(event);
                        }
                    }, plugin, true);
            plugin.getLogger().info("ChestShop найден: выручка магазинов учитывается для процента работников.");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Не удалось подключиться к ChestShop: " + e);
            return false;
        }
    }

    private void handle(Event event) {
        if (failed) {
            return;
        }
        try {
            Object type = event.getClass().getMethod("getTransactionType").invoke(event);
            if (type == null || !"BUY".equals(String.valueOf(type))) {
                return;     // клиент покупает у магазина: деньги достаются владельцу
            }
            Object sign = event.getClass().getMethod("getSign").invoke(event);
            double price = price(event);
            Location at = sign instanceof org.bukkit.block.BlockState state ? state.getLocation()
                    : sign instanceof Block block ? block.getLocation() : null;
            if (at == null || price <= 0) {
                return;
            }
            Plot plot = plugin.plots().at(at);
            if (plot != null) {
                plugin.jobs().onSale(plot, price);
            }
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            failed = true;
            plugin.getLogger().warning("Событие ChestShop не удалось разобрать, учёт выручки отключён: " + e);
        }
    }

    private static double price(Event event) throws ReflectiveOperationException {
        try {
            Method exact = event.getClass().getMethod("getExactPrice");
            Object value = exact.invoke(event);
            if (value instanceof Number n) {
                return n.doubleValue();
            }
        } catch (NoSuchMethodException ignored) {
            // старые версии: getPrice
        }
        Object value = event.getClass().getMethod("getPrice").invoke(event);
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
