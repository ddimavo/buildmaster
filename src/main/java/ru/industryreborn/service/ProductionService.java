package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Station;
import ru.industryreborn.util.Text;

/**
 * Производство мастерской. Каждый станок получает задание (рецепт из реестра игры) и работает сам: сырьё берётся с общего
 * склада мастерской, который пополняется из сундуков-приёмников, продукция выкладывается в сундуки продукции. Станки без
 * задания обслуживают заказы игроков (они хранятся отдельно и в продукцию не попадают).
 */
public final class ProductionService {

    public enum Status {
        NO_RECIPE("не назначено"), NO_POWER("обесточен"), NO_RAW("нет сырья"), OUTPUT_FULL("нет места для продукции"),
        WORKING("работает");

        private final String title;

        Status(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** Сводка по станку для меню и голограмм. */
    public record View(String pos, Material block, Station station, RecipeBook.Recipe recipe, Status status,
                       double ratio, int output, int bufferCapacity) { }

    private final IndustryRebornPlugin plugin;
    private long lastTickSec = Long.MIN_VALUE;

    public ProductionService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ вместимость и скорость

    private double timeFactor() {
        return Math.max(0, plugin.getConfig().getDouble("production.time-factor", 1.0));
    }

    public int stockCapacity(Plot plot) {
        return ProductionMath.capacity(plugin.getConfig().getInt("production.stock-items", 1024), plugin.upgrades().of(plot).stock());
    }

    public int stockTotal(Plot plot) {
        return plot.stock.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int stock(Plot plot, Material m) {
        return plot.stock.getOrDefault(m, 0);
    }

    /** Вместимость буфера продукции станка в предметах. */
    public int bufferCapacity(Plot plot, RecipeBook.Recipe recipe) {
        int baseBatches = plugin.getConfig().getInt("production.buffer-batches", 64);
        return ProductionMath.capacity(baseBatches * recipe.outputAmount(), plugin.upgrades().of(plot).buffer());
    }

    /** Время партии с учётом скорости улучшений и общего множителя. */
    public double secondsPerBatch(Plot plot, RecipeBook.Recipe recipe) {
        return ProductionMath.secondsPerBatch(recipe.seconds(), 1, plugin.upgrades().of(plot).speed(), timeFactor());
    }

    /** Сколько станков мастерской могут иметь задание (зависит от уровня). */
    public int slots(Plot plot) {
        return plugin.upgrades().of(plot).maxLines();
    }

    public int assignedCount(Plot plot) {
        return (int) plot.machineStates.values().stream().filter(s -> s.recipe != null).count();
    }

    /** Сколько станков с данным ключом тарифа заняты производством (они не обслуживают заказы игроков). */
    public int assignedCount(Plot plot, String tariffKey) {
        int n = 0;
        for (var e : plot.machineStates.entrySet()) {
            Station st = plugin.stations().machines(plot).get(e.getKey());
            if (e.getValue().recipe != null && st != null && tariffKey.equals(st.tariffKey())) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------ станки и сырьё

    private static int[] parse(String pos) {
        String[] c = pos.split(",");
        return new int[] {Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])};
    }

    private Block blockAt(Plot plot, String pos) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            return null;
        }
        int[] c = parse(pos);
        return world.getBlockAt(c[0], c[1], c[2]);
    }

    /** Материалы, нужные заданиям станков: только их сундук-приёмник забирает на склад. */
    public Set<Material> acceptedMaterials(Plot plot) {
        Set<Material> set = new HashSet<>();
        for (Plot.MachineState s : plot.machineStates.values()) {
            RecipeBook.Recipe r = plugin.recipes().get(s.recipe);
            if (r != null) {
                for (RecipeBook.Ingredient i : r.inputs()) {
                    set.addAll(i.options());
                }
            }
        }
        return set;
    }

    /** Что будет израсходовано на одну партию (материал -> штук) или null, если сырья не хватает. */
    public Map<Material, Integer> plan(Plot plot, RecipeBook.Recipe recipe) {
        Map<Material, Integer> use = new LinkedHashMap<>();
        for (RecipeBook.Ingredient ing : recipe.inputs()) {
            int need = ing.count();
            for (Material m : ing.options()) {
                int have = stock(plot, m) - use.getOrDefault(m, 0);
                int take = Math.min(have, need);
                if (take > 0) {
                    use.merge(m, take, Integer::sum);
                    need -= take;
                }
                if (need == 0) {
                    break;
                }
            }
            if (need > 0) {
                return null;
            }
        }
        return use;
    }

