package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Ledger;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.StorageEntry;
import ru.buildmaster.util.ChunkAccess;
import ru.buildmaster.util.ChunkJob;
import ru.buildmaster.util.Text;

/** Жизненный цикл участка: создание, покупка, аренда, продление, отсрочка, освобождение и сброс здания. */
public final class PlotService {

    /** Параметры освобождения участка. */
    public record Release(String actor, String reason, Journal.Action action, boolean arrest,
                          Plot.RestoreMode mode) { }

    private final BuildMasterPlugin plugin;
    private final Set<String> creating = new java.util.HashSet<>();
    private int tickCounter;

    public PlotService(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    public long dayMillis() {
        return Math.max(1, plugin.getConfig().getLong("rent-day-seconds", 86400)) * 1000L;
    }

    public int maxRentDays() {
        return Math.max(1, plugin.getConfig().getInt("max-rent-days", 14));
    }

    public boolean isLocked(Plot plot) {
        return plot.busy || plot.restorePending;
    }

    // ---------------------------------------------------------------- создание

    public boolean isCreating(String id) {
        return creating.contains(id.toLowerCase());
    }

    /** Создаёт административный участок: снимает исходный вид здания и ставит голограмму в позиции админа. */
    public void create(Player admin, String id, Category category, Box box, double price, double rent) {
        String key = id.toLowerCase();
        World world = admin.getWorld();
        File dir = plugin.plots().snapshotDir(id);
        creating.add(key);
        Text.send(admin, "<yellow>Сохраняю исходный вид здания (" + box.describe() + ")...");
        Location holo = admin.getEyeLocation();
        ChunkJob.run(plugin, world, box, (chunk, clip) -> {
            try {
                plugin.snapshots().capture(dir, world, chunk.getX(), chunk.getZ(), clip);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, () -> {
            try {
                plugin.snapshots().saveMeta(dir, box, SnapshotStore.originalEntities(world, box));
            } catch (IOException e) {
                creating.remove(key);
                plugin.getLogger().log(Level.SEVERE, "Не удалось записать снимок " + id, e);
                Text.err(admin, "Не удалось сохранить снимок здания: " + e.getMessage());
                return;
            }
            Plot plot = new Plot(id, world.getName(), box);
            plot.category = category;
            plot.createdBy = admin.getUniqueId();
            plot.price = price;
            plot.rentPerDay = rent;
            plot.hasHolo = true;
            plot.holoX = holo.getX();
            plot.holoY = holo.getY();
            plot.holoZ = holo.getZ();
            plugin.plots().add(plot);
            plugin.plots().save();
            plugin.stations().rescan(plot);
            plugin.holograms().update(plot);
            plugin.journal().add(id, null, admin.getName(), Journal.Action.CREATED,
                    category.title() + ", " + box.describe());
            creating.remove(key);
            Text.ok(admin, "Участок <white>" + id + "</white> (" + category.title() + ") создан. Цена: "
                    + priceText(price) + ", аренда: " + priceText(rent) + "/сутки. Голограмма поставлена в вашей позиции.");
        }, error -> {
            creating.remove(key);
            plugin.getLogger().log(Level.SEVERE, "Ошибка создания снимка " + id, error);
            Text.err(admin, "Ошибка при создании снимка: " + error.getMessage());
        });
    }

    /** Заново снимает исходный вид свободного участка (после правок здания админом). */
    public void resnapshot(Player admin, Plot plot) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            Text.err(admin, "Мир участка не загружен.");
            return;
        }
        File dir = plugin.plots().snapshotDir(plot.id);
        plot.busy = true;
        ChunkJob.run(plugin, world, plot.box, (chunk, clip) -> {
            try {
                plugin.snapshots().capture(dir, world, chunk.getX(), chunk.getZ(), clip);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, () -> {
            try {
                plugin.snapshots().saveMeta(dir, plot.box, SnapshotStore.originalEntities(world, plot.box));
                Text.ok(admin, "Исходный вид участка " + plot.id + " обновлён.");
            } catch (IOException e) {
                Text.err(admin, "Не удалось сохранить снимок: " + e.getMessage());
            }
            plot.busy = false;
        }, error -> {
            plot.busy = false;
            plugin.getLogger().log(Level.SEVERE, "Ошибка снимка " + plot.id, error);
            Text.err(admin, "Ошибка при создании снимка: " + error.getMessage());
        });
    }

    // ---------------------------------------------------------------- покупка и аренда

    private boolean checkAvailable(Player p, Plot plot) {
        if (!plugin.economy().available()) {
            Text.err(p, "Экономика недоступна: не найден плагин с поддержкой Vault.");
            return false;
        }
        if (isLocked(plot)) {
            Text.err(p, "Участок сейчас обрабатывается, попробуйте через несколько секунд.");
            return false;
        }
        if (!plot.isFree()) {
            Text.err(p, "Участок уже занят.");
            return false;
        }
        if (!p.hasPermission("buildmaster.bypass")) {
            List<Plot> mine = plugin.plots().ownedBy(p.getUniqueId());
            Map<Category, Integer> byCategory = new EnumMap<>(Category.class);
            for (Plot m : mine) {
                byCategory.merge(m.category, 1, Integer::sum);
            }
            OwnershipLimits.Result r = OwnershipLimits.check(mine.size(), byCategory, plot.category,
                    plugin.getConfig().getInt("limits.total", 6),
                    plugin.getConfig().getInt("limits.categories." + plot.category.key(), 2));
            if (!r.allowed()) {
                Text.err(p, r.reason());
                return false;
            }
        }
        return true;
    }

    /** Платёж в казну. Деньги списываются у игрока и учитываются на счёте казны. */
    private boolean pay(OfflinePlayer player, double amount, String reason) {
        if (!plugin.economy().withdraw(player, amount)) {
            return false;
        }
        plugin.treasury().deposit(amount, reason);
        plugin.saveTreasury();
        return true;
    }

    public boolean buy(Player p, Plot plot) {
        if (!checkAvailable(p, plot)) {
            return false;
        }
        if (plot.price < 0) {
            Text.err(p, "Этот участок не продаётся, только аренда.");
            return false;
        }
        if (!pay(p, plot.price, "Покупка участка " + plot.id + " (" + p.getName() + ")")) {
            Text.err(p, "Недостаточно средств. Нужно: " + plugin.economy().format(plot.price));
            return false;
        }
        occupy(plot, p, Plot.Status.OWNED, 0);
        plugin.journal().add(plot.id, p.getUniqueId(), p.getName(), Journal.Action.BOUGHT,
                plugin.economy().format(plot.price));
        Text.ok(p, "Вы купили участок <white>" + plot.id + "</white> за " + plugin.economy().format(plot.price)
                + ". Вы - управляющий: главным владельцем остаётся администрация.");
        return true;
    }

    public boolean rent(Player p, Plot plot, int days) {
        if (!checkAvailable(p, plot)) {
            return false;
        }
        if (plot.rentPerDay < 0) {
            Text.err(p, "Этот участок не сдаётся в аренду.");
            return false;
        }
        int max = maxRentDays();
        if (!RentRules.validNewRent(days, max)) {
            Text.err(p, "Срок аренды: от 1 до " + max + " суток.");
            return false;
        }
        double cost = RentRules.cost(plot.rentPerDay, days);
        if (!pay(p, cost, "Аренда участка " + plot.id + " на " + days + " сут. (" + p.getName() + ")")) {
            Text.err(p, "Недостаточно средств. Нужно: " + plugin.economy().format(cost));
            return false;
        }
        occupy(plot, p, Plot.Status.RENTED, System.currentTimeMillis() + days * dayMillis());
        plugin.journal().add(plot.id, p.getUniqueId(), p.getName(), Journal.Action.RENTED,
                days + " сут., " + plugin.economy().format(cost));
        Text.ok(p, "Вы арендовали участок <white>" + plot.id + "</white> на " + days + " сут. за "
                + plugin.economy().format(cost) + ".");
        return true;
    }

    private void occupy(Plot plot, Player p, Plot.Status status, long until) {
        plot.status = status;
        plot.owner = p.getUniqueId();
        plot.ownerName = p.getName();
        plot.rentedUntil = until;
        plot.autoRenew = false;
        plot.warned = false;
        plot.graceUntil = 0;
        plot.clearRights();
        plot.powerWarnLevel = 0;
        plugin.plots().save();
        plugin.power().recalc(plot);
    }

    public boolean extend(Player p, Plot plot, int days) {
        if (plot.status != Plot.Status.RENTED || !plot.isOwner(p.getUniqueId())) {
            Text.err(p, "Продлевать можно только свою арендованную территорию.");
            return false;
        }
        if (isLocked(plot)) {
            Text.err(p, "Участок сейчас обрабатывается.");
            return false;
        }
        if (plot.rentPerDay < 0) {
            Text.err(p, "Аренда этого участка отключена.");
            return false;
        }
        long now = System.currentTimeMillis();
        int allowed = RentRules.maxExtendDays(now, plot.rentedUntil, dayMillis(), maxRentDays());
        if (days < 1 || days > allowed) {
            Text.err(p, allowed == 0
                    ? "Срок аренды уже максимальный (" + maxRentDays() + " сут.)."
                    : "Сейчас можно продлить не более чем на " + allowed + " сут. (максимум " + maxRentDays() + ").");
            return false;
        }
        double cost = RentRules.cost(plot.rentPerDay, days);
        if (!pay(p, cost, "Продление аренды " + plot.id + " на " + days + " сут. (" + p.getName() + ")")) {
            Text.err(p, "Недостаточно средств. Нужно: " + plugin.economy().format(cost));
            return false;
        }
        plot.rentedUntil = Math.max(plot.rentedUntil, now) + days * dayMillis();
        plot.warned = false;
        plot.graceUntil = 0;
        plugin.plots().save();
        plugin.holograms().update(plot);
        plugin.journal().add(plot.id, p.getUniqueId(), p.getName(), Journal.Action.EXTENDED,
                days + " сут., " + plugin.economy().format(cost));
        Text.ok(p, "Аренда продлена на " + days + " сут. Осталось: "
                + Text.duration(plot.rentedUntil - now) + ".");
        return true;
    }

    public void setAutoRenew(Plot plot, boolean value) {
        plot.autoRenew = value;
        plugin.plots().save();
    }

    public void setResetOnRelease(Plot plot, boolean value) {
        plot.resetOnRelease = value;
        plugin.plots().save();
    }

    /** Публичный доступ для всех; возвращает текст ошибки или null. Правила типа участка задаёт администратор. */
    public String setPublic(Plot plot, Plot.Perm perm, boolean value) {
        if (perm == Plot.Perm.BUILD) {
            return "Строительство нельзя открыть для всех.";
        }
        if (value) {
            List<String> allowed = plugin.getConfig().getStringList("public-permissions." + plot.category.key());
            if (allowed.stream().noneMatch(a -> a.equalsIgnoreCase(perm.name()))) {
                return "Для типа «" + plot.category.title() + "» публичный доступ «" + perm.title()
                        + "» запрещён администратором.";
            }
            plot.publicPerms.add(perm);
        } else {
            plot.publicPerms.remove(perm);
        }
        plugin.plots().save();
        return null;
    }

    private Plot.RestoreMode ownerMode(Plot plot) {
        return plot.resetOnRelease ? Plot.RestoreMode.FULL : Plot.RestoreMode.ITEMS_ONLY;
    }

    /** Добровольный отказ управляющего от участка. */
    public void release(Player p, Plot plot) {
        if (isLocked(plot)) {
            Text.err(p, "Участок сейчас обрабатывается.");
            return;
        }
        if (plot.status == Plot.Status.OWNED) {
            int percent = plugin.getConfig().getInt("sell-refund-percent", 0);
            if (percent > 0 && plot.price > 0) {
                double refund = plot.price * percent / 100.0;
                if (plugin.treasury().withdraw(refund, "система", "Возврат при отказе от участка " + plot.id)
                        && plugin.economy().deposit(p, refund)) {
                    plugin.saveTreasury();
                    Text.ok(p, "Возвращено " + plugin.economy().format(refund) + " (" + percent + "% цены).");
                }
            }
        }
        evict(plot, new Release(p.getName(), "Отказ управляющего", Journal.Action.RELEASED, false, ownerMode(plot)));
    }

    // ---------------------------------------------------------------- освобождение и сброс

    /** Освобождает участок: вещи уходят в хранилища их владельцев, здание сбрасывается по режиму. */
    public void evict(Plot plot, Release release) {
        UUID previous = plot.owner;
        plugin.journal().add(plot.id, previous, release.actor(), release.action(), release.reason());
        plot.status = Plot.Status.FREE;
        plot.owner = null;
        plot.ownerName = null;
        plot.rentedUntil = 0;
        plot.autoRenew = false;
        plot.warned = false;
        plot.graceUntil = 0;
        plugin.power().closeAccount(plot, previous, release.action() == Journal.Action.SEIZED);
        plot.clearRights();
        plot.powerWarnLevel = 0;
        startRestore(plot, previous, release);
        notifyOwner(previous, "<yellow>Участок <white>" + Text.esc(plot.id) + "</white>: "
                + release.action().title().toLowerCase() + ". Причина: " + Text.esc(release.reason())
                + ". Ваши вещи отправлены в хранилище (/district storage)"
                + (release.arrest() ? ", но арестованы до решения администрации." : "."));
        plugin.holograms().update(plot);
    }

    /** Сброс здания по решению администратора; владелец участка остаётся прежним. */
    public void reset(Plot plot, String actor, String reason) {
        plugin.journal().add(plot.id, plot.owner, actor, Journal.Action.RESET, reason);
        startRestore(plot, plot.owner, new Release(actor, reason, Journal.Action.RESET, false, Plot.RestoreMode.FULL));
        notifyOwner(plot.owner, "<yellow>Администрация сбросила здание участка <white>" + Text.esc(plot.id)
                + "</white>. Причина: " + Text.esc(reason) + ". Ваши вещи отправлены в хранилище (/district storage).");
    }

    private void startRestore(Plot plot, UUID tenant, Release release) {
        plot.restorePending = true;
        plot.restoreFor = tenant;
        plot.restoreMode = release.mode();
        plot.restoreArrest = release.arrest();
        plot.restoreReason = release.reason();
        plot.restoreBatch = UUID.randomUUID().toString();
        plot.failures = 0;
        plugin.plots().save();
        runRestore(plot);
    }

    /** Предупреждение игроку: запись в журнал предупреждений и сообщение в чат, если он в сети. */
    public void warn(UUID uuid, String plotId, String miniMessage) {
        if (uuid == null) {
            return;
        }
        plugin.warnings().add(uuid, plotId, miniMessage);
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            Text.send(online, miniMessage);
        }
    }

    private void notifyOwner(UUID uuid, String message) {
        Player online = uuid == null ? null : Bukkit.getPlayer(uuid);
        if (online != null) {
            Text.send(online, message);
        }
    }

    public void runRestore(Plot plot) {
        if (plot.busy) {
            return;
        }
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            plugin.getLogger().warning("Мир " + plot.world + " не загружен, сброс участка " + plot.id + " отложен.");
            plot.failures++;
            return;
        }
        File dir = plugin.plots().snapshotDir(plot.id);
        if (plot.restoreMode == Plot.RestoreMode.FULL && !plugin.snapshots().exists(dir)) {
            plugin.getLogger().severe("Нет снимка для участка " + plot.id + ": здание не восстановлено");
            plot.restoreMode = Plot.RestoreMode.ITEMS_ONLY;
        }
        plot.busy = true;
        Ledger ledger = plugin.ledgers().get(plot.id);
        Set<UUID> originals = plugin.snapshots().loadOriginalEntities(dir);
        Map<UUID, Integer> returned = new HashMap<>();
        ChunkJob.run(plugin, world, plot.box, (chunk, clip) -> {
            Map<UUID, List<ItemStack>> out = new HashMap<>();
            try {
                restoreChunk(plot, world, chunk, clip, dir, ledger, originals, out);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            for (Map.Entry<UUID, List<ItemStack>> e : out.entrySet()) {
                boolean arrest = plot.restoreArrest && e.getKey().equals(plot.restoreFor);
                plugin.storage().add(e.getKey(), plot.id, e.getValue(),
                        arrest ? StorageEntry.State.ARRESTED : StorageEntry.State.AVAILABLE,
                        plot.restoreReason, plot.restoreBatch);
                for (ItemStack s : e.getValue()) {
                    returned.merge(e.getKey(), s.getAmount(), Integer::sum);
                }
            }
            plugin.ledgers().markDirty(plot.id);
        }, () -> {
            plot.busy = false;
            plot.restorePending = false;
            plot.restoreFor = null;
            plugin.plots().save();
            plugin.ledgers().flush();
            plugin.holograms().update(plot);
            plugin.stations().rescan(plot);
            for (Map.Entry<UUID, Integer> e : returned.entrySet()) {
                Player online = Bukkit.getPlayer(e.getKey());
                if (online != null) {
                    Text.send(online, "<green>Участок <white>" + Text.esc(plot.id) + "</white>: в ваше хранилище "
                            + "отправлено предметов: " + e.getValue() + ". Откройте /district storage");
                }
            }
        }, error -> {
            plot.busy = false;
            plot.failures++;
            plugin.getLogger().log(Level.SEVERE, "Ошибка сброса участка " + plot.id, error);
        });
    }

    private void restoreChunk(Plot plot, World world, Chunk chunk, Box clip, File dir, Ledger ledger,
                              Set<UUID> originals, Map<UUID, List<ItemStack>> out) throws IOException {
        UUID tenant = plot.restoreFor;

        // 1. содержимое контейнеров: хозяин - тот, кто поставил контейнер; для исходных - арендатор
        for (BlockState state : ChunkAccess.tileEntities(chunk)) {
            if (!clip.contains(state.getX(), state.getY(), state.getZ())) {
                continue;
            }
            Ledger.Entry placed = ledger.get(state.getX(), state.getY(), state.getZ());
            collectContainer(state, placed != null ? placed.player() : tenant, out);
        }

        // 2. сущности: выброшенные предметы, рамки и стойки, добавленные игроками
        for (Entity e : ChunkAccess.entities(chunk)) {
            if (!clip.contains(e.getLocation().getBlockX(), e.getLocation().getBlockY(), e.getLocation().getBlockZ())) {
                continue;
            }
            if (e instanceof Item dropped) {
                give(out, tenant, dropped.getItemStack().clone());
                dropped.remove();
            } else if ((e instanceof Hanging || e instanceof ArmorStand) && !originals.contains(e.getUniqueId())) {
                collectEntity(e, tenant, out);
                e.remove();
            }
        }

        // 3. поставленные игроками блоки возвращаются поставившим (только при полном сбросе)
        var iterator = ledger.entries().entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long key = entry.getKey();
            int x = Ledger.unpackX(key);
            int y = Ledger.unpackY(key);
            int z = Ledger.unpackZ(key);
            if (!clip.contains(x, y, z)) {
                continue;
            }
            if (plot.restoreMode == Plot.RestoreMode.FULL && !world.getBlockAt(x, y, z).getType().isAir()) {
                Material material = Material.matchMaterial(entry.getValue().material());
                if (material != null && material.isItem()) {
                    giveStacks(out, entry.getValue().player(), material, entry.getValue().amount());
                }
            }
            iterator.remove();
        }

        // 4. здание возвращается к исходному виду вместе с данными блоков (таблички, баннеры и т.д.)
        if (plot.restoreMode == Plot.RestoreMode.FULL) {
            plugin.snapshots().place(dir, world, chunk.getX(), chunk.getZ(), clip);
            // исходные контейнеры считаются пустыми: иначе игрок мог бы забрать содержимое дважды
            for (BlockState state : ChunkAccess.tileEntities(chunk)) {
                if (clip.contains(state.getX(), state.getY(), state.getZ())) {
                    clearContainer(state);
                }
            }
        }
    }

    private void collectContainer(BlockState state, UUID owner, Map<UUID, List<ItemStack>> out) {
        Inventory inv = null;
        if (state instanceof Chest chest) {
            inv = chest.getBlockInventory();
        } else if (state instanceof BlockInventoryHolder holder) {
            inv = holder.getInventory();
        }
        if (inv != null) {
            for (ItemStack s : inv.getContents()) {
                if (s != null && !s.getType().isAir()) {
                    give(out, owner, s.clone());
                }
            }
            inv.clear();
        }
        if (state instanceof Jukebox jukebox && jukebox.hasRecord()) {
            give(out, owner, jukebox.getRecord().clone());
            jukebox.setRecord(null);
        }
    }

    private void clearContainer(BlockState state) {
        if (state instanceof Chest chest) {
            chest.getBlockInventory().clear();
        } else if (state instanceof BlockInventoryHolder holder) {
            holder.getInventory().clear();
        }
    }

    private void collectEntity(Entity e, UUID owner, Map<UUID, List<ItemStack>> out) {
        if (e instanceof ItemFrame frame) {
            give(out, owner, new ItemStack(frame instanceof GlowItemFrame ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME));
            if (!frame.getItem().getType().isAir()) {
                give(out, owner, frame.getItem().clone());
            }
        } else if (e instanceof Painting) {
            give(out, owner, new ItemStack(Material.PAINTING));
        } else if (e instanceof ArmorStand stand) {
            give(out, owner, new ItemStack(Material.ARMOR_STAND));
            EntityEquipment eq = stand.getEquipment();
            if (eq != null) {
                for (ItemStack s : eq.getArmorContents()) {
                    if (s != null && !s.getType().isAir()) {
                        give(out, owner, s.clone());
                    }
                }
                for (ItemStack s : new ItemStack[] {eq.getItemInMainHand(), eq.getItemInOffHand()}) {
                    if (s != null && !s.getType().isAir()) {
                        give(out, owner, s.clone());
                    }
                }
            }
        }
    }

    private void give(Map<UUID, List<ItemStack>> out, UUID owner, ItemStack stack) {
        if (owner == null) {
            plugin.getLogger().warning("Предмет без владельца пропущен: " + stack.getType());
            return;
        }
        out.computeIfAbsent(owner, k -> new ArrayList<>()).add(stack);
    }

    private void giveStacks(Map<UUID, List<ItemStack>> out, UUID owner, Material material, int amount) {
        int left = amount;
        int stack = Math.max(1, material.getMaxStackSize());
        while (left > 0) {
            int n = Math.min(stack, left);
            give(out, owner, new ItemStack(material, n));
            left -= n;
        }
    }

    // ---------------------------------------------------------------- телепорт

    public void teleport(Player player, Plot plot) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            Text.err(player, "Мир участка не загружен.");
            return;
        }
        Location target;
        if (plot.hasSpawn) {
            target = new Location(world, plot.spawnX, plot.spawnY, plot.spawnZ, plot.spawnYaw, plot.spawnPitch);
        } else {
            int cx = (int) Math.floor(plot.box.centerX());
            int cz = (int) Math.floor(plot.box.centerZ());
            int y = world.getHighestBlockYAt(cx, cz) + 1;
            for (int yy = plot.box.minY(); yy < plot.box.maxY(); yy++) {
                Block floor = world.getBlockAt(cx, yy - 1, cz);
                if (floor.getType().isSolid() && world.getBlockAt(cx, yy, cz).isPassable()
                        && world.getBlockAt(cx, yy + 1, cz).isPassable()) {
                    y = yy;
                    break;
                }
            }
            target = new Location(world, cx + 0.5, y, cz + 0.5);
        }
        player.teleportAsync(target);
        Text.send(player, "<light_purple>Телепорт к участку <white>" + plot.id + "</white>.");
    }

    // ---------------------------------------------------------------- периодические задачи

    public void tick() {
        long now = System.currentTimeMillis();
        for (Plot plot : new ArrayList<>(plugin.plots().all())) {
            if (plot.busy) {
                continue;
            }
            if (plot.restorePending) {
                if (plot.failures < 3) {
                    runRestore(plot);
                }
                continue;
            }
            if (plot.status != Plot.Status.RENTED) {
                continue;
            }
            if (plot.graceUntil > 0) {
                graceTick(plot, now);
            } else if (now >= plot.rentedUntil) {
                expire(plot, now);
            } else if (!plot.warned && plot.rentedUntil - now < dayMillis()) {
                Player owner = plot.owner == null ? null : Bukkit.getPlayer(plot.owner);
                if (plot.owner != null) {
                    plot.warned = true;
                    warn(plot.owner, plot.id, "<yellow>Аренда участка <white>" + Text.esc(plot.id)
                            + "</white> заканчивается через " + Text.duration(plot.rentedUntil - now)
                            + (plot.autoRenew ? " (будет продлена автоматически)." : ". Продлите её: /district extend."));
                }
            }
        }
        plugin.power().tick();
        plugin.holograms().updateAll();
        plugin.ledgers().flush();
        if (tickCounter % 4320 == 4319) { // примерно раз в сутки
            plugin.stations().rescanAll();
        }
        if (++tickCounter % 180 == 0) { // примерно раз в час
            int removed = plugin.storage().sweepExpired(now, plugin.getConfig().getInt("storage-days", 60));
            if (removed > 0) {
                plugin.getLogger().info("Из хранилищ удалено просроченных записей: " + removed);
            }
        }
    }

    private void expire(Plot plot, long now) {
        OfflinePlayer owner = plot.owner == null ? null : Bukkit.getOfflinePlayer(plot.owner);
        if (plot.autoRenew && owner != null && plot.rentPerDay >= 0) {
            if (tryAutoRenew(plot, owner, now)) {
                return;
            }
            long grace = Math.max(0, plugin.getConfig().getInt("grace-days", 3));
            if (grace > 0) {
                plot.graceUntil = now + grace * dayMillis();
                plugin.plots().save();
                plugin.holograms().update(plot);
                plugin.journal().add(plot.id, plot.owner, "система", Journal.Action.GRACE_STARTED,
                        "Не хватило средств для автопродления, отсрочка " + grace + " сут.");
                warn(plot.owner, plot.id, "<red>Не хватило средств для продления аренды участка <white>"
                        + Text.esc(plot.id) + "</white>. Участок заморожен на " + grace
                        + " сут.: строить нельзя, торговля работает. Пополните счёт.");
                return;
            }
        }
        evict(plot, new Release("система", "Срок аренды истёк", Journal.Action.EXPIRED, false, ownerMode(plot)));
    }

    private void graceTick(Plot plot, long now) {
        OfflinePlayer owner = Bukkit.getOfflinePlayer(plot.owner);
        if (plot.rentPerDay >= 0 && tryAutoRenew(plot, owner, now)) {
            return;
        }
        if (now >= plot.graceUntil) {
            evict(plot, new Release("система", "Аренда не оплачена в течение отсрочки", Journal.Action.EXPIRED,
                    false, ownerMode(plot)));
        }
    }

    /** Пытается продлить аренду на период автопродления. Успех снимает отсрочку. */
    private boolean tryAutoRenew(Plot plot, OfflinePlayer owner, long now) {
        int days = Math.max(1, plugin.getConfig().getInt("autorenew-days", 7));
        days = Math.min(days, maxRentDays());
        double cost = RentRules.cost(plot.rentPerDay, days);
        if (!pay(owner, cost, "Автопродление аренды " + plot.id + " (" + owner.getName() + ")")) {
            return false;
        }
        plot.rentedUntil = Math.max(plot.rentedUntil, now) + days * dayMillis();
        plot.graceUntil = 0;
        plot.warned = false;
        plugin.plots().save();
        plugin.holograms().update(plot);
        plugin.journal().add(plot.id, plot.owner, "система", Journal.Action.EXTENDED,
                "Автопродление на " + days + " сут., " + plugin.economy().format(cost));
        if (owner.getPlayer() != null) {
            Text.ok(owner.getPlayer(), "Аренда участка <white>" + Text.esc(plot.id) + "</white> автоматически продлена на "
                    + days + " сут. за " + plugin.economy().format(cost) + ".");
        }
        return true;
    }

    private static String priceText(double v) {
        return v < 0 ? "нет" : String.valueOf(v);
    }
}
