package ru.industryreborn.service;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;

/**
 * Учёт выручки магазинов через события ChestShop (необязательная зависимость, через рефлексию).
 * Покупка клиентом в магазине участка засчитывается как доход магазина для процента работников.
 * Не проверено на настоящем ChestShop: имена методов события могут отличаться в разных версиях.
 */
public final class ChestShopHook implements Listener {

    private final IndustryRebornPlugin plugin;
    private boolean failed;

    public ChestShopHook(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    /** Подписывается на TransactionEvent, если ChestShop установлен. Возвращает, получилось ли. */
    @SuppressWarnings("unchecked")
    public boolean register() {
        if (Bukkit.getPluginManager().getPlugin("ChestShop") == null) {
            return false;
        }
        registerBlueprintBlock();
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

    /**
     * Чертежи продают только магазины администрации: сделки ChestShop с чертежом отменяются (событие PreTransactionEvent, стаки берутся через getStock()).
     * Не проверено на настоящем ChestShop: при другом устройстве события блокировка просто не включается.
     */
    @SuppressWarnings("unchecked")
    private void registerBlueprintBlock() {
        try {
            Class<? extends Event> pre = (Class<? extends Event>) Class.forName("com.Acrobot.ChestShop.Events.PreTransactionEvent");
            Bukkit.getPluginManager().registerEvent(pre, this, EventPriority.HIGH, (listener, event) -> {
                if (pre.isInstance(event)) {
                    blockBlueprints(event);
                }
            }, plugin, false);
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("ChestShop: запрет на продажу чертежей не включён: " + e);
        }
    }

    private void blockBlueprints(Event event) {
        try {
            if (plugin.blueprints() == null || !(event instanceof org.bukkit.event.Cancellable cancellable)) {
                return;
            }
            Object stock = event.getClass().getMethod("getStock").invoke(event);
            if (!(stock instanceof org.bukkit.inventory.ItemStack[] stacks)) {
                return;
            }
            for (org.bukkit.inventory.ItemStack s : stacks) {
                if (plugin.blueprints().isBlueprint(s)) {
                    cancellable.setCancelled(true);
                    Object client = event.getClass().getMethod("getClient").invoke(event);
                    if (client instanceof org.bukkit.entity.Player p) {
                        ru.industryreborn.util.Text.err(p, "Чертежи продаёт только магазин администрации: /district blueprint shop");
                    }
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.FINE, "ChestShop: не удалось проверить сделку на чертёж", e);
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
