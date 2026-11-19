package ru.industryreborn.items;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.util.Text;

/**
 * Предметы плагина и их распознавание. Предмет всегда несёт метку в данных предмета (industryreborn:item = id), поэтому его нельзя подделать
 * переименованием; предметы Nexo распознаются через API Nexo (если он установлен). Определения лежат в items/*.yml:
 * <pre>
 * steel:
 *   material: IRON_INGOT
 *   name: "&lt;gray&gt;Стальной слиток"
 *   lore: ["Прочный сплав"]
 *   custom_model_data: 1001        # необязательно
 *   item_model: industryreborn:steel # необязательно
 *   nexo: steel_ingot               # необязательно: внешний вид из Nexo
 * </pre>
 */
public final class CustomItems {

    private final IndustryRebornPlugin plugin;
    private final NamespacedKey tagKey;
    private final Map<String, CustomItem> items = new LinkedHashMap<>();
    private ExternalItems external;

    public CustomItems(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.tagKey = new NamespacedKey("industryreborn", "item");
        this.external = new NexoBridge(plugin);
    }

    /** Только для тестов: подмена внешнего поставщика (Nexo). */
    public void setExternal(ExternalItems external) {
        this.external = external == null ? ExternalItems.NONE : external;
    }

    public ExternalItems external() {
        return external;
    }

    public NamespacedKey tagKey() {
        return tagKey;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("custom-items.enabled", true);
    }

    public File folder() {
        return new File(plugin.getDataFolder(), "items");
    }

    // ------------------------------------------------------------ загрузка

