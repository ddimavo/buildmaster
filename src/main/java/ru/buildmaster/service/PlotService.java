package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Snapshot;
import ru.buildmaster.util.ChunkJob;
import ru.buildmaster.util.Text;

/** Жизненный цикл участка: создание, покупка, аренда, продление, отказ, сброс здания. */
public final class PlotService {

    private final BuildMasterPlugin plugin;
    private final Set<String> creating = new HashSet<>();
    private Set<Material> holderMaterials;

    public PlotService(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    public long dayMillis() {
        return Math.max(1, plugin.getConfig().getLong("rent-day-seconds", 86400)) * 1000L;
    }

    public boolean isLocked(Plot plot) {
        return plot.busy || plot.restorePending;
    }

    // ---------------------------------------------------------------- создание

    public boolean isCreating(String id) {
        return creating.contains(id.toLowerCase());
    }

    /** Создаёт участок и снимает исходный вид здания (по чанку за тик). */
    public void create(Player admin, String id, Box box, double price, double rent) {
        String key = id.toLowerCase();
        World world = admin.getWorld();
        creating.add(key);
        Text.send(admin, "<yellow>Сохраняю исходный вид здания (" + box.describe() + ")...");
        Snapshot.Builder builder = new Snapshot.Builder(box);
        ChunkJob.run(plugin, world, box, (chunk, clip) -> {
            for (int y = clip.minY(); y <= clip.maxY(); y++) {
                for (int z = clip.minZ(); z <= clip.maxZ(); z++) {
                    for (int x = clip.minX(); x <= clip.maxX(); x++) {
                        builder.set(x, y, z, world.getBlockData(x, y, z));
                    }
                }
            }
        }, () -> {
            Snapshot snapshot = builder.build();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    snapshot.write(plugin.plots().snapshotFile(id));
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "Не удалось записать снимок " + id, e);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        creating.remove(key);
                        Text.err(admin, "Не удалось сохранить снимок здания: " + e.getMessage());
                    });
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Plot plot = new Plot(id, world.getName(), box);
                    plot.price = price;
                    plot.rentPerDay = rent;
                    plugin.plots().add(plot);
                    plugin.plots().save();
                    creating.remove(key);
                    Text.ok(admin, "Участок <white>" + id + "</white> создан. Цена: "
                            + priceText(price) + ", аренда: " + priceText(rent) + "/сутки.");
                });
            });
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
        plot.busy = true;
        Snapshot.Builder builder = new Snapshot.Builder(plot.box);
        ChunkJob.run(plugin, world, plot.box, (chunk, clip) -> {
            for (int y = clip.minY(); y <= clip.maxY(); y++) {
                for (int z = clip.minZ(); z <= clip.maxZ(); z++) {
                    for (int x = clip.minX(); x <= clip.maxX(); x++) {
                        builder.set(x, y, z, world.getBlockData(x, y, z));
                    }
                }
            }
        }, () -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                builder.build().write(plugin.plots().snapshotFile(plot.id));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plot.busy = false;
                    Text.ok(admin, "Исходный вид участка " + plot.id + " обновлён.");
                });
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось записать снимок " + plot.id, e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plot.busy = false;
                    Text.err(admin, "Не удалось сохранить снимок: " + e.getMessage());
                });
            }
        }), error -> {
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
        int max = plugin.getConfig().getInt("max-plots-per-player", 3);
        if (max > 0 && plugin.plots().ownedBy(p.getUniqueId()).size() >= max
                && !p.hasPermission("buildmaster.bypass")) {
            Text.err(p, "Вы уже владеете максимальным числом участков (" + max + ").");
            return false;
        }
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
        if (!plugin.economy().withdraw(p, plot.price)) {
            Text.err(p, "Недостаточно средств. Нужно: " + plugin.economy().format(plot.price));
            return false;
        }
        occupy(plot, p, Plot.Status.OWNED, 0);
        Text.ok(p, "Вы купили участок <white>" + plot.id + "</white> за " + plugin.economy().format(plot.price) + ".");
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
        int max = plugin.getConfig().getInt("max-rent-days", 60);
        if (days < 1 || days > max) {
            Text.err(p, "Срок аренды: от 1 до " + max + " суток.");
            return false;
        }
        double cost = plot.rentPerDay * days;
        if (!plugin.economy().withdraw(p, cost)) {
            Text.err(p, "Недостаточно средств. Нужно: " + plugin.economy().format(cost));
            return false;
        }
        occupy(plot, p, Plot.Status.RENTED, System.currentTimeMillis() + days * dayMillis());
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
        plot.trusted.clear();
        plugin.plots().save();
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
        int max = plugin.getConfig().getInt("max-rent-days", 60);
        long remaining = Math.max(0, plot.rentedUntil - System.currentTimeMillis());
        if (days < 1 || remaining + days * dayMillis() > max * dayMillis()) {
            Text.err(p, "Нельзя оплатить больше " + max + " суток вперёд.");
            return false;
        }
        double cost = plot.rentPerDay * days;
        if (!plugin.economy().withdraw(p, cost)) {
            Text.err(p, "Недостаточно средств. Нужно: " + plugin.economy().format(cost));
            return false;
        }
        plot.rentedUntil += days * dayMillis();
        plot.warned = false;
        plugin.plots().save();
        Text.ok(p, "Аренда продлена на " + days + " сут. Осталось: " + Text.duration(plot.rentedUntil - System.currentTimeMillis()) + ".");
        return true;
    }

    public void setAutoRenew(Plot plot, boolean value) {
        plot.autoRenew = value;
        plugin.plots().save();
    }

    /** Добровольный отказ от участка: предметы возвращаются, здание сбрасывается. */
    public void release(Player p, Plot plot) {
        if (isLocked(plot)) {
            Text.err(p, "Участок сейчас обрабатывается.");
            return;
        }
        if (plot.status == Plot.Status.OWNED) {
            int percent = plugin.getConfig().getInt("sell-refund-percent", 0);
            if (percent > 0 && plot.price > 0) {
                double refund = plot.price * percent / 100.0;
                if (plugin.economy().deposit(p, refund)) {
                    Text.ok(p, "Возвращено " + plugin.economy().format(refund) + " (" + percent + "% цены).");
                }
            }
        }
        evict(plot, "Вы отказались от участка <white>" + plot.id + "</white>.");
    }

    // ---------------------------------------------------------------- освобождение и сброс

    /** Освобождает участок: возвращает предметы прежнему владельцу и приводит здание в исходный вид. */
    public void evict(Plot plot, String notice) {
        UUID previous = plot.owner;
        plot.status = Plot.Status.FREE;
        plot.owner = null;
        plot.ownerName = null;
        plot.rentedUntil = 0;
        plot.autoRenew = false;
        plot.warned = false;
        plot.trusted.clear();
        plot.restorePending = true;
        plot.restoreFor = previous;
        plot.failures = 0;
        plugin.plots().save();
        if (previous != null && notice != null) {
            Player online = Bukkit.getPlayer(previous);
            if (online != null) {
                Text.send(online, "<yellow>" + notice + " Здание восстанавливается, ваши предметы будут возвращены.");
            }
        }
        runRestore(plot);
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
        plot.busy = true;
        File file = plugin.plots().snapshotFile(plot.id);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Snapshot snapshot;
            try {
                snapshot = Snapshot.read(file);
            } catch (IOException e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getLogger().log(Level.SEVERE, "Нет снимка для участка " + plot.id + ": здание не восстановлено", e);
                    plot.busy = false;
                    plot.restorePending = false;
                    plugin.plots().save();
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> restore(plot, world, snapshot));
        });
    }

    private void restore(Plot plot, World world, Snapshot snapshot) {
        BlockData[] palette = new BlockData[snapshot.palette.length];
        for (int i = 0; i < palette.length; i++) {
            try {
                palette[i] = Bukkit.createBlockData(snapshot.palette[i]);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Неизвестный блок в снимке: " + snapshot.palette[i]);
                palette[i] = Material.AIR.createBlockData();
            }
        }
        UUID target = plot.restoreFor;
        AtomicInteger returned = new AtomicInteger();
        ChunkJob.run(plugin, world, plot.box, (chunk, clip) -> {
            List<ItemStack> items = new ArrayList<>();
            restoreBlocks(world, snapshot, palette, clip, items);
            collectEntities(chunk, clip, items);
            for (ItemStack s : items) {
                returned.addAndGet(s.getAmount());
            }
            deliver(target, items);
        }, () -> {
            plot.busy = false;
            plot.restorePending = false;
            plot.restoreFor = null;
            plugin.plots().save();
            if (target != null) {
                Player online = Bukkit.getPlayer(target);
                if (online != null) {
                    Text.ok(online, "Участок <white>" + plot.id + "</white> освобождён. Возвращено предметов: "
                            + returned.get() + ".");
                    int left = plugin.pending().count(target);
                    if (left > 0) {
                        Text.send(online, "<yellow>Не всё поместилось в инвентарь (" + left
                                + " шт.). Освободите место и введите <white>/district claim</white>.");
                    }
                }
            }
        }, error -> {
            plot.busy = false;
            plot.failures++;
            plugin.getLogger().log(Level.SEVERE, "Ошибка сброса участка " + plot.id, error);
        });
    }

    private void restoreBlocks(World world, Snapshot snapshot, BlockData[] palette, Box clip, List<ItemStack> items) {
        Map<Material, Integer> placed = new EnumMap<>(Material.class);
        for (int y = clip.minY(); y <= clip.maxY(); y++) {
            for (int z = clip.minZ(); z <= clip.maxZ(); z++) {
                for (int x = clip.minX(); x <= clip.maxX(); x++) {
                    Block block = world.getBlockAt(x, y, z);
                    BlockData current = block.getBlockData();
                    Material type = current.getMaterial();
                    if (!type.isAir() && holders().contains(type)) {
                        collectContainer(block, items);
                    }
                    BlockData original = palette[snapshot.paletteIndex(x, y, z)];
                    if (current.equals(original)) {
                        continue;
                    }
                    if (original.getMaterial().isAir() && !type.isAir()) {
                        countPlaced(current, placed);
                    }
                    block.setBlockData(original, false);
                }
            }
        }
        for (Map.Entry<Material, Integer> e : placed.entrySet()) {
            int left = e.getValue();
            int stack = Math.max(1, e.getKey().getMaxStackSize());
            while (left > 0) {
                int n = Math.min(stack, left);
                items.add(new ItemStack(e.getKey(), n));
                left -= n;
            }
        }
    }

    /** Блок, которого не было в исходном здании, возвращается игроку предметом. */
    private void countPlaced(BlockData data, Map<Material, Integer> placed) {
        if (data instanceof Bisected bisected && bisected.getHalf() == Bisected.Half.TOP
                && !(data instanceof Stairs) && !(data instanceof TrapDoor)) {
            return; // верхняя половина двери/высокого цветка
        }
        if (data instanceof Bed bed && bed.getPart() == Bed.Part.HEAD) {
            return;
        }
        Material item = data.getPlacementMaterial();
        if (item.isAir() || !item.isItem()) {
            return;
        }
        int amount = data instanceof Slab slab && slab.getType() == Slab.Type.DOUBLE ? 2 : 1;
        placed.merge(item, amount, Integer::sum);
    }

    private void collectContainer(Block block, List<ItemStack> items) {
        BlockState state = block.getState(false);
        if (state instanceof BlockInventoryHolder holder) {
            Inventory inv = holder.getInventory();
            for (ItemStack s : inv.getContents()) {
                if (s != null && !s.getType().isAir()) {
                    items.add(s.clone());
                }
            }
            inv.clear();
        }
        if (state instanceof Jukebox jukebox && jukebox.hasRecord()) {
            items.add(jukebox.getRecord().clone());
            jukebox.setRecord(null);
        }
    }

    private void collectEntities(org.bukkit.Chunk chunk, Box clip, List<ItemStack> items) {
        for (Entity e : chunk.getEntities()) {
            if (!clip.contains(e.getLocation().getBlockX(), e.getLocation().getBlockY(), e.getLocation().getBlockZ())) {
                continue;
            }
            if (e instanceof Item dropped) {
                items.add(dropped.getItemStack().clone());
                dropped.remove();
            } else if (e instanceof ItemFrame frame && !frame.getItem().getType().isAir()) {
                items.add(frame.getItem().clone());
                frame.setItem(null);
            }
        }
    }

    /** Материалы, блоки которых содержат инвентарь (сундуки, бочки, печи, шалкеры...). */
    private Set<Material> holders() {
        if (holderMaterials == null) {
            Set<Material> set = new HashSet<>();
            for (Material m : Material.values()) {
                if (m.isLegacy() || !m.isBlock()) {
                    continue;
                }
                try {
                    BlockState state = m.createBlockData().createBlockState();
                    if (state instanceof BlockInventoryHolder || state instanceof Jukebox) {
                        set.add(m);
                    }
                } catch (Exception ignored) {
                    // материал без состояния блока
                }
            }
            holderMaterials = set;
        }
        return holderMaterials;
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

    // ---------------------------------------------------------------- выдача предметов

    /** Выдаёт предметы онлайн-игроку, остаток (и всё для оффлайн-игрока) откладывает в хранилище. */
    public void deliver(UUID uuid, List<ItemStack> items) {
        if (items.isEmpty() || uuid == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        List<ItemStack> rest = items;
        if (player != null) {
            rest = new ArrayList<>(player.getInventory().addItem(items.toArray(new ItemStack[0])).values());
        }
        plugin.pending().add(uuid, rest);
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
            if (now >= plot.rentedUntil) {
                expire(plot, now);
            } else if (!plot.warned && plot.rentedUntil - now < dayMillis()) {
                Player owner = plot.owner == null ? null : Bukkit.getPlayer(plot.owner);
                if (owner != null) {
                    plot.warned = true;
                    Text.send(owner, "<yellow>Аренда участка <white>" + plot.id + "</white> заканчивается через "
                            + Text.duration(plot.rentedUntil - now)
                            + (plot.autoRenew ? " (будет продлена автоматически)." : ". Продлите её: /district extend."));
                }
            }
        }
    }

    private void expire(Plot plot, long now) {
        OfflinePlayer owner = plot.owner == null ? null : Bukkit.getOfflinePlayer(plot.owner);
        int days = Math.max(1, plugin.getConfig().getInt("autorenew-days", 7));
        if (plot.autoRenew && owner != null && plot.rentPerDay >= 0) {
            double cost = plot.rentPerDay * days;
            if (plugin.economy().withdraw(owner, cost)) {
                plot.rentedUntil = Math.max(plot.rentedUntil, now) + days * dayMillis();
                plot.warned = false;
                plugin.plots().save();
                Player online = owner.getPlayer();
                if (online != null) {
                    Text.ok(online, "Аренда участка <white>" + plot.id + "</white> автоматически продлена на "
                            + days + " сут. за " + plugin.economy().format(cost) + ".");
                }
                return;
            }
            evict(plot, "Аренда участка <white>" + plot.id + "</white> прекращена: не хватило средств для автопродления.");
            return;
        }
        evict(plot, "Срок аренды участка <white>" + plot.id + "</white> истёк.");
    }

    private static String priceText(double v) {
        return v < 0 ? "нет" : String.valueOf(v);
    }
}
