package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import ru.industryreborn.IndustryRebornPlugin;

/**
 * Каталог услуг мастерских: переплавка (рецепты печи берутся из игры), ремонт повреждённых предметов
 * и рецепты, заданные администратором в config.yml (services.recipes).
 */
public final class ServiceCatalog {

    public enum Kind { SMELT, REPAIR, RECIPE }

    /**
     * Услуга.
     *
     * @param stationKey      нужный станок (ключ тарифа: smelter, anvil, alchemy, enchanting)
     * @param secondsPerUnit  время на единицу работы на одном станке (до учёта числа станков и services.time-factor)
     */
    public record Offer(String key, Kind kind, String title, String stationKey, double defaultPrice, double maxPrice,
                        double secondsPerUnit, Material input, int inputAmount, Material output, int outputAmount) { }

    /** Результат плавки одного предмета. */
    public record SmeltResult(ItemStack result, double seconds) { }

    /** Подготовленный заказ: что забрать у клиента, что он получит и сколько единиц работы. */
    public record Prepared(int units, List<ItemStack> consume, List<ItemStack> outputs, String error) {
        public boolean ok() {
            return error == null;
        }

        static Prepared fail(String error) {
            return new Prepared(0, List.of(), List.of(), error);
        }
    }

    /** Поиск рецепта плавки; в тестах подменяется. */
    @FunctionalInterface
    public interface SmeltLookup {
        SmeltResult find(ItemStack input);
    }

    private final IndustryRebornPlugin plugin;
    private final Map<Material, Optional<SmeltResult>> smeltCache = new HashMap<>();
    private SmeltLookup lookup = this::bukkitLookup;

    public ServiceCatalog(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    /** Только для тестов. */
    public void setSmeltLookup(SmeltLookup custom) {
        this.lookup = custom == null ? this::bukkitLookup : custom;
        smeltCache.clear();
    }

    private SmeltResult bukkitLookup(ItemStack input) {
        return smeltCache.computeIfAbsent(input.getType(), m -> {
            try {
                var it = Bukkit.recipeIterator();
                while (it.hasNext()) {
                    Recipe r = it.next();
                    if (r instanceof FurnaceRecipe furnace && furnace.getInputChoice().test(input)) {
                        return Optional.of(new SmeltResult(furnace.getResult().clone(), furnace.getCookingTime() / 20.0));
                    }
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Не удалось перебрать рецепты плавки: " + e.getMessage());
            }
            return Optional.empty();
        }).orElse(null);
    }

    // ------------------------------------------------------------ предложения

    public List<Offer> offers() {
        List<Offer> list = new ArrayList<>();
        var cfg = plugin.getConfig();
        Double scaleOverride = plugin.balance().applied("services");
        double k = scaleOverride == null ? 1.0 : scaleOverride;
        list.add(new Offer("smelt", Kind.SMELT, cfg.getString("services.smelt.title", "Переплавка"), "smelter",
                cfg.getDouble("services.smelt.default-price", 1.0) * k, cfg.getDouble("services.smelt.max-price", 20.0) * k,
                0, null, 0, null, 0));
        list.add(new Offer("repair", Kind.REPAIR, cfg.getString("services.repair.title", "Ремонт"), "anvil",
                cfg.getDouble("services.repair.default-price", 10.0) * k, cfg.getDouble("services.repair.max-price", 100.0) * k,
                cfg.getDouble("services.repair.seconds-per-item", 5.0), null, 0, null, 0));
        ConfigurationSection recipes = cfg.getConfigurationSection("services.recipes");
        if (recipes != null) {
            for (String id : recipes.getKeys(false)) {
                ConfigurationSection r = recipes.getConfigurationSection(id);
                Material in = Material.matchMaterial(r.getString("input", ""));
                Material out = Material.matchMaterial(r.getString("output", ""));
                if (in == null || out == null || in.isAir() || out.isAir()) {
                    plugin.getLogger().warning("Рецепт услуги «" + id + "» пропущен: неизвестный материал");
                    continue;
                }
                list.add(new Offer("recipe:" + id, Kind.RECIPE, r.getString("title", id), r.getString("station", "smelter"),
                        r.getDouble("default-price", 1.0) * k, r.getDouble("max-price", 100.0) * k, r.getDouble("seconds", 5.0),
                        in, Math.max(1, r.getInt("input-amount", 1)), out, Math.max(1, r.getInt("output-amount", 1))));
            }
        }
        return list;
    }

    public Offer get(String key) {
        for (Offer o : offers()) {
            if (o.key().equalsIgnoreCase(key)) {
                return o;
            }
        }
        return null;
    }

    /** Время на единицу работы (до учёта числа станков). Для переплавки зависит от предмета. */
    public double baseSeconds(Offer offer, ItemStack sample) {
        if (offer.kind() == Kind.SMELT && sample != null) {
            SmeltResult r = lookup.find(sample);
            return r == null ? 10.0 : r.seconds();
        }
        return offer.secondsPerUnit();
    }

    // ------------------------------------------------------------ подготовка заказа

    /** Разбирает предметы клиента: что подходит под услугу, сколько единиц работы и что получится. */
    public Prepared prepare(Offer offer, List<ItemStack> candidates) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack s : candidates) {
            if (s != null && !s.getType().isAir()) {
                items.add(s);
            }
        }
        if (items.isEmpty()) {
            return Prepared.fail("Возьмите в руку предметы для заказа.");
        }
        return switch (offer.kind()) {
            case SMELT -> prepareSmelt(items);
            case REPAIR -> prepareRepair(items);
            case RECIPE -> prepareRecipe(offer, items);
        };
    }