    public View view(Plot plot, String pos) {
        Block block = blockAt(plot, pos);
        Material type = block == null ? Material.AIR : block.getType();
        Station station = plugin.stations().machines(plot).get(pos);
        Plot.MachineState state = plot.machineStates.get(pos);
        RecipeBook.Recipe recipe = state == null ? null : plugin.recipes().get(state.recipe);
        if (recipe == null) {
            return new View(pos, type, station, null, Status.NO_RECIPE, 0, state == null ? 0 : state.output, 0);
        }
        int cap = bufferCapacity(plot, recipe);
        Status status;
        if (!plugin.power().isPowered(plot)) {
            status = Status.NO_POWER;
        } else if (state.output + recipe.outputAmount() > cap) {
            status = Status.OUTPUT_FULL;
        } else if (plan(plot, recipe) == null) {
            status = Status.NO_RAW;
        } else {
            status = Status.WORKING;
        }
        double seconds = secondsPerBatch(plot, recipe);
        double ratio = status == Status.WORKING && seconds > 0 && !Double.isInfinite(seconds)
                ? Math.min(1, state.progress / seconds) : 0;
        return new View(pos, type, station, recipe, status, ratio, state.output, cap);
    }

    /** Рецепты, доступные станку мастерской: из реестра игры, а для стола зачарований - одобренные администратором. */
    public List<RecipeBook.Recipe> recipesFor(Plot plot, Material block) {
        if ("enchant".equals(RecipeBook.familyOf(block))) {
            List<RecipeBook.Recipe> list = new ArrayList<>();
            for (String id : plot.approvedEnchants) {
                RecipeBook.Recipe r = plugin.recipes().get(id);
                if (r != null) {
                    list.add(r);
                }
            }
            list.sort(java.util.Comparator.comparing(RecipeBook.Recipe::id));
            return list;
        }
        return plugin.recipes().forBlock(block);
    }

    /** Все станки мастерской (с заданием и без) по позиции. */
    public List<View> views(Plot plot) {
        List<String> keys = new ArrayList<>(plugin.stations().machines(plot).keySet());
        java.util.Collections.sort(keys);
        List<View> list = new ArrayList<>();
        for (String pos : keys) {
            list.add(view(plot, pos));
        }
        return list;
    }

    // ------------------------------------------------------------ проверки

    private String checkManager(Player manager, Plot plot) {
        if (plot.category != Category.WORKSHOP) {
            return "Производство есть только у мастерских.";
        }
        if (!plot.isOwner(manager.getUniqueId()) && !manager.hasPermission("industryreborn.admin")) {
            return "Производством управляет только управляющий мастерской.";
        }
        if (plugin.service().isLocked(plot)) {
            return "Мастерская сейчас обрабатывается, попробуйте позже.";
        }
        return null;
    }

    /** Платёж со счёта предприятия в казну. Возвращает ошибку или null. */
    private String payFromAccount(Plot plot, double price, String reason) {
        if (price <= 0) {
            return null;
        }
        plugin.power().touch(plot);
        if (plot.account.rawBalance() + 1e-9 < price) {
            return "На счёте участка не хватает денег: нужно " + plugin.economy().format(price)
                    + ", есть " + plugin.economy().format(plot.account.rawBalance())
                    + ". Пополните счёт: /district account deposit <сумма>";
        }
        plot.account.withdraw(price);
        plugin.treasury().deposit(price, reason);
        plugin.saveTreasury();
        return null;
    }

    // ------------------------------------------------------------ задания станкам

