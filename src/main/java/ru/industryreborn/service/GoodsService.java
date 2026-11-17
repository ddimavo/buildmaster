package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/**
 * Товары предприятий и витрина. Магазин и мастерская продают товары по цене управляющего, склад только хранит. Товары попадают
 * на витрину вручную (положить из инвентаря) либо автоматически по связи с мастерской. Деньги от продажи идут на счёт
 * предприятия за вычетом налога с продаж (в казну).
 */
public final class GoodsService {

    private final IndustryRebornPlugin plugin;

    public GoodsService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ правила

    /** Вместимость склада товаров предприятия данного типа (0 - товаров нет). */
    public int capacity(Plot plot) {
        return Math.max(0, plugin.getConfig().getInt("goods.capacity." + plot.category.key(), switch (plot.category) {
            case SHOP -> 2048;
            case WORKSHOP -> 1024;
            case WAREHOUSE -> 8192;
            default -> 0;
        }));
    }

    public boolean holdsGoods(Plot plot) {
        return plugin.modules().enabled(ru.industryreborn.service.Module.GOODS) && plot.category == Category.SHOP || plot.category == Category.WORKSHOP || plot.category == Category.WAREHOUSE;
    }

    /** Продаёт ли предприятие товары покупателям (склад только хранит). */
    public boolean sells(Plot plot) {
        return plugin.modules().enabled(ru.industryreborn.service.Module.GOODS) && !plot.isFree() && (plot.category == Category.SHOP || plot.category == Category.WORKSHOP);
    }

