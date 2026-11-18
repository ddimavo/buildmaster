package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;
import ru.industryreborn.IndustryRebornPlugin;

/**
 * Каталог рецептов станков, построенный из реестра игры: плавка (печь, плавильная печь, коптильня) и сборка (наковальня:
 * блоки, инструменты, оружие, броня, еда из верстака). Расход и выход те же, что в игре, поэтому рецепты не нарушают баланс
 * и не требуют одобрения. Зачарования (ENCHANT) заводятся отдельно и проходят одобрение администратора.
 */
public final class RecipeBook {

    public enum Kind { SMELT, ASSEMBLE, ENCHANT }

    /** Ингредиент: подойдёт любой из материалов списка (например, любые доски), всего нужно count штук. */
    public record Ingredient(List<Material> options, int count, List<String> customs) {

        public Ingredient(List<Material> options, int count) {
            this(options, count, List.of());
        }

        public boolean accepts(Material m) {
            return options.contains(m);
        }

        /** Подходит предмет плагина или Nexo (ключ вида «ir:steel», «nexo:steel»). */
        public boolean acceptsKey(String key) {
            return customs.contains(key);
        }
    }

    /**
     * Рецепт станка. family: furnace | blast | smoker | craft | enchant. Идентификатор - "семейство:РЕЗУЛЬТАТ".
     */
    public record Recipe(String id, Kind kind, String family, List<Ingredient> inputs, Material output, int outputAmount,
                         double seconds, String category, String enchantKey, int enchantLevel, String outputItem, boolean free) {

        public Recipe(String id, Kind kind, String family, List<Ingredient> inputs, Material output, int outputAmount,
                      double seconds, String category) {
            this(id, kind, family, inputs, output, outputAmount, seconds, category, null, 0, null, true);
        }

        public Recipe(String id, Kind kind, String family, List<Ingredient> inputs, Material output, int outputAmount,
                      double seconds, String category, String enchantKey, int enchantLevel) {
            this(id, kind, family, inputs, output, outputAmount, seconds, category, enchantKey, enchantLevel, null, true);
        }

        /** Рецепт администратора (из recipes/*.yml): результат или ингредиенты могут быть предметами плагина и Nexo. */
        public boolean custom() {
            return id.startsWith("custom:");
        }

        /** Рецепт зачарования предмета. */
        public boolean enchanting() {
            return enchantKey != null;
        }
        public boolean uses(Material m) {
            return inputs.stream().anyMatch(i -> i.accepts(m));
        }

        /** Ключ результата для склада и показа: «IRON_INGOT», «ir:steel», «nexo:steel». */
        public String outputKey() {
            return outputItem != null ? outputItem : output.name();
        }

        public int totalInput() {
            return inputs.stream().mapToInt(Ingredient::count).sum();
        }
    }

    /** Поиск зачарований по ключу (в тестах подменяется): настоящие и кастомные зачарования берутся из реестра игры. */
    public interface EnchantLookup {
        org.bukkit.enchantments.Enchantment get(String key);

        List<org.bukkit.enchantments.Enchantment> all();
    }

    private static final EnchantLookup REGISTRY = new EnchantLookup() {
        @Override
        public org.bukkit.enchantments.Enchantment get(String key) {
            org.bukkit.NamespacedKey k = org.bukkit.NamespacedKey.fromString(key.contains(":") ? key : "minecraft:" + key);
            return k == null ? null : org.bukkit.Registry.ENCHANTMENT.get(k);
        }

        @Override
        public List<org.bukkit.enchantments.Enchantment> all() {
            List<org.bukkit.enchantments.Enchantment> list = new ArrayList<>();
            org.bukkit.Registry.ENCHANTMENT.forEach(list::add);
            return list;
        }
    };

    private final IndustryRebornPlugin plugin;
    private EnchantLookup enchants = REGISTRY;
    private Supplier<Iterator<org.bukkit.inventory.Recipe>> source = Bukkit::recipeIterator;
    private Map<String, Recipe> cache;