    /** Читает все items/*.yml. Возвращает список ошибок (неверные записи пропускаются). */
    public List<String> load() {
        List<String> errors = new ArrayList<>();
        items.clear();
        File dir = folder();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            files = new File[0];
        }
        java.util.Arrays.sort(files);
        for (File f : files) {
            try {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                for (String id : yml.getKeys(false)) {
                    ConfigurationSection s = yml.getConfigurationSection(id);
                    if (s == null) {
                        continue;
                    }
                    String error = add(id, s);
                    if (error != null) {
                        errors.add(f.getName() + ": " + error);
                    }
                }
            } catch (RuntimeException e) {
                errors.add(f.getName() + ": " + e.getMessage());
                plugin.getLogger().log(Level.WARNING, "Не удалось прочитать " + f, e);
            }
        }
        errors.addAll(loadNexo());
        for (String e : errors) {
            plugin.getLogger().warning("Предметы: " + e);
        }
        return errors;
    }

    // ------------------------------------------------------------ предметы Nexo с метками

    /** Папка предметов Nexo (custom-items.nexo-folder, по умолчанию plugins/Nexo/items). */
    public File nexoFolder() {
        String configured = plugin.getConfig().getString("custom-items.nexo-folder", "");
        if (configured != null && !configured.isBlank()) {
            File f = new File(configured);
            return f.isAbsolute() ? f : new File(plugin.getDataFolder().getParentFile(), configured);
        }
        return new File(plugin.getDataFolder().getParentFile(), "Nexo/items");
    }

    static final String TAG_ITEM = "industryreborn:item";
    static final String TAG_KIND = "industryreborn:kind";
    static final String TAG_SKIN_FOR = "industryreborn:skin_for";

    /**
     * Находит в файлах предметов Nexo записи с меткой PersistentData industryreborn:item (значение - id предмета плагина),
     * необязательно industryreborn:kind (item|tool|skin) и industryreborn:skin_for (pickaxe,axe,... или any).
     * Предметы регистрируются без отдельного определения в items/*.yml. Возвращает ошибки (неверные записи пропускаются).
     */
    private List<String> loadNexo() {
        List<String> errors = new ArrayList<>();
        if (!plugin.getConfig().getBoolean("custom-items.nexo-scan", true)) {
            return errors;
        }
        List<File> files = new ArrayList<>();
        collectYaml(nexoFolder(), files);
        java.util.Collections.sort(files);
        for (File f : files) {
            try {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                for (String nexoKey : yml.getKeys(false)) {
                    ConfigurationSection s = yml.getConfigurationSection(nexoKey);
                    if (s == null) {
                        continue;
                    }
                    String error = addFromNexo(nexoKey, s);
                    if (error != null) {
                        errors.add("Nexo/" + f.getName() + ": " + error);
                    }
                }
            } catch (RuntimeException e) {
                errors.add("Nexo/" + f.getName() + ": " + e.getMessage());
            }
        }
        return errors;
    }

    private static void collectYaml(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isDirectory()) {
                collectYaml(f, out);
            } else if (n.endsWith(".yml") || n.endsWith(".yaml")) {
                out.add(f);
            }
        }
    }

    /** Метки industryreborn:* из PersistentData записи Nexo: ключ -> значение. */
    private static java.util.Map<String, String> tags(ConfigurationSection s) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (java.util.Map<?, ?> m : s.getMapList("PersistentData")) {
            Object key = m.get("key");
            Object value = m.get("value");
            if (key != null && value != null && String.valueOf(key).toLowerCase(Locale.ROOT).startsWith("industryreborn:")) {
                map.put(String.valueOf(key).toLowerCase(Locale.ROOT), String.valueOf(value).trim());
            }
        }
        return map;
    }

    private String addFromNexo(String nexoKey, ConfigurationSection s) {
        var tags = tags(s);
        String rawId = tags.get(TAG_ITEM);
        if (rawId == null) {
            return null;                                   // обычный предмет Nexo, плагина не касается
        }
        String id = rawId.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_]{1,40}")) {
            return "«" + nexoKey + "»: id «" + rawId + "» - латиница, цифры и _ (до 40 знаков)";
        }
        if (items.containsKey(id)) {
            return "«" + nexoKey + "»: id «" + id + "» уже занят" + (items.get(id).nexoSource() ? " другим предметом Nexo «" + items.get(id).nexoId() + "»" : " в items/*.yml");
        }
        ItemKind kind = tags.containsKey(TAG_KIND) ? ItemKind.parse(tags.get(TAG_KIND)) : ItemKind.ITEM;
        if (kind == null) {
            return "«" + nexoKey + "»: неизвестный тип «" + tags.get(TAG_KIND) + "» (item, tool или skin)";
        }
        Set<String> skinFor = new java.util.LinkedHashSet<>();
        if (kind == ItemKind.SKIN) {
            String raw = tags.getOrDefault(TAG_SKIN_FOR, "any");
            for (String part : raw.split("[,; ]+")) {
                if (part.isBlank()) {
                    continue;
                }
                String p = part.toLowerCase(Locale.ROOT);
                if (!SKIN_TARGETS.contains(p)) {
                    return "«" + nexoKey + "»: skin_for «" + part + "» - одно из " + SKIN_TARGETS;
                }
                skinFor.add(p);
            }
            if (skinFor.isEmpty()) {
                skinFor.add("any");
            }
        }
        Material material = Material.matchMaterial(s.getString("material", "PAPER"));
        if (material == null || material.isAir() || !material.isItem()) {
            material = Material.PAPER;
        }
        String model = s.getString("Components.item_model");
        String itemModel = model != null && NamespacedKey.fromString(model.toLowerCase(Locale.ROOT)) != null ? model.toLowerCase(Locale.ROOT)
                : "nexo:" + nexoKey.toLowerCase(Locale.ROOT);
        items.put(id, new CustomItem(id, material, s.getString("itemname", Text.esc(nexoKey)), s.getStringList("lore"), null, itemModel, nexoKey,
                kind, Set.copyOf(skinFor), true));
        return null;
    }

    /** Типы инструментов, на которые может ставиться скин. */
    public static final List<String> SKIN_TARGETS = List.of("pickaxe", "axe", "shovel", "hoe", "sword", "any");

    /** Добавляет определение из секции. Возвращает ошибку или null. */
    public String add(String rawId, ConfigurationSection s) {
        String id = rawId.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_]{1,40}")) {
            return "«" + rawId + "»: id - латиница, цифры и _ (до 40 знаков)";
        }
        if (items.containsKey(id)) {
            return "«" + id + "»: уже определён";
        }
        String nexo = s.getString("nexo");
        Material material = Material.matchMaterial(s.getString("material", nexo == null ? "" : "PAPER"));
        if (material == null || material.isAir() || !material.isItem()) {
            return "«" + id + "»: неизвестный материал «" + s.getString("material") + "»";
        }
        String itemModel = s.getString("item_model");
        if (itemModel != null && NamespacedKey.fromString(itemModel.toLowerCase(Locale.ROOT)) == null) {
            return "«" + id + "»: неверный item_model «" + itemModel + "»";
        }
        ItemKind kind = s.contains("kind") ? ItemKind.parse(s.getString("kind")) : ItemKind.ITEM;
        if (kind == null) {
            return "«" + id + "»: неизвестный тип «" + s.getString("kind") + "» (item, tool или skin)";
        }
        Set<String> skinFor = new java.util.LinkedHashSet<>();
        if (kind == ItemKind.SKIN) {
            for (String part : s.getStringList("skin_for")) {
                if (!SKIN_TARGETS.contains(part.toLowerCase(Locale.ROOT))) {
                    return "«" + id + "»: skin_for «" + part + "» - одно из " + SKIN_TARGETS;
                }
                skinFor.add(part.toLowerCase(Locale.ROOT));
            }
            if (skinFor.isEmpty()) {
                skinFor.add("any");
            }
        }
        items.put(id, new CustomItem(id, material, s.getString("name", Text.esc(id)), s.getStringList("lore"),
                s.contains("custom_model_data") ? s.getInt("custom_model_data") : null, itemModel, nexo == null || nexo.isBlank() ? null : nexo,
                kind, Set.copyOf(skinFor), false));
        return null;
    }

    public CustomItem get(String id) {
        return id == null ? null : items.get(id.toLowerCase(Locale.ROOT));
    }

    public List<CustomItem> all() {
        return List.copyOf(items.values());
    }

    // ------------------------------------------------------------ предметы

    /** Предмет существует (определён у нас; для Nexo - есть в Nexo). */
    public boolean exists(ItemRef ref) {
        return switch (ref.kind()) {
            case VANILLA -> true;
            case PLUGIN -> items.containsKey(ref.id());
            case NEXO -> external.exists(ref.id());
        };
    }

    /** Ссылка известна: предмет плагина определён, ванильный материал верен; предметы Nexo не проверяются, пока Nexo не загрузился. */
    public boolean known(ItemRef ref) {
        return ref.kind() == ItemRef.Kind.NEXO ? !external.available() || external.exists(ref.id()) : exists(ref);
    }

    /** Основа предмета: материал, из которого он строится (для значков меню). */
    public Material baseMaterial(ItemRef ref) {
        if (ref.kind() == ItemRef.Kind.VANILLA) {
            return ref.material();
        }
        if (ref.kind() == ItemRef.Kind.PLUGIN) {
            CustomItem d = items.get(ref.id());
            return d == null ? Material.BARRIER : d.material();
        }
        ItemStack built = external.build(ref.id());
        return built == null ? Material.PAPER : built.getType();
    }

    /** Показываемое название без разметки: для предметов плагина - из определения. */
    public String displayName(ItemRef ref) {
        if (ref.kind() == ItemRef.Kind.PLUGIN) {
            CustomItem d = items.get(ref.id());
            return d == null ? ref.id() : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Text.mm(d.name()));
        }
        return ref.kind() == ItemRef.Kind.NEXO ? ref.id() : ref.id().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Строит предмет. Для недоступных (нет в определениях, Nexo не загрузился) возвращает null. */
    public ItemStack build(ItemRef ref, int amount) {
        int n = Math.max(1, amount);
        switch (ref.kind()) {
            case VANILLA -> {
                Material m = ref.material();
                return m == null ? null : new ItemStack(m, n);
            }
            case NEXO -> {
                ItemStack stack = external.build(ref.id());
                if (stack == null) {
                    return null;
                }
                stack.setAmount(n);
                return stack;
            }
            default -> {
                CustomItem d = items.get(ref.id());
                if (d == null) {
                    return null;
                }
                ItemStack stack = null;
                if (d.nexoId() != null) {
                    stack = external.build(d.nexoId());
                }
                if (stack == null && d.nexoSource()) {
                    return null;            // предмет живёт в Nexo (модель, прочность, свойства): без Nexo подделку из материала не выдаём
                }
                if (stack == null) {
                    stack = new ItemStack(d.material());
                    decorate(stack, d);
                }
                tag(stack, d.id());
                stack.setAmount(n);
                return stack;
            }
        }
    }

    private void decorate(ItemStack stack, CustomItem d) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.displayName(Text.mm(d.name()).decoration(TextDecoration.ITALIC, false));
        if (!d.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : d.lore()) {
                lore.add(Text.mm(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }
        try {
            if (d.model() != null) {
                meta.setCustomModelData(d.model());
            }
            if (d.itemModel() != null) {
                meta.setItemModel(NamespacedKey.fromString(d.itemModel().toLowerCase(Locale.ROOT)));
            }
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.FINE, "Внешний вид предмета " + d.id() + " не применён", e);
        }
        stack.setItemMeta(meta);
    }

    /** Ставит на предмет метку плагина. */
    public void tag(ItemStack stack, String id) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, id.toLowerCase(Locale.ROOT));
        stack.setItemMeta(meta);
    }

    /** Метка плагина на предмете (id предмета плагина) либо null. */
    public String tagOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
    }

    /**
     * Что это за предмет: предмет плагина (по метке; метка с неизвестным id не считается), затем предмет Nexo. null - обычный ванильный предмет.
     * Обычные ванильные предметы с названием или свойствами тоже возвращают null: их различает только метка.
     */
    public ItemRef identify(ItemStack stack) {
        String tag = tagOf(stack);
        if (tag != null && items.containsKey(tag)) {
            return new ItemRef(ItemRef.Kind.PLUGIN, tag);
        }
        String nexo = external.idOf(stack);
        return nexo == null ? null : new ItemRef(ItemRef.Kind.NEXO, nexo);
    }

    // ------------------------------------------------------------ нетронутые экземпляры

    /** Ключ метки скина на инструменте (industryreborn:skin = id скина). */
    public NamespacedKey skinKey() {
        return new NamespacedKey("industryreborn", "skin");
    }

    /**
     * Экземпляр нетронутый: без износа, зачарований, стоимости починки и применённого скина. Склад товаров и сырьё станков принимают предметы
     * плагина и Nexo только такими, иначе битый зачарованный инструмент превратился бы в новый (склад хранит товар как «новый предмет»).
     */
    public boolean pristine(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage() && d.getDamage() > 0) {
            return false;
        }
        if (meta.hasEnchants()) {
            return false;
        }
        if (meta instanceof org.bukkit.inventory.meta.Repairable r && r.hasRepairCost() && r.getRepairCost() > 0) {
            return false;
        }
        return !meta.getPersistentDataContainer().has(skinKey(), PersistentDataType.STRING);
    }

    // ------------------------------------------------------------ выгрузка для Nexo

    /**
     * Заготовка предметов для Nexo и NexoHelper: все предметы плагина с меткой PersistentData industryreborn:item. Текстуры, модели и Pack
     * дописываются в Nexo (NexoHelper). Возвращает файл.
     */
    public File exportNexo() throws java.io.IOException {
        YamlConfiguration yml = new YamlConfiguration();
        for (CustomItem d : items.values()) {
            String key = d.nexoId() != null ? d.nexoId() : d.id();
            String p = key + ".";
            yml.set(p + "itemname", d.name());
            yml.set(p + "material", d.material().name());
            if (!d.lore().isEmpty()) {
                yml.set(p + "lore", d.lore());
            }
            List<Map<String, String>> pdc = new ArrayList<>();
            Map<String, String> tag = new LinkedHashMap<>();
            tag.put("type", "STRING");
            tag.put("key", "industryreborn:item");
            tag.put("value", d.id());
            pdc.add(tag);
            yml.set(p + "PersistentData", pdc);
        }
        File dir = new File(plugin.getDataFolder(), "nexo-export");
        dir.mkdirs();
        File out = new File(dir, "industryreborn_items.yml");
        yml.options().header("Предметы IndustryReborn для Nexo. Положите файл в plugins/Nexo/items, добавьте Pack.model и текстуры (удобно через NexoHelper).");
        yml.save(out);
        return out;
    }
}
