package ru.industryreborn.items;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Employee;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.Module;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.util.Text;

/**
 * Чертежи: одноразовый предмет, который открывает мастерской рецепт администратора. Чертёж несёт метки в данных предмета (рецепт и уникальный
 * серийный номер), использованные номера запоминаются, поэтому копия предмета (дюп) не сработает. Изученный рецепт закреплён за предприятием.
 * Чертежи нельзя продать через аукцион казны и удалить: они уходят в хранилище администрации, откуда администратор забирает их и распоряжается как угодно.
 */
public final class BlueprintService {

    /** Прочитанный чертёж. */
    public record Info(String recipeId, String serial) { }

    private final IndustryRebornPlugin plugin;
    private final File file;
    private final NamespacedKey recipeKey;
    private final NamespacedKey serialKey;
    private final Set<String> used = new HashSet<>();
    private final List<byte[]> vault = new ArrayList<>();

    public BlueprintService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "blueprints.yml");
        this.recipeKey = new NamespacedKey("industryreborn", "blueprint");
        this.serialKey = new NamespacedKey("industryreborn", "blueprint_serial");
        load();
    }

    public boolean enabled() {
        return plugin.customItems().enabled() && plugin.modules().enabled(Module.PRODUCTION);
    }

    // ------------------------------------------------------------ предмет

    /** Материал чертежа по умолчанию (blueprints.material). */
    private Material baseMaterial() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("blueprints.material", "PAPER"));
        return m == null || m.isAir() || !m.isItem() ? Material.PAPER : m;
    }

    /** Новый чертёж рецепта с уникальным серийным номером. null - рецепт не рецепт администратора. */
    public ItemStack create(RecipeBook.Recipe recipe) {
        if (recipe == null || !recipe.custom()) {
            return null;
        }
        ItemStack stack = null;
        String itemRef = plugin.recipes().blueprintItem(recipe.id());
        if (itemRef != null) {
            ItemRef ref = ItemRef.parse(itemRef);
            stack = ref == null ? null : plugin.customItems().build(ref, 1);
        }
        boolean styled = stack != null;
        if (stack == null) {
            stack = new ItemStack(baseMaterial());
        }
        ItemMeta meta = stack.getItemMeta();
        String out = plugin.recipes().outputName(recipe);
        if (!styled) {
            meta.displayName(Text.mm("<aqua>Чертёж: <white>" + Text.esc(out)).decoration(TextDecoration.ITALIC, false));
        }
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
        lore.add(Text.mm("<gray>Рецепт: <white>" + Text.esc(out) + " × " + recipe.outputAmount()).decoration(TextDecoration.ITALIC, false));
        lore.add(Text.mm("<gray>" + Text.esc(RecipeBook.familyTitle(recipe.family()))).decoration(TextDecoration.ITALIC, false));
        lore.add(Text.mm("<yellow>Одноразовый: изучает управляющий или работник мастерской").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(recipeKey, PersistentDataType.STRING, recipe.id());
        meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        stack.setItemMeta(meta);
        return stack;
    }

    /** Чертёж из предмета либо null (не чертёж или метки повреждены). */
    public Info read(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        String recipe = pdc.get(recipeKey, PersistentDataType.STRING);
        String serial = pdc.get(serialKey, PersistentDataType.STRING);
        return recipe == null || serial == null ? null : new Info(recipe, serial);
    }

    public boolean isBlueprint(ItemStack stack) {
        return read(stack) != null;
    }

    public boolean used(String serial) {
        return used.contains(serial);
    }

    // ------------------------------------------------------------ изучение

    /** Кто может изучать чертежи предприятия: управляющий и работники. */
    public boolean mayLearn(Player player, Plot plot) {
        if (plot.isOwner(player.getUniqueId())) {
            return true;
        }
        for (Employee e : plugin.jobs().employeesOf(plot.id)) {
            if (e.player.equals(player.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    /** Мастерская игрока: указанная, иначе та, где он стоит, иначе единственная, где он управляющий или работник. */
    public Plot resolvePlot(Player player, String id) {
        if (id != null) {
            return plugin.plots().get(id);
        }
        Plot here = plugin.plots().at(player.getLocation());
        if (here != null && here.category == Category.WORKSHOP && mayLearn(player, here)) {
            return here;
        }
        List<Plot> mine = new ArrayList<>();
        for (Plot p : plugin.plots().all()) {
            if (p.category == Category.WORKSHOP && !p.isFree() && mayLearn(player, p)) {
                mine.add(p);
            }
        }
        return mine.size() == 1 ? mine.get(0) : null;
    }

    /** Изучает чертёж из основной руки: расходует его и закрепляет рецепт за предприятием. Возвращает ошибку или null. */
    public String learnFromHand(Player player, Plot plot) {
        if (!enabled()) {
            return "Чертежи сейчас отключены.";
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        Info info = read(hand);
        if (info == null) {
            return "Возьмите чертёж в основную руку.";
        }
        if (plot == null || plot.category != Category.WORKSHOP || plot.isFree()) {
            return "Чертёж изучает мастерская: укажите её или встаньте в неё.";
        }
        if (!mayLearn(player, plot)) {
            return "Чертежи изучает управляющий или работник мастерской.";
        }
        if (plugin.service().isLocked(plot)) {
            return "Мастерская сейчас обрабатывается, попробуйте позже.";
        }
        RecipeBook.Recipe recipe = plugin.recipes().get(info.recipeId());
        if (recipe == null || !recipe.custom()) {
            return "Рецепт этого чертежа больше не существует. Обратитесь к администрации.";
        }
        if (used.contains(info.serial())) {
            return "Этот чертёж уже использован.";
        }
        if (recipe.free()) {
            return "Этот рецепт доступен всем, чертёж не нужен.";
        }
        if (plot.learnedRecipes.contains(recipe.id())) {
            return "Мастерская уже знает этот рецепт.";
        }
        // чертёж тратится первым: если сохранение не удастся, рецепт всё равно не потеряется для предприятия
        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
        used.add(info.serial());
        plot.learnedRecipes.add(recipe.id());
        plugin.plots().save();
        save();
        plugin.journal().add(plot.id, player.getUniqueId(), player.getName(), Journal.Action.FACTORY,
                "Изучен рецепт «" + recipe.id().substring(7) + "» по чертежу");
        Text.ok(player, "Рецепт «" + plugin.recipes().outputName(recipe) + "» изучен мастерской " + plot.id
                + ". Чертёж израсходован, рецепт останется за предприятием.");
        return null;
    }

    // ------------------------------------------------------------ выдача и продажа

    /** Выдаёт чертёж игроку (лишнее - в хранилище игрока). Возвращает ошибку или null. */
    public String give(Player target, String recipeKey, int amount) {
        RecipeBook.Recipe recipe = plugin.recipes().get("custom:" + recipeKey.toLowerCase(Locale.ROOT));
        if (recipe == null) {
            return "Рецепт «" + recipeKey + "» не найден: /district recipe list";
        }
        if (amount < 1 || amount > 64) {
            return "Количество от 1 до 64.";
        }
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            items.add(create(recipe));
        }
        plugin.orders().deliver(target.getUniqueId(), "-", items, "Чертёж: " + recipe.id().substring(7));
        return null;
    }

    /** Покупка чертежа в магазине администрации: деньги идут в казну. Возвращает ошибку или null. */
    public String buy(Player player, String recipeKey) {
        if (!enabled()) {
            return "Чертежи сейчас отключены.";
        }
        RecipeBook.Recipe recipe = plugin.recipes().get("custom:" + recipeKey.toLowerCase(Locale.ROOT));
        double price = recipe == null ? 0 : plugin.recipes().blueprintPrice(recipe.id());
        if (recipe == null || recipe.free() || price <= 0) {
            return "Этот чертёж в магазине не продаётся.";
        }
        if (!player.hasPermission("industryreborn.use")) {
            return "Недостаточно прав.";
        }
        if (!plugin.economy().withdraw(player, price)) {
            return "Не хватает денег: нужно " + plugin.economy().format(price) + ".";
        }
        plugin.treasury().deposit(price, "Чертёж «" + recipe.id().substring(7) + "»: " + player.getName());
        plugin.saveTreasury();
        plugin.orders().deliver(player.getUniqueId(), "-", List.of(create(recipe)), "Куплен чертёж: " + recipe.id().substring(7));
        plugin.journal().add("-", player.getUniqueId(), player.getName(), Journal.Action.FACTORY,
                "Куплен чертёж «" + recipe.id().substring(7) + "» за " + plugin.economy().format(price));
        Text.ok(player, "Куплен чертёж «" + plugin.recipes().outputName(recipe) + "» за " + plugin.economy().format(price) + ".");
        return null;
    }

    // ------------------------------------------------------------ хранилище администрации

    public boolean mayUseVault(org.bukkit.command.CommandSender who) {
        return who.hasPermission("industryreborn.vault") || who.hasPermission("industryreborn.admin");
    }

    public int vaultSize() {
        return vault.size();
    }

    /** Кладёт предмет в хранилище администрации. */
    public void deposit(ItemStack stack, String reason) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        vault.add(stack.serializeAsBytes());
        save();
        plugin.getLogger().info("Хранилище чертежей: принято " + stack.getAmount() + " × " + stack.getType() + " (" + reason + ")");
    }

    /** Предметы хранилища (копии). */
    public List<ItemStack> vaultItems() {
        List<ItemStack> list = new ArrayList<>();
        for (byte[] b : vault) {
            list.add(ItemStack.deserializeBytes(b));
        }
        return list;
    }

    /** Забирает предмет из хранилища по номеру. null - нет такого. */
    public ItemStack takeFromVault(int index) {
        if (index < 0 || index >= vault.size()) {
            return null;
        }
        ItemStack stack = ItemStack.deserializeBytes(vault.remove(index));
        save();
        return stack;
    }

    // ------------------------------------------------------------ хранение

    private void load() {
        used.clear();
        vault.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        used.addAll(yml.getStringList("used"));
        for (String b : yml.getStringList("vault")) {
            try {
                vault.add(Base64.getDecoder().decode(b));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Хранилище чертежей: повреждённая запись пропущена");
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("used", new ArrayList<>(used));
        yml.set("vault", vault.stream().map(b -> Base64.getEncoder().encodeToString(b)).toList());
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить blueprints.yml", e);
        }
    }
}