    public RecipeBook(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    /** Только для тестов: подмена реестра рецептов игры. */
    public void setSource(Supplier<Iterator<org.bukkit.inventory.Recipe>> source) {
        this.source = source == null ? Bukkit::recipeIterator : source;
        invalidate();
    }

    public void invalidate() {
        cache = null;
    }

    /** Только для тестов: подмена реестра зачарований. */
    public void setEnchantLookup(EnchantLookup lookup) {
        this.enchants = lookup == null ? REGISTRY : lookup;
    }

    public EnchantLookup enchantLookup() {
        return enchants;
    }

    // ------------------------------------------------------------ зачарования

    /** Ключ зачарования без «minecraft:» (так он хранится в идентификаторе рецепта). */
    public static String shortKey(org.bukkit.enchantments.Enchantment e) {
        org.bukkit.NamespacedKey k = e.getKey();
        return "minecraft".equals(k.getNamespace()) ? k.getKey() : k.toString();
    }

    public static String enchantId(Material item, String key, int level) {
        return "enchant:" + item.name() + "|" + key.replace("minecraft:", "") + "|" + level;
    }

    /** Лазурита на зачарование уровня level. */
    public int lapisFor(int level) {
        return Math.max(1, plugin.getConfig().getInt("enchanting.lapis-base", 1)
                + plugin.getConfig().getInt("enchanting.lapis-per-level", 2) * level);
    }

    public double enchantSeconds(int level) {
        return Math.max(1, plugin.getConfig().getDouble("enchanting.seconds-base", 8)
                + plugin.getConfig().getDouble("enchanting.seconds-per-level", 4) * level);
    }

    /** Рецепт зачарования из идентификатора «enchant:ПРЕДМЕТ|ключ|уровень» либо null, если он недействителен. */
    private Recipe enchantRecipe(String id) {
        String[] p = id.substring("enchant:".length()).split("\\|");
        if (p.length != 3) {
            return null;
        }
        Material item = Material.matchMaterial(p[0]);
        org.bukkit.enchantments.Enchantment e = enchants.get(p[1]);
        int level;
        try {
            level = Integer.parseInt(p[2]);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (item == null || e == null || level < 1) {
            return null;
        }
        return new Recipe(enchantId(item, p[1], level), Kind.ENCHANT, "enchant",
                List.of(new Ingredient(List.of(item), 1), new Ingredient(List.of(Material.LAPIS_LAZULI), lapisFor(level))),
                item, 1, enchantSeconds(level), "Зачарования", p[1].replace("minecraft:", ""), level);
    }

    /** Предмет, который получается по рецепту (с зачарованием, если оно есть). */
    public ItemStack stackOf(Recipe r) {
        if (r.outputItem() != null) {
            return plugin.customItems().build(ru.industryreborn.items.ItemRef.parse(r.outputItem()), r.outputAmount());   // null, если предмет недоступен
        }
        ItemStack stack = new ItemStack(r.output(), Math.max(1, r.outputAmount()));
        if (r.enchanting()) {
            org.bukkit.enchantments.Enchantment e = enchants.get(r.enchantKey());
            if (e != null) {
                stack.addUnsafeEnchantment(e, r.enchantLevel());
            }
        }
        return stack;
    }

    // ------------------------------------------------------------ станки

    /** Семейство рецептов для блока станка; null - блок не производит (варочная стойка, прочие). */
    public static String familyOf(Material block) {
        return switch (block) {
            case FURNACE -> "furnace";
            case BLAST_FURNACE -> "blast";
            case SMOKER -> "smoker";
            case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> "craft";
            case ENCHANTING_TABLE -> "enchant";
            default -> null;
        };
    }

    public static String familyTitle(String family) {
        return switch (family) {
            case "furnace" -> "Плавка в печи";
            case "blast" -> "Плавка в плавильной печи";
            case "smoker" -> "Готовка в коптильне";
            case "craft" -> "Сборка";
            case "enchant" -> "Зачарование";
            default -> family;
        };
    }

    // ------------------------------------------------------------ категории

    /** Категория результата сборки для меню выбора. */
    public static String categoryOf(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.equals("SHIELD") || n.equals("TURTLE_HELMET")) {
            return "Броня";
        }
        if (n.endsWith("_SWORD") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.endsWith("ARROW")) {
            return "Оружие";
        }
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || n.equals("SHEARS") || n.equals("FISHING_ROD") || n.equals("FLINT_AND_STEEL")) {
            return "Инструменты";
        }
        if (m.isEdible()) {
            return "Еда";
        }
        if (m.isBlock()) {
            return "Блоки";
        }
        return "Прочее";
    }

    // ------------------------------------------------------------ построение

    private Map<String, Recipe> all() {
        if (cache == null) {
            cache = build();
        }
        return cache;
    }

    private static boolean plain(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && stack.asOne().isSimilar(new ItemStack(stack.getType()));
    }

    private boolean banned(Material m) {
        for (String s : plugin.getConfig().getStringList("production.banned-outputs")) {
            if (s.equalsIgnoreCase(m.name())) {
                return true;
            }
        }
        return false;
    }

