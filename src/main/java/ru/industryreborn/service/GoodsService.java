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
        return plot.goods.values().stream().mapToInt(Integer::intValue).sum()
                + plot.customGoods.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int stock(Plot plot, Material m) {
        return plot.goods.getOrDefault(m, 0);
    }

    // ------------------------------------------------------------ товары по ключу (материал или предмет плагина и Nexo)

    /** Ключ товара-материала: «IRON_INGOT»; предметы плагина и Nexo - «ir:steel», «nexo:steel». */
    public static String keyOf(Material m) {
        return m.name();
    }

    private static boolean custom(String key) {
        return ru.industryreborn.items.ItemRef.isCustomKey(key);
    }

    public int stockKey(Plot plot, String key) {
        if (custom(key)) {
            return plot.customGoods.getOrDefault(key, 0);
        }
        Material m = Material.matchMaterial(key);
        return m == null ? 0 : stock(plot, m);
    }

    public double priceKey(Plot plot, String key) {
        Double own;
        if (custom(key)) {
            own = plot.customPrices.get(key);
        } else {
            Material m = Material.matchMaterial(key);
            own = m == null ? null : plot.prices.get(m);
        }
        if (own != null) {
            return own;
        }
        return Math.max(0, plugin.getConfig().getDouble("goods.default-price", 0));
    }

    /** Название товара для людей: материал - имя материала, предмет плагина или Nexo - его название. */
    public String nameOf(String key) {
        return custom(key) ? plugin.customItems().displayName(ru.industryreborn.items.ItemRef.parse(key)) : key;
    }

    /** Предмет товара (1 шт.) либо null, если он сейчас недоступен (не определён, Nexo ещё не загрузился). */
    public ItemStack stackOfKey(String key) {
        var ref = ru.industryreborn.items.ItemRef.parse(key);
        return ref == null ? null : plugin.customItems().build(ref, 1);
    }

    private String sortName(String key) {
        return (custom(key) ? nameOf(key) : key).toUpperCase(java.util.Locale.ROOT);
    }

    /** Все товары склада (в т. ч. без цены), по названию. */
    public List<String> keys(Plot plot) {
        List<String> list = new ArrayList<>();
        plot.goods.keySet().forEach(m -> list.add(m.name()));
        list.addAll(plot.customGoods.keySet());
        list.sort(Comparator.comparing(this::sortName));
        return list;
    }

    /** Товары с положительной ценой (то, что можно купить), по названию. */
    public List<String> listedKeys(Plot plot) {
        List<String> list = new ArrayList<>();
        for (String key : keys(plot)) {
            if (priceKey(plot, key) > 0) {
                list.add(key);
            }
        }
        return list;
    }

    /** Цена за штуку: назначенная управляющим либо цена по умолчанию из конфига (0 - не продаётся). */
    public double price(Plot plot, Material m) {
        return priceKey(plot, keyOf(m));
    }

    /** Товары-материалы с положительной ценой (то, что можно купить), по названию. */
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
        Double auto = plugin.balance().applied("sales-tax");
        return Math.max(0, Math.min(100, auto != null ? auto : plugin.getConfig().getDouble("goods.sales-tax-percent", 0)));
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
        return addKey(plot, keyOf(m), amount);
    }

    /** То же по ключу: материал или предмет плагина и Nexo. */
    public int addKey(Plot plot, String key, int amount) {
        int room = capacity(plot) - total(plot);
        int put = Math.max(0, Math.min(room, amount));
        if (put > 0) {
            if (custom(key)) {
                plot.customGoods.merge(key, put, Integer::sum);
            } else {
                Material m = Material.matchMaterial(key);
                if (m == null) {
                    return 0;
                }
                plot.goods.merge(m, put, Integer::sum);
            }
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
            String key = null;
            if (s != null && !s.getType().isAir()) {
                var ref = plugin.customItems().identify(s);
                if (ref != null) {
                    key = only == null && plugin.customItems().pristine(s) ? ref.key() : null;   // предметы плагина и Nexo - только нетронутые и когда кладём «всё подходящее»
                } else if (plain(s) && (only == null || s.getType() == only)) {
                    key = s.getType().name();
                }
            }
            if (key == null) {
                continue;
            }
            int take = Math.min(limit - moved, s.getAmount());
            if (custom(key)) {
                plot.customGoods.merge(key, take, Integer::sum);
            } else {
                plot.goods.merge(s.getType(), take, Integer::sum);
            }
            moved += take;
            s.setAmount(s.getAmount() - take);
            inv.setItem(i, s.getAmount() <= 0 ? null : s);
        }
        if (moved == 0) {
            return "В инвентаре нет подходящих предметов (обычные без названий, предметы плагина и Nexo)" + (only == null ? "" : " (" + only.name() + ")") + ".";
        }
        plugin.plots().save();
        Text.ok(manager, "Выложено на склад товаров: " + moved + ". Всего: " + total(plot) + " из " + capacity(plot) + ".");
        return null;
    }

    /** Забирает товар обратно в инвентарь (излишек - в хранилище). */
    public String withdraw(Player manager, Plot plot, Material m, int amount) {
        return withdrawKey(manager, plot, keyOf(m), amount);
    }

    public String withdrawKey(Player manager, Plot plot, String key, int amount) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.GOODS);
        if (moduleOff != null) {
            return moduleOff;
        }
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        int have = stockKey(plot, key);
        if (have <= 0) {
            return "Этого товара на складе нет.";
        }
        ItemStack item = stackOfKey(key);
        if (item == null) {
            return "Этот предмет сейчас недоступен (не загружен Nexo или предмет убран из файлов).";
        }
        int take = amount <= 0 ? have : Math.min(amount, have);
        removeGoods(plot, key, take);
        plugin.plots().save();
        plugin.orders().deliver(manager.getUniqueId(), plot.id, ServiceCatalog.split(item, take),
                "Товары, участок " + plot.id);
        Text.ok(manager, "Забрано: " + nameOf(key) + " × " + take + ".");
        return null;
    }

    private void removeGoods(Plot plot, String key, int n) {
        if (custom(key)) {
            int left = plot.customGoods.getOrDefault(key, 0) - n;
            if (left <= 0) {
                plot.customGoods.remove(key);
            } else {
                plot.customGoods.put(key, left);
            }
            return;
        }
        Material m = Material.matchMaterial(key);
        if (m == null) {
            return;
        }
        int left = stock(plot, m) - n;
        if (left <= 0) {
            plot.goods.remove(m);
        } else {
            plot.goods.put(m, left);
        }
    }

    /** Назначает цену за штуку; null или отрицательная - снять с продажи (цена удаляется). */
    public String setPrice(Player manager, Plot plot, Material m, Double price) {
        return setPriceKey(manager, plot, keyOf(m), price);
    }

    public String setPriceKey(Player manager, Plot plot, String key, Double price) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.GOODS);
        if (moduleOff != null) {
            return moduleOff;
        }
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        Material m = custom(key) ? null : Material.matchMaterial(key);
        var ref = custom(key) ? ru.industryreborn.items.ItemRef.parse(key) : null;
        if (custom(key) ? ref == null || (!plugin.customItems().exists(ref) && stockKey(plot, key) == 0) : m == null) {
            return "Неизвестный товар «" + key + "».";
        }
        if (price == null || price < 0) {
            if (custom(key)) {
                plot.customPrices.remove(key);
            } else {
                plot.prices.remove(m);
            }
        } else {
            if (price.isNaN() || price.isInfinite() || price > 1_000_000) {
                return "Цена должна быть числом от 0 до 1 000 000.";
            }
            double rounded = Math.round(price * 100) / 100.0;
            if (custom(key)) {
                plot.customPrices.put(key, rounded);
            } else {
                plot.prices.put(m, rounded);
            }
        }
        plugin.plots().save();
        return null;
    }

    // ------------------------------------------------------------ покупка

    /** Покупка с витрины: деньги покупателя на счёт предприятия (минус налог), товар - покупателю. */
    public String buy(Player buyer, Plot plot, Material m, int amount) {
        return buyKey(buyer, plot, keyOf(m), amount);
    }

    public String buyKey(Player buyer, Plot plot, String key, int amount) {
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
        double price = priceKey(plot, key);
        if (price <= 0) {
            return "Этот товар не продаётся.";
        }
        ItemStack item = stackOfKey(key);
        if (item == null) {
            return "Этот товар сейчас недоступен.";
        }
        int have = stockKey(plot, key);
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
        removeGoods(plot, key, amount);
        plugin.power().touch(plot);
        plot.account.deposit(net);
        plot.revenue += net;
        if (tax > 0) {
            plugin.treasury().deposit(tax, "Налог с продаж, участок " + plot.id);
            plugin.saveTreasury();
        }
        plugin.jobs().onIncome(plot, net);
        plugin.plots().save();
        plugin.orders().deliver(buyer.getUniqueId(), plot.id, ServiceCatalog.split(item, amount),
                "Покупка на участке " + plot.id);
        if (plot.owner != null) {
            plugin.service().warn(plot.owner, plot.id, "<green>" + Text.esc(buyer.getName()) + " купил " + Text.esc(nameOf(key)) + " × " + amount
                    + " на участке <white>" + Text.esc(plot.id) + "</white>: на счёт зачислено " + plugin.economy().format(net)
                    + (tax > 0 ? " (налог " + plugin.economy().format(tax) + ")" : "") + ".");
        }
        Text.ok(buyer, "Куплено: " + nameOf(key) + " × " + amount + " за " + plugin.economy().format(total) + ".");
        return null;
    }

    // ------------------------------------------------------------ закрытие предприятия

    /** Предприятие освобождается: товары возвращаются управляющему (при изъятии арестовываются), цены и связи сбрасываются. */
    public void closePlot(Plot plot, UUID manager, boolean arrest, String reason) {
        plugin.holoShops().removeOfPlot(plot.id);
        if ((!plot.goods.isEmpty() || !plot.customGoods.isEmpty()) && manager != null) {
            List<ItemStack> items = new ArrayList<>();
            for (var e : plot.goods.entrySet()) {
                items.addAll(ServiceCatalog.split(new ItemStack(e.getKey()), e.getValue()));
            }
            for (var e : plot.customGoods.entrySet()) {
                ItemStack stack = stackOfKey(e.getKey());
                if (stack == null) {
                    plugin.getLogger().warning("Товары " + plot.id + ": предмет " + e.getKey() + " недоступен и не возвращён (" + e.getValue() + " шт.)");
                    continue;
                }
                items.addAll(ServiceCatalog.split(stack, e.getValue()));
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
        plot.customGoods.clear();
        plot.customPrices.clear();
        plot.storefront = false;
        plot.servicesPos = null;
        plot.managerPos = null;
        plugin.links().clear(plot);
    }
}
