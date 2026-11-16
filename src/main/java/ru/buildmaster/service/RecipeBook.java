package ru.buildmaster.service;

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
import ru.buildmaster.BuildMasterPlugin;

/**
 * Каталог рецептов станков, построенный из реестра игры: плавка (печь, плавильная печь, коптильня) и сборка (наковальня:
 * блоки, инструменты, оружие, броня, еда из верстака). Расход и выход те же, что в игре, поэтому рецепты не нарушают баланс
 * и не требуют одобрения. Зачарования (ENCHANT) заводятся отдельно и проходят одобрение администратора.
 */
public final class RecipeBook {

    public enum Kind { SMELT, ASSEMBLE, ENCHANT }

    /** Ингредиент: подойдёт любой из материалов списка (например, любые доски), всего нужно count штук. */
    public record Ingredient(List<Material> options, int count) {
        public boolean accepts(Material m) {
            return options.contains(m);
        }
    }

    /**
     * Рецепт станка. family: furnace | blast | smoker | craft | enchant. Идентификатор - "семейство:РЕЗУЛЬТАТ".
     */
    public record Recipe(String id, Kind kind, String family, List<Ingredient> inputs, Material output, int outputAmount,
                         double seconds, String category) {
        public boolean uses(Material m) {
            return inputs.stream().anyMatch(i -> i.accepts(m));
        }

        public int totalInput() {
            return inputs.stream().mapToInt(Ingredient::count).sum();
        }
    }

    private final BuildMasterPlugin plugin;
    private Supplier<Iterator<org.bukkit.inventory.Recipe>> source = Bukkit::recipeIterator;
    private Map<String, Recipe> cache;

    public RecipeBook(BuildMasterPlugin plugin) {
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
        return id == null ? null : all().get(id);
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

    public int size() {
        return all().size();
    }
}