    private static List<Material> options(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.MaterialChoice mc && !mc.getChoices().isEmpty()) {
            return List.copyOf(mc.getChoices());
        }
        return null;
    }

    private Map<String, Recipe> build() {
        Map<String, Recipe> map = new LinkedHashMap<>();
        double base = Math.max(0, plugin.getConfig().getDouble("production.assembly-seconds-base", 4));
        double perItem = Math.max(0, plugin.getConfig().getDouble("production.assembly-seconds-per-item", 0.5));
        Iterator<org.bukkit.inventory.Recipe> it;
        try {
            it = source.get();
        } catch (RuntimeException e) {
            return map;
        }
        while (it.hasNext()) {
            org.bukkit.inventory.Recipe r = it.next();
            ItemStack result = r.getResult();
            if (!plain(result) || banned(result.getType())) {
                continue;
            }
            if (r instanceof CookingRecipe<?> cooking) {
                String family = cooking instanceof BlastingRecipe ? "blast" : cooking instanceof SmokingRecipe ? "smoker"
                        : cooking instanceof FurnaceRecipe ? "furnace" : null;
                List<Material> in = options(cooking.getInputChoice());
                if (family == null || in == null) {
                    continue;
                }
                String id = family + ":" + result.getType().name();
                Recipe existing = map.get(id);
                if (existing != null) {
                    // тот же результат из другого сырья (сырое железо, блок руды, глубинная руда): сырьё объединяется
                    List<Material> merged = new ArrayList<>(existing.inputs().get(0).options());
                    for (Material m : in) {
                        if (!merged.contains(m)) {
                            merged.add(m);
                        }
                    }
                    map.put(id, new Recipe(id, Kind.SMELT, family, List.of(new Ingredient(merged, 1)), result.getType(),
                            result.getAmount(), existing.seconds(), ""));
                } else {
                    map.put(id, new Recipe(id, Kind.SMELT, family, List.of(new Ingredient(in, 1)), result.getType(),
                            result.getAmount(), Math.max(1, cooking.getCookingTime() / 20.0), ""));
                }
            } else if (r instanceof ShapedRecipe shaped) {
                List<Ingredient> inputs = shapedInputs(shaped);
                addCraft(map, inputs, result, base, perItem);
            } else if (r instanceof ShapelessRecipe shapeless) {
                List<Ingredient> inputs = new ArrayList<>();
                boolean ok = true;
                for (RecipeChoice c : shapeless.getChoiceList()) {
                    List<Material> o = options(c);
                    if (o == null) {
                        ok = false;
                        break;
                    }
                    merge(inputs, o, 1);
                }
                addCraft(map, ok ? inputs : null, result, base, perItem);
            }
        }
        return map;
    }

    private static List<Ingredient> shapedInputs(ShapedRecipe shaped) {
        List<Ingredient> inputs = new ArrayList<>();
        Map<Character, Integer> counts = new LinkedHashMap<>();
        for (String row : shaped.getShape()) {
            for (char c : row.toCharArray()) {
                if (c != ' ') {
                    counts.merge(c, 1, Integer::sum);
                }
            }
        }
        for (var e : counts.entrySet()) {
            List<Material> o = options(shaped.getChoiceMap().get(e.getKey()));
            if (o == null) {
                return null;
            }
            merge(inputs, o, e.getValue());
        }
        return inputs;
    }

    private static void merge(List<Ingredient> inputs, List<Material> options, int count) {
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).options().equals(options)) {
                inputs.set(i, new Ingredient(options, inputs.get(i).count() + count));
                return;
            }
        }
        inputs.add(new Ingredient(options, count));
    }

    private void addCraft(Map<String, Recipe> map, List<Ingredient> inputs, ItemStack result, double base, double perItem) {
        if (inputs == null || inputs.isEmpty()) {
            return;
        }
        String id = "craft:" + result.getType().name();
        if (map.containsKey(id)) {
            return;     // один рецепт на результат: берётся первый в реестре
        }
        int total = inputs.stream().mapToInt(Ingredient::count).sum();
        map.put(id, new Recipe(id, Kind.ASSEMBLE, "craft", List.copyOf(inputs), result.getType(), result.getAmount(),
                base + perItem * total, categoryOf(result.getType())));
    }

    // ------------------------------------------------------------ запросы

    public Recipe get(String id) {
        if (id == null) {
            return null;
        }
        if (id.startsWith("custom:")) {
            return custom.get(id);
        }
        if (id.startsWith("enchant:")) {
            return enchantRecipe(id);
        }
        return all().get(id);
    }

    /** Рецепты, доступные станку с данным блоком, по алфавиту. */
    public List<Recipe> forBlock(Material block) {
        String family = familyOf(block);
        List<Recipe> list = new ArrayList<>();
        if (family == null) {
            return list;
        }
        for (Recipe r : all().values()) {
            if (r.family().equals(family)) {
                list.add(r);
            }
        }
        list.sort(java.util.Comparator.comparing(r -> r.output().name().toLowerCase(Locale.ROOT)));
        return list;
    }

    /** Название результата для меню и голограмм: для предметов плагина - из определения, иначе имя материала. */
    public String outputName(Recipe r) {
        if (r.outputItem() != null) {
            return plugin.customItems().displayName(ru.industryreborn.items.ItemRef.parse(r.outputItem()));
        }
        return r.output().name();
    }

    public int size() {
        return all().size() + custom.size();
    }

    // ------------------------------------------------------------ рецепты администратора

    private final Map<String, Recipe> custom = new LinkedHashMap<>();

    public java.io.File customFolder() {
        return new java.io.File(plugin.getDataFolder(), "recipes");
    }

    /** Все рецепты администратора. */
    public List<Recipe> customRecipes() {
        return List.copyOf(custom.values());
    }

    /** Рецепт администратора можно выполнить: результат и ингредиенты доступны (предметы Nexo загружены). */
    public boolean available(Recipe r) {
        if (!r.custom()) {
            return true;
        }
        var items = plugin.customItems();
        if (r.outputItem() != null && !items.known(ru.industryreborn.items.ItemRef.parse(r.outputItem()))) {
            return false;
        }
        return r.outputItem() == null || items.build(ru.industryreborn.items.ItemRef.parse(r.outputItem()), 1) != null;
    }

    /** Читает recipes/*.yml. Возвращает список ошибок (неверные рецепты пропускаются). */
    public List<String> loadCustom() {
        custom.clear();
        List<String> errors = new ArrayList<>();
        java.io.File[] files = customFolder().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return errors;
        }
        java.util.Arrays.sort(files);
        for (java.io.File f : files) {
            org.bukkit.configuration.file.YamlConfiguration yml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            for (String id : yml.getKeys(false)) {
                var s = yml.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                String error = addCustom(id, s);
                if (error != null) {
                    errors.add(f.getName() + ": " + error);
                }
            }
        }
        for (String e : errors) {
            plugin.getLogger().warning("Рецепты: " + e);
        }
        return errors;
    }

    private static final List<String> STATIONS = List.of("furnace", "blast", "smoker", "craft");

    /** Добавляет рецепт администратора из секции. Возвращает ошибку или null. */
    public String addCustom(String rawId, org.bukkit.configuration.ConfigurationSection s) {
        String key = rawId.toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9_]{1,40}")) {
            return "«" + rawId + "»: id - латиница, цифры и _ (до 40 знаков)";
        }
        String id = "custom:" + key;
        if (custom.containsKey(id)) {
            return "«" + key + "»: уже определён";
        }
        String station = s.getString("station", "");
        if (!STATIONS.contains(station)) {
            return "«" + key + "»: station - одно из " + STATIONS;
        }
        double seconds = s.getDouble("seconds", 0);
        if (seconds <= 0 || seconds > 36000) {
            return "«" + key + "»: seconds - от 0 до 36000";
        }
        var items = plugin.customItems();
        List<Ingredient> inputs = new ArrayList<>();
        for (Object raw : s.getList("inputs", List.of())) {
            if (!(raw instanceof Map<?, ?> m)) {
                return "«" + key + "»: каждый вход - запись {item: ..., amount: ...}";
            }
            Object item = m.get("item");
            List<String> refs = new ArrayList<>();
            if (item instanceof List<?> list) {
                list.forEach(o -> refs.add(String.valueOf(o)));
            } else if (item != null) {
                refs.add(String.valueOf(item));
            }
            int amount = m.get("amount") instanceof Number n ? n.intValue() : 1;
            if (refs.isEmpty() || amount < 1 || amount > 64) {
                return "«" + key + "»: у входа нужен item и amount от 1 до 64";
            }
            List<Material> vanilla = new ArrayList<>();
            List<String> customs = new ArrayList<>();
            for (String r : refs) {
                var ref = ru.industryreborn.items.ItemRef.parse(r);
                if (ref == null || !items.known(ref)) {
                    return "«" + key + "»: неизвестный предмет «" + r + "»";
                }
                if (ref.custom()) {
                    customs.add(ref.key());
                } else {
                    vanilla.add(ref.material());
                }
            }
            inputs.add(new Ingredient(List.copyOf(vanilla), amount, List.copyOf(customs)));
        }
        if (inputs.isEmpty() || inputs.size() > 4) {
            return "«" + key + "»: от 1 до 4 входов";
        }
        var out = s.getConfigurationSection("output");
        var outRef = out == null ? null : ru.industryreborn.items.ItemRef.parse(out.getString("item"));
        int outAmount = out == null ? 1 : out.getInt("amount", 1);
        if (outRef == null || !items.known(outRef) || outAmount < 1 || outAmount > 64) {
            return "«" + key + "»: у результата нужен известный item и amount от 1 до 64";
        }
        custom.put(id, new Recipe(id, Kind.ASSEMBLE, station, List.copyOf(inputs), items.baseMaterial(outRef), outAmount, seconds,
                s.getString("category", "Особые"), null, 0, outRef.custom() ? outRef.key() : null, s.getBoolean("free", false)));
        return null;
    }
}