    /** Назначает станку рецепт. Рецепт должен подходить типу станка, число станков с заданием ограничено уровнем. */
    public String assign(Player manager, Plot plot, String pos, String recipeId) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        Station station = plugin.stations().machines(plot).get(pos);
        Block block = blockAt(plot, pos);
        if (station == null || block == null) {
            return "Здесь нет рабочего станка мастерской.";
        }
        String family = RecipeBook.familyOf(block.getType());
        if (family == null) {
            return "Этот станок ничего не производит: он обслуживает заказы игроков.";
        }
        RecipeBook.Recipe recipe = plugin.recipes().get(recipeId);
        if (recipe == null) {
            return "Неизвестный рецепт «" + recipeId + "».";
        }
        if (!recipe.family().equals(family)) {
            return "Этот рецепт для другого станка (" + RecipeBook.familyTitle(recipe.family()) + ").";
        }
        if (recipe.enchanting() && !plot.approvedEnchants.contains(recipe.id())) {
            return "Это зачарование ещё не одобрено администратором для вашей мастерской: /district enchant propose.";
        }
        Plot.MachineState existing = plot.machineStates.get(pos);
        boolean already = existing != null && existing.recipe != null;
        if (!already && assignedCount(plot) >= slots(plot)) {
            return "Все станки с заданием заняты (" + slots(plot) + "). Улучшите мастерскую или снимите задание с другого станка.";
        }
        Plot.MachineState state = existing != null ? existing : new Plot.MachineState();
        if (existing != null && existing.recipe != null && !existing.recipe.equals(recipe.id()) && existing.output > 0) {
            return "Сначала заберите продукцию станка: она относится к прежнему рецепту.";
        }
        state.recipe = recipe.id();
        state.progress = 0;
        plot.machineStates.put(pos, state);
        plugin.plots().save();
        plugin.journal().add(plot.id, manager.getUniqueId(), manager.getName(), Journal.Action.FACTORY,
                "Станок " + pos + ": " + recipe.id());
        return null;
    }