    public int total(Plot plot) {
        return plot.goods.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int stock(Plot plot, Material m) {
        return plot.goods.getOrDefault(m, 0);
    }

    /** Цена за штуку: назначенная управляющим либо цена по умолчанию из конфига (0 - не продаётся). */
    public double price(Plot plot, Material m) {
        Double own = plot.prices.get(m);
        if (own != null) {
            return own;
        }
        return Math.max(0, plugin.getConfig().getDouble("goods.default-price", 0));
    }

    /** Товары с положительной ценой (то, что можно купить), по названию. */
    public List<Material> listed(Plot plot) {
        List<Material> list = new ArrayList<>();
        for (Material m : plot.goods.keySet()) {
            if (price(plot, m) > 0) {
                list.add(m);
            }
        }
        list.sort(Comparator.comparing(Material::name));
        return list;
    }

    public double salesTaxPercent() {
        return Math.max(0, Math.min(100, plugin.getConfig().getDouble("goods.sales-tax-percent", 0)));
    }

    private String checkManager(Player manager, Plot plot) {
        if (!holdsGoods(plot)) {
            return "У этого типа предприятия нет товаров.";
        }
        if (plot.isFree() || (!plot.isOwner(manager.getUniqueId()) && !manager.hasPermission("industryreborn.admin"))) {
            return "Товарами управляет управляющий предприятия.";
        }
        if (plugin.service().isLocked(plot)) {
            return "Участок сейчас обрабатывается.";
        }
        return null;
    }

    private static boolean plain(ItemStack s) {
        return s != null && !s.getType().isAir() && s.isSimilar(new ItemStack(s.getType()));
    }

    // ------------------------------------------------------------ склад товаров

    /** Добавляет товар (автоматически, по связи). Возвращает, сколько поместилось. */
    public int add(Plot plot, Material m, int amount) {
        int room = capacity(plot) - total(plot);
        int put = Math.max(0, Math.min(room, amount));
        if (put > 0) {
            plot.goods.merge(m, put, Integer::sum);
        }
        return put;
    }

    /** Кладёт обычные предметы из инвентаря в товары (requested <= 0 - всё подходящее, мешающие предметы с названиями пропускаются). */
    public String deposit(Player manager, Plot plot, Material only, int requested) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.GOODS);
        if (moduleOff != null) {
            return moduleOff;
        }
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        int room = capacity(plot) - total(plot);
        if (room <= 0) {
            return "Склад товаров полон (" + capacity(plot) + ").";
        }
        int limit = requested > 0 ? Math.min(requested, room) : room;
        int moved = 0;
        var inv = manager.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && moved < limit; i++) {
            ItemStack s = contents[i];
            if (!plain(s) || (only != null && s.getType() != only)) {
                continue;
            }
            int take = Math.min(limit - moved, s.getAmount());
            plot.goods.merge(s.getType(), take, Integer::sum);
            moved += take;
            s.setAmount(s.getAmount() - take);
            inv.setItem(i, s.getAmount() <= 0 ? null : s);
        }
        if (moved == 0) {
            return "В инвентаре нет подходящих обычных предметов" + (only == null ? "" : " (" + only.name() + ")") + ".";
        }
        plugin.plots().save();
        Text.ok(manager, "Выложено на склад товаров: " + moved + ". Всего: " + total(plot) + " из " + capacity(plot) + ".");
        return null;
    }

    /** Забирает товар обратно в инвентарь (излишек - в хранилище). */
    public String withdraw(Player manager, Plot plot, Material m, int amount) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.GOODS);
        if (moduleOff != null) {
            return moduleOff;
        }
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        int have = stock(plot, m);
        if (have <= 0) {
            return "Этого товара на складе нет.";
        }
        int take = amount <= 0 ? have : Math.min(amount, have);
        removeGoods(plot, m, take);
        plugin.plots().save();
        plugin.orders().deliver(manager.getUniqueId(), plot.id, ServiceCatalog.split(new ItemStack(m), take),
                "Товары, участок " + plot.id);
        Text.ok(manager, "Забрано: " + m.name() + " × " + take + ".");
        return null;
    }

    private void removeGoods(Plot plot, Material m, int n) {
        int left = stock(plot, m) - n;
        if (left <= 0) {
            plot.goods.remove(m);
        } else {
            plot.goods.put(m, left);
        }
    }

    /** Назначает цену за штуку; null или отрицательная - снять с продажи (цена удаляется). */
    public String setPrice(Player manager, Plot plot, Material m, Double price) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.GOODS);
        if (moduleOff != null) {
            return moduleOff;
        }
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        if (price == null || price < 0) {
            plot.prices.remove(m);
        } else {
            if (price.isNaN() || price.isInfinite() || price > 1_000_000) {
                return "Цена должна быть числом от 0 до 1 000 000.";
            }
            plot.prices.put(m, Math.round(price * 100) / 100.0);
        }
        plugin.plots().save();
        return null;
    }

    // ------------------------------------------------------------ покупка

    /** Покупка с витрины: деньги покупателя на счёт предприятия (минус налог), товар - покупателю. */
    public String buy(Player buyer, Plot plot, Material m, int amount) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.GOODS);
        if (moduleOff != null) {
            return moduleOff;
        }
        if (!sells(plot)) {
            return "Это предприятие ничего не продаёт.";
        }
        if (plugin.service().isLocked(plot)) {
            return "Участок сейчас обрабатывается.";
        }
        if (plot.isOwner(buyer.getUniqueId())) {
            return "Нельзя покупать у самого себя: заберите товар со склада.";
        }
        double price = price(plot, m);
        if (price <= 0) {
            return "Этот товар не продаётся.";
        }
        int have = stock(plot, m);
        if (have <= 0) {
            return "Товара нет в наличии.";
        }
        if (amount <= 0 || amount > have) {
            return "Столько нет в наличии: осталось " + have + ".";
        }
        if (!plugin.economy().available()) {
            return "Экономика недоступна: не найден плагин с поддержкой Vault.";
        }
        double total = Math.round(price * amount * 100) / 100.0;
        if (!plugin.economy().has(buyer, total)) {
            return "Недостаточно средств. Нужно: " + plugin.economy().format(total);
        }
        if (!plugin.economy().withdraw(buyer, total)) {
            return "Не удалось списать деньги.";
        }
        double tax = Math.round(total * salesTaxPercent()) / 100.0;
        double net = total - tax;
        removeGoods(plot, m, amount);
        plugin.power().touch(plot);
        plot.account.deposit(net);
        plot.revenue += net;
        if (tax > 0) {
            plugin.treasury().deposit(tax, "Налог с продаж, участок " + plot.id);
            plugin.saveTreasury();
        }
        plugin.jobs().onIncome(plot, net);
        plugin.plots().save();
        plugin.orders().deliver(buyer.getUniqueId(), plot.id, ServiceCatalog.split(new ItemStack(m), amount),
                "Покупка на участке " + plot.id);
        if (plot.owner != null) {
            plugin.service().warn(plot.owner, plot.id, "<green>" + Text.esc(buyer.getName()) + " купил " + m.name() + " × " + amount
                    + " на участке <white>" + Text.esc(plot.id) + "</white>: на счёт зачислено " + plugin.economy().format(net)
                    + (tax > 0 ? " (налог " + plugin.economy().format(tax) + ")" : "") + ".");
        }
        Text.ok(buyer, "Куплено: " + m.name() + " × " + amount + " за " + plugin.economy().format(total) + ".");
        return null;
    }

    // ------------------------------------------------------------ закрытие предприятия

    /** Предприятие освобождается: товары возвращаются управляющему (при изъятии арестовываются), цены и связи сбрасываются. */
    public void closePlot(Plot plot, UUID manager, boolean arrest, String reason) {
        if (!plot.goods.isEmpty() && manager != null) {
            List<ItemStack> items = new ArrayList<>();
            for (var e : plot.goods.entrySet()) {
                items.addAll(ServiceCatalog.split(new ItemStack(e.getKey()), e.getValue()));
            }
            if (arrest) {
                plugin.storage().add(manager, plot.id, items, ru.industryreborn.model.StorageEntry.State.ARRESTED, reason,
                        "goods-" + UUID.randomUUID());
            } else {
                plugin.orders().deliver(manager, plot.id, items, "Товары, участок " + plot.id + " освобождён");
            }
        }
        plot.goods.clear();
        plot.prices.clear();
        plot.storefront = false;
        plot.servicesPos = null;
        plot.managerPos = null;
        plugin.links().clear(plot);
    }
}
