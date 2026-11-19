package ru.industryreborn.items;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.ServiceCatalog;
import ru.industryreborn.util.Text;

/**
 * Магазин предметов администрации: инструменты, скины и другие предметы плагина (в том числе найденные в Nexo) по ценам, которые задаёт администратор
 * (цена меняется в любой момент командой). Деньги идут в казну. Игроки продают свои предметы только обычными магазинами предприятий.
 */
public final class ItemShop {

    private final IndustryRebornPlugin plugin;
    private final File file;
    private final Map<String, Double> prices = new LinkedHashMap<>();

    public ItemShop(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "itemshop.yml");
        load();
    }

    public boolean enabled() {
        return plugin.customItems().enabled();
    }

    /** Цена предмета в магазине (0 - не продаётся). */
    public double price(String id) {
        return prices.getOrDefault(id.toLowerCase(Locale.ROOT), 0.0);
    }

    /** Назначает цену предмету плагина; 0 - снять с продажи. Возвращает ошибку или null. */
    public String setPrice(String id, double price) {
        CustomItem item = plugin.customItems().get(id);
        if (item == null) {
            return "Предмет «" + id + "» не найден: /district item list";
        }
        if (price < 0 || Double.isNaN(price) || Double.isInfinite(price) || price > 1_000_000_000) {
            return "Цена - число от 0 до 1 000 000 000.";
        }
        if (price == 0) {
            prices.remove(item.id());
        } else {
            prices.put(item.id(), Math.round(price * 100) / 100.0);
        }
        save();
        return null;
    }

    /** Предметы в продаже: сначала инструменты, затем скины, затем остальное; внутри - по названию. */
    public List<CustomItem> listed() {
        List<CustomItem> list = new ArrayList<>();
        for (CustomItem d : plugin.customItems().all()) {
            if (price(d.id()) > 0) {
                list.add(d);
            }
        }
        list.sort(Comparator.<CustomItem, Integer>comparing(d -> switch (d.kind()) {
            case TOOL -> 0;
            case SKIN -> 1;
            case ITEM -> 2;
        }).thenComparing(d -> plugin.customItems().displayName(d.ref()).toLowerCase(Locale.ROOT)));
        return list;
    }

    /** Покупка: деньги в казну, предмет покупателю (лишнее - в хранилище). Возвращает ошибку или null. */
    public String buy(Player buyer, String id, int amount) {
        if (!enabled()) {
            return "Магазин предметов сейчас отключён.";
        }
        CustomItem item = plugin.customItems().get(id);
        double price = item == null ? 0 : price(item.id());
        if (item == null || price <= 0) {
            return "Этот предмет в магазине не продаётся.";
        }
        if (amount < 1 || amount > 64) {
            return "Количество от 1 до 64.";
        }
        if (!buyer.hasPermission("industryreborn.use")) {
            return "Недостаточно прав.";
        }
        ItemStack stack = plugin.customItems().build(item.ref(), 1);
        if (stack == null) {
            return "Этот предмет сейчас недоступен (Nexo ещё не загрузил предметы).";
        }
        double total = Math.round(price * amount * 100) / 100.0;
        if (!plugin.economy().withdraw(buyer, total)) {
            return "Не хватает денег: нужно " + plugin.economy().format(total) + ".";
        }
        plugin.treasury().deposit(total, "Магазин предметов «" + item.id() + "»: " + buyer.getName());
        plugin.saveTreasury();
        plugin.orders().deliver(buyer.getUniqueId(), "-", ServiceCatalog.split(stack, amount), "Куплено в магазине предметов: " + item.id());
        plugin.journal().add("-", buyer.getUniqueId(), buyer.getName(), Journal.Action.FACTORY,
                "Куплено в магазине предметов: " + item.id() + " × " + amount + " за " + plugin.economy().format(total));
        Text.ok(buyer, "Куплено: " + Text.esc(plugin.customItems().displayName(item.ref())) + " × " + amount + " за " + plugin.economy().format(total) + ".");
        return null;
    }

    private void load() {
        prices.clear();
        if (!file.exists()) {
            return;
        }
        var section = YamlConfiguration.loadConfiguration(file).getConfigurationSection("prices");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                double v = section.getDouble(key);
                if (v > 0) {
                    prices.put(key, v);
                }
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (var e : prices.entrySet()) {
            yml.set("prices." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить itemshop.yml", e);
        }
    }
}