    public String clear(Player manager, Plot plot, String pos) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        Plot.MachineState state = plot.machineStates.get(pos);
        if (state == null || state.recipe == null) {
            return "У этого станка нет задания.";
        }
        state.recipe = null;
        state.progress = 0;
        if (state.output <= 0) {
            plot.machineStates.remove(pos);
        }
        plugin.plots().save();
        return null;
    }

    // ------------------------------------------------------------ сундуки

    private static boolean isContainer(Block b) {
        try {
            return b.getState() instanceof Container;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Помечает контейнер в мастерской как сундук сырья (raw) или продукции. Один блок не может быть и тем и другим. */
    public String markChest(Player manager, Plot plot, Block block, boolean raw) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        if (!plot.box.contains(block.getX(), block.getY(), block.getZ()) || !plot.world.equals(block.getWorld().getName())) {
            return "Сундук должен стоять внутри мастерской.";
        }
        if (!isContainer(block)) {
            return "Нужно смотреть на сундук или другой контейнер.";
        }
        String pos = StationService.posKey(block.getX(), block.getY(), block.getZ());
        int max = plugin.getConfig().getInt("production.chests-max", 4);
        List<String> target = raw ? plot.rawChests : plot.productChests;
        List<String> other = raw ? plot.productChests : plot.rawChests;
        if (target.contains(pos)) {
            return "Этот сундук уже помечен.";
        }
        if (other.contains(pos)) {
            return "Этот сундук уже помечен как " + (raw ? "сундук продукции" : "сундук сырья") + ": сначала снимите пометку.";
        }
        if (target.size() >= max) {
            return "Сундуков " + (raw ? "сырья" : "продукции") + " не больше " + max + ".";
        }
        target.add(pos);
        plugin.plots().save();
        return null;
    }

    public String unmarkChest(Player manager, Plot plot, Block block) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        String pos = StationService.posKey(block.getX(), block.getY(), block.getZ());
        boolean removed = plot.rawChests.remove(pos) | plot.productChests.remove(pos);
        if (!removed) {
            return "Этот сундук не помечен.";
        }
        plugin.plots().save();
        return null;
    }

    /** Убирает пометку, если блок сломан или заменён. */
    public void chestBroken(Plot plot, int x, int y, int z) {
        String pos = StationService.posKey(x, y, z);
        if (plot.rawChests.remove(pos) | plot.productChests.remove(pos)) {
            plugin.plots().save();
        }
    }

    private Inventory inventoryAt(Plot plot, String pos) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            return null;
        }
        int[] c = parse(pos);
        if (!world.isChunkLoaded(c[0] >> 4, c[2] >> 4)) {
            return null;
        }
        BlockState state = world.getBlockAt(c[0], c[1], c[2]).getState();
        return state instanceof Container container ? container.getInventory() : null;
    }

    private static boolean plainStack(ItemStack s) {
        return s != null && !s.getType().isAir() && s.isSimilar(new ItemStack(s.getType()));
    }

    /** Сундуки сырья переносят подходящие обычные предметы на склад мастерской. Возвращает число перенесённых предметов. */
    public int inbox(Plot plot) {
        if (plot.rawChests.isEmpty()) {
            return 0;
        }
        Set<Material> accepted = acceptedMaterials(plot);
        if (accepted.isEmpty()) {
            return 0;
        }
        int room = stockCapacity(plot) - stockTotal(plot);
        int moved = 0;
        for (String pos : new ArrayList<>(plot.rawChests)) {
            Inventory inv = inventoryAt(plot, pos);
            if (inv == null) {
                continue;
            }
            for (int i = 0; i < inv.getSize() && room > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (!plainStack(s) || !accepted.contains(s.getType())) {
                    continue;
                }
                int take = Math.min(room, s.getAmount());
                plot.stock.merge(s.getType(), take, Integer::sum);
                room -= take;
                moved += take;
                if (take >= s.getAmount()) {
                    inv.setItem(i, null);
                } else {
                    s.setAmount(s.getAmount() - take);
                    inv.setItem(i, s);
                }
            }
        }
        return moved;
    }

    /** Продукция станков выкладывается в сундуки продукции; что не поместилось, ждёт в буфере станка. */
    private boolean outbox(Plot plot) {
        Plot linked = plugin.links().target(plot);
        if (plot.productChests.isEmpty() && linked == null && !plot.storefront) {
            return false;
        }
        boolean changed = false;
        for (var e : plot.machineStates.entrySet()) {
            Plot.MachineState state = e.getValue();
            RecipeBook.Recipe r = plugin.recipes().get(state.recipe);
            if (r == null || state.output <= 0) {
                continue;
            }
            // связь с магазином или складом (или своя витрина) важнее сундука продукции
            Plot dest = linked != null ? linked : plot.storefront ? plot : null;
            if (dest != null && !r.enchanting()) {
                int put = plugin.goods().add(dest, r.output(), state.output);
                state.output -= put;
                changed |= put > 0;
                continue;
            }
            for (String pos : plot.productChests) {
                Inventory inv = inventoryAt(plot, pos);
                if (inv == null || state.output <= 0) {
                    continue;
                }
                int stack = Math.max(1, plugin.recipes().stackOf(r).getMaxStackSize());
                while (state.output > 0) {
                    int n = Math.min(stack, state.output);
                    ItemStack out = plugin.recipes().stackOf(r);
                    out.setAmount(n);
                    var left = inv.addItem(out);
                    int rest = left.isEmpty() ? 0 : left.values().iterator().next().getAmount();
                    int placed = n - rest;
                    state.output -= placed;
                    changed |= placed > 0;
                    if (rest > 0) {
                        break;
                    }
                }
            }
        }
        return changed;
    }

    // ------------------------------------------------------------ склад: ручная загрузка и выдача

    /** Загружает подходящее сырьё из инвентаря на склад (то же, что делает сундук сырья). */
    public String load(Player manager, Plot plot, int requested) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        Set<Material> accepted = acceptedMaterials(plot);
        if (accepted.isEmpty()) {
            return "Ни у одного станка нет задания: загружать пока нечего.";
        }
        int room = stockCapacity(plot) - stockTotal(plot);
        if (room <= 0) {
            return "Склад мастерской полон (" + stockCapacity(plot) + ").";
        }
        PlayerInventory inv = manager.getInventory();
        int limit = requested > 0 ? Math.min(requested, room) : room;
        int moved = 0;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && moved < limit; i++) {
            ItemStack s = contents[i];
            if (!plainStack(s) || !accepted.contains(s.getType())) {
                continue;
            }
            int take = Math.min(limit - moved, s.getAmount());
            plot.stock.merge(s.getType(), take, Integer::sum);
            moved += take;
            s.setAmount(s.getAmount() - take);
            inv.setItem(i, s.getAmount() <= 0 ? null : s);
        }
        if (moved == 0) {
            return "В инвентаре нет подходящего сырья (обычные предметы без названий и свойств).";
        }
        plugin.plots().save();
        Text.ok(manager, "На склад мастерской загружено " + moved + ". Всего: " + stockTotal(plot) + " из " + stockCapacity(plot) + ".");
        return null;
    }

    /** Забирает продукцию из буферов станков в инвентарь (излишек уходит в хранилище). */
    public String collect(Player manager, Plot plot) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        List<ItemStack> items = new ArrayList<>();
        int total = 0;
        for (Plot.MachineState state : plot.machineStates.values()) {
            RecipeBook.Recipe r = plugin.recipes().get(state.recipe);
            if (r == null || state.output <= 0) {
                continue;
            }
            items.addAll(ServiceCatalog.split(plugin.recipes().stackOf(r), state.output));
            total += state.output;
            state.output = 0;
        }
        if (total == 0) {
            return "В буферах станков нет продукции.";
        }
        plugin.plots().save();
        plugin.orders().deliver(manager.getUniqueId(), plot.id, items, "Продукция производства, участок " + plot.id);
        Text.ok(manager, "Забрано продукции: " + total + ". Если не хватило места, остальное в хранилище: /district storage");
        return null;
    }

    // ------------------------------------------------------------ улучшения

    /** Повышает уровень мастерской; оплата со счёта предприятия в казну. */
    public String upgrade(Player manager, Plot plot) {
        String error = checkManager(manager, plot);
        if (error != null) {
            return error;
        }
        UpgradeCatalog.Level next = plugin.upgrades().next(plot);
        if (next == null) {
            return "Достигнут максимальный уровень мастерской.";
        }
        String pay = payFromAccount(plot, next.price(), "Улучшение мастерской " + plot.id + " до уровня " + next.level());
        if (pay != null) {
            return pay;
        }
        plot.level = next.level();
        plugin.plots().save();
        plugin.journal().add(plot.id, manager.getUniqueId(), manager.getName(), Journal.Action.UPGRADE,
                "Уровень " + next.level() + " за " + plugin.economy().format(next.price()));
        Text.ok(manager, "Мастерская улучшена до уровня <white>" + next.level() + "</white>: станков с заданием " + next.maxLines()
                + ", скорость ×" + next.speed() + ", склад ×" + next.stock() + ", буфер ×" + next.buffer() + ".");
        return null;
    }

    // ------------------------------------------------------------ закрытие мастерской

    /**
     * Мастерская закрывается: склад и продукция возвращаются управляющему (при изъятии за нарушение арестовываются),
     * задания, сундуки и улучшения сбрасываются.
     */
    public void closeWorkshop(Plot plot, UUID manager, boolean arrest, String reason) {
        List<ItemStack> items = new ArrayList<>();
        for (var e : plot.stock.entrySet()) {
            items.addAll(ServiceCatalog.split(new ItemStack(e.getKey()), e.getValue()));
        }
        for (Plot.MachineState state : plot.machineStates.values()) {
            RecipeBook.Recipe r = plugin.recipes().get(state.recipe);
            if (r != null && state.output > 0) {
                items.addAll(ServiceCatalog.split(plugin.recipes().stackOf(r), state.output));
            }
        }
        if (manager != null && !items.isEmpty()) {
            if (arrest) {
                plugin.storage().add(manager, plot.id, items, ru.industryreborn.model.StorageEntry.State.ARRESTED, reason,
                        "factory-" + UUID.randomUUID());
            } else {
                plugin.orders().deliver(manager, plot.id, items, "Производство закрыто: мастерская " + plot.id);
            }
        }
        plot.stock.clear();
        plot.machineStates.clear();
        plot.rawChests.clear();
        plot.productChests.clear();
        plot.legacyLines.clear();
        plot.approvedEnchants.clear();
        plot.level = 0;
    }

    // ------------------------------------------------------------ перенос данных старых линий

    private static final Map<String, Material[]> LEGACY = Map.of(
            "iron-ingots", new Material[] {Material.RAW_IRON, Material.IRON_INGOT},
            "gold-ingots", new Material[] {Material.RAW_GOLD, Material.GOLD_INGOT},
            "copper-ingots", new Material[] {Material.RAW_COPPER, Material.COPPER_INGOT},
            "glass", new Material[] {Material.SAND, Material.GLASS},
            "stone", new Material[] {Material.COBBLESTONE, Material.STONE},
            "tool-repair", new Material[] {Material.IRON_INGOT, Material.IRON_BLOCK});

    /** Сырьё старых линий переходит на склад, продукция возвращается управляющему. */
    public void migrate() {
        boolean changed = false;
        for (Plot plot : plugin.plots().all()) {
            if (plot.legacyLines.isEmpty()) {
                continue;
            }
            List<ItemStack> items = new ArrayList<>();
            for (var e : plot.legacyLines.entrySet()) {
                Material[] pair = LEGACY.get(e.getKey());
                if (pair == null) {
                    plugin.getLogger().warning("Неизвестная старая линия " + e.getKey() + " на участке " + plot.id + ": данные пропущены");
                    continue;
                }
                if (e.getValue()[0] > 0) {
                    plot.stock.merge(pair[0], e.getValue()[0], Integer::sum);
                }
                if (e.getValue()[1] > 0) {
                    items.addAll(ServiceCatalog.split(new ItemStack(pair[1]), e.getValue()[1]));
                }
            }
            plot.legacyLines.clear();
            if (plot.owner != null && !items.isEmpty()) {
                plugin.orders().deliver(plot.owner, plot.id, items, "Перенос производства (обновление плагина), участок " + plot.id);
            }
            changed = true;
        }
        if (changed) {
            plugin.plots().save();
        }
    }

    // ------------------------------------------------------------ работа

    /** Раз в секунду: сундуки сырья, станки по биллинговому времени (пока есть электричество), сундуки продукции. */
    public void tick() {
        long now = plugin.power().nowSec();
        double dt = lastTickSec == Long.MIN_VALUE ? 0 : Math.max(0, now - lastTickSec);
        lastTickSec = now;
        boolean changed = false;
        for (Plot plot : plugin.plots().all()) {
            if (plot.category != Category.WORKSHOP || plot.isFree() || plot.restorePending || plugin.service().isLocked(plot)
                    || !plugin.stations().ready(plot)) {
                continue;
            }
            changed |= prune(plot);
            if (plot.machineStates.isEmpty() && plot.rawChests.isEmpty() && plot.productChests.isEmpty()) {
                continue;
            }
            changed |= inbox(plot) > 0;
            for (var e : new ArrayList<>(plot.machineStates.entrySet())) {
                changed |= run(plot, e.getKey(), e.getValue(), dt);
            }
            changed |= outbox(plot);
        }
        if (changed) {
            plugin.plots().save();
        }
    }

    /** Убирает задания станков, которых больше нет (разрушены): продукция возвращается управляющему. */
    private boolean prune(Plot plot) {
        boolean changed = false;
        for (String pos : new ArrayList<>(plot.machineStates.keySet())) {
            if (plugin.stations().machines(plot).containsKey(pos)) {
                continue;
            }
            Plot.MachineState state = plot.machineStates.remove(pos);
            RecipeBook.Recipe r = plugin.recipes().get(state.recipe);
            if (r != null && state.output > 0 && plot.owner != null) {
                plugin.orders().deliver(plot.owner, plot.id, ServiceCatalog.split(plugin.recipes().stackOf(r), state.output),
                        "Станок разрушен, продукция возвращена, участок " + plot.id);
            }
            changed = true;
        }
        for (String pos : new ArrayList<>(plot.rawChests)) {
            if (chestGone(plot, pos)) {
                plot.rawChests.remove(pos);
                changed = true;
            }
        }
        for (String pos : new ArrayList<>(plot.productChests)) {
            if (chestGone(plot, pos)) {
                plot.productChests.remove(pos);
                changed = true;
            }
        }
        return changed;
    }

    private boolean chestGone(Plot plot, String pos) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            return false;
        }
        int[] c = parse(pos);
        return world.isChunkLoaded(c[0] >> 4, c[2] >> 4) && !isContainer(world.getBlockAt(c[0], c[1], c[2]));
    }

    private boolean run(Plot plot, String pos, Plot.MachineState state, double dt) {
        RecipeBook.Recipe recipe = plugin.recipes().get(state.recipe);
        if (recipe == null) {
            return false;
        }
        Station station = plugin.stations().machines(plot).get(pos);
        View v = view(plot, pos);
        if (v.status() != Status.WORKING || station == null) {
            boolean idle = state.progress != 0;
            state.progress = 0;      // простой время не копит
            return idle;
        }
        double seconds = secondsPerBatch(plot, recipe);
        if (Double.isInfinite(seconds)) {
            return false;
        }
        int cap = bufferCapacity(plot, recipe);
        state.progress += dt;
        boolean produced = false;
        while (state.progress + 1e-9 >= seconds && state.output + recipe.outputAmount() <= cap) {
            Map<Material, Integer> use = plan(plot, recipe);
            if (use == null || !plugin.power().useStation(plot, station.tariffKey())) {
                break;
            }
            for (var u : use.entrySet()) {
                int left = plot.stock.getOrDefault(u.getKey(), 0) - u.getValue();
                if (left <= 0) {
                    plot.stock.remove(u.getKey());
                } else {
                    plot.stock.put(u.getKey(), left);
                }
            }
            state.output += recipe.outputAmount();
            state.progress -= seconds;
            produced = true;
        }
        boolean exhausted = plan(plot, recipe) == null || state.output + recipe.outputAmount() > cap;
        // время не копится впрок: без сырья или места станок простаивает, после остановки - не больше одной партии
        state.progress = exhausted ? 0 : Math.min(state.progress, seconds);
        return produced || dt > 0;
    }
}