    private Prepared prepareSmelt(List<ItemStack> items) {
        SmeltResult first = lookup.find(items.get(0));
        if (first == null) {
            return Prepared.fail("Из этого предмета ничего не выплавить.");
        }
        int units = 0;
        List<ItemStack> consume = new ArrayList<>();
        for (ItemStack s : items) {
            if (s.getType() != items.get(0).getType()) {
                continue;
            }
            units += s.getAmount();
            consume.add(s.clone());
        }
        return new Prepared(units, consume, split(first.result(), first.result().getAmount() * units), null);
    }

    private Prepared prepareRepair(List<ItemStack> items) {
        int units = 0;
        List<ItemStack> consume = new ArrayList<>();
        List<ItemStack> outputs = new ArrayList<>();
        for (ItemStack s : items) {
            ItemMeta meta = s.getItemMeta();
            if (!(meta instanceof Damageable damageable) || !damageable.hasDamage()) {
                continue;
            }
            units += s.getAmount();
            consume.add(s.clone());
            ItemStack fixed = s.clone();
            fixed.editMeta(Damageable.class, m -> m.setDamage(0));
            outputs.add(fixed);
        }
        if (units == 0) {
            return Prepared.fail("Нужны повреждённые предметы: целые чинить не нужно.");
        }
        return new Prepared(units, consume, outputs, null);
    }

    private Prepared prepareRecipe(Offer offer, List<ItemStack> items) {
        int total = 0;
        for (ItemStack s : items) {
            if (s.getType() == offer.input()) {
                total += s.getAmount();
            }
        }
        int batches = total / offer.inputAmount();
        if (batches <= 0) {
            return Prepared.fail("Нужно не меньше " + offer.inputAmount() + " шт. материала «" + offer.input().name() + "».");
        }
        // забираем именно стаки клиента (с их свойствами), чтобы их можно было точно списать из инвентаря
        int need = batches * offer.inputAmount();
        List<ItemStack> consume = new ArrayList<>();
        for (ItemStack s : items) {
            if (s.getType() == offer.input() && need > 0) {
                ItemStack part = s.clone();
                part.setAmount(Math.min(s.getAmount(), need));
                consume.add(part);
                need -= part.getAmount();
            }
        }
        return new Prepared(batches, consume, split(new ItemStack(offer.output()), batches * offer.outputAmount()), null);
    }

    /** Делит количество на стаки по максимальному размеру стака. */
    public static List<ItemStack> split(ItemStack template, int total) {
        List<ItemStack> result = new ArrayList<>();
        int max = Math.max(1, template.getMaxStackSize());
        int left = total;
        while (left > 0) {
            int n = Math.min(max, left);
            ItemStack stack = template.clone();
            stack.setAmount(n);
            result.add(stack);
            left -= n;
        }
        return result;
    }
}
