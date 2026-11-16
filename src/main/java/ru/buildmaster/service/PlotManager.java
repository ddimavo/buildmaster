package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Plot;

/** Хранилище участков: загрузка/сохранение и быстрый поиск по координатам. */
public final class PlotManager {

    private final BuildMasterPlugin plugin;
    private final File file;
    private final Map<String, Plot> plots = new java.util.LinkedHashMap<>();
    /** мир -> ключ чанка -> участки, задевающие этот чанк */
    private final Map<String, Map<Long, List<Plot>>> index = new HashMap<>();

    public PlotManager(BuildMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "plots.yml");
    }

    /** Папка со снимком здания (структуры по чанкам). */
    public File snapshotDir(String id) {
        return new File(new File(plugin.getDataFolder(), "snapshots"), id.toLowerCase(Locale.ROOT));
    }

    public Collection<Plot> all() {
        return plots.values();
    }

    public Plot get(String id) {
        return id == null ? null : plots.get(id.toLowerCase(Locale.ROOT));
    }

    public void add(Plot plot) {
        plots.put(plot.id.toLowerCase(Locale.ROOT), plot);
        reindex();
    }

    public void remove(Plot plot) {
        plots.remove(plot.id.toLowerCase(Locale.ROOT));
        reindex();
    }

    public Plot at(Location loc) {
        return loc.getWorld() == null ? null : at(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public Plot at(String world, int x, int y, int z) {
        Map<Long, List<Plot>> byChunk = index.get(world);
        if (byChunk == null) {
            return null;
        }
        List<Plot> list = byChunk.get(chunkKey(x >> 4, z >> 4));
        if (list == null) {
            return null;
        }
        for (Plot p : list) {
            if (p.box.contains(x, y, z)) {
                return p;
            }
        }
        return null;
    }

    public Plot overlapping(String world, Box box) {
        for (Plot p : plots.values()) {
            if (p.world.equals(world) && p.box.intersects(box)) {
                return p;
            }
        }
        return null;
    }

    public List<Plot> ownedBy(UUID uuid) {
        List<Plot> result = new ArrayList<>();
        for (Plot p : plots.values()) {
            if (p.isOwner(uuid)) {
                result.add(p);
            }
        }
        return result;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx & 0xffffffffL) | ((long) cz << 32);
    }

    private void reindex() {
        index.clear();
        for (Plot p : plots.values()) {
            Map<Long, List<Plot>> byChunk = index.computeIfAbsent(p.world, w -> new HashMap<>());
            for (int cx = p.box.minX() >> 4; cx <= p.box.maxX() >> 4; cx++) {
                for (int cz = p.box.minZ() >> 4; cz <= p.box.maxZ() >> 4; cz++) {
                    byChunk.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(p);
                }
            }
        }
    }

    public void load() {
        plots.clear();
        if (file.exists()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = yml.getConfigurationSection("plots");
            if (root != null) {
                for (String id : root.getKeys(false)) {
                    try {
                        Plot p = readPlot(id, root.getConfigurationSection(id));
                        plots.put(id.toLowerCase(Locale.ROOT), p);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "Не удалось загрузить участок " + id, e);
                    }
                }
            }
        }
        reindex();
    }

    private Plot readPlot(String id, ConfigurationSection s) {
        Plot p = new Plot(id, s.getString("world"), Box.parse(s.getString("box")));
        p.category = ru.buildmaster.model.Category.parse(s.getString("category", "shop"));
        if (p.category == null) {
            p.category = ru.buildmaster.model.Category.SHOP;
        }
        String creator = s.getString("created-by");
        if (creator != null) {
            p.createdBy = UUID.fromString(creator);
        }
        p.name = s.getString("name");
        p.description = s.getString("description");
        if (s.contains("holo")) {
            p.hasHolo = true;
            p.holoX = s.getDouble("holo.x");
            p.holoY = s.getDouble("holo.y");
            p.holoZ = s.getDouble("holo.z");
        }
        p.resetOnRelease = s.getBoolean("reset-on-release", true);
        p.graceUntil = s.getLong("grace-until");
        p.price = s.getDouble("price", -1);
        p.rentPerDay = s.getDouble("rent-per-day", -1);
        p.status = Plot.Status.valueOf(s.getString("status", "FREE"));
        String owner = s.getString("owner");
        if (owner != null) {
            p.owner = UUID.fromString(owner);
        }
        p.ownerName = s.getString("owner-name");
        p.rentedUntil = s.getLong("rented-until");
        p.autoRenew = s.getBoolean("auto-renew");
        // старый формат: список доверенных = все права
        for (String t : s.getStringList("trusted")) {
            p.grantAll(UUID.fromString(t));
        }
        ConfigurationSection grants = s.getConfigurationSection("grants");
        if (grants != null) {
            for (String uid : grants.getKeys(false)) {
                for (String name : grants.getStringList(uid)) {
                    Plot.Perm perm = Plot.Perm.parse(name);
                    if (perm != null) {
                        p.grant(UUID.fromString(uid), perm);
                    }
                }
            }
        }
        for (String name : s.getStringList("public-perms")) {
            Plot.Perm perm = Plot.Perm.parse(name);
            if (perm != null && perm != Plot.Perm.BUILD) {
                p.publicPerms.add(perm);
            }
        }
        p.level = s.getInt("factory.level");
        ConfigurationSection lines = s.getConfigurationSection("factory.lines");
        if (lines != null) {
            for (String lineId : lines.getKeys(false)) {
                Plot.Line line = new Plot.Line();
                line.input = lines.getInt(lineId + ".input");
                line.output = lines.getInt(lineId + ".output");
                line.progress = lines.getDouble(lineId + ".progress");
                p.lines.put(lineId, line);
            }
        }
        ConfigurationSection offers = s.getConfigurationSection("offers");
        if (offers != null) {
            for (String key : offers.getKeys(false)) {
                p.offers.put(key, new Plot.Offer(offers.getBoolean(key + ".enabled"), offers.getDouble(key + ".price")));
            }
        }
        p.discountPercent = s.getDouble("discount-percent");
        p.plantCapacity = s.getDouble("plant.capacity");
        p.plantMultiplier = s.getDouble("plant.multiplier", 1.0);
        p.plantId = s.getString("grid.plant");
        p.gridReason = s.getString("grid.reason");
        p.lastPlantId = s.getString("grid.last-plant");
        p.nextTaxAt = s.getLong("tax.next");
        p.taxDebt = s.getDouble("tax.debt");
        p.account.setUnitsPerDay(s.getDouble("account.units"));
        p.account.restore(s.getDouble("account.balance"), s.getDouble("account.rate"), s.getLong("account.updated"));
        ConfigurationSection zones = s.getConfigurationSection("zones");
        if (zones != null) {
            for (String name : zones.getKeys(false)) {
                ConfigurationSection z = zones.getConfigurationSection(name);
                p.zones.put(name, new Plot.Zone(name, Box.parse(z.getString("box")), z.getString("icon", "BRICKS")));
            }
        }
        if (s.contains("spawn")) {
            p.hasSpawn = true;
            p.spawnX = s.getDouble("spawn.x");
            p.spawnY = s.getDouble("spawn.y");
            p.spawnZ = s.getDouble("spawn.z");
            p.spawnYaw = (float) s.getDouble("spawn.yaw");
            p.spawnPitch = (float) s.getDouble("spawn.pitch");
        }
        p.restorePending = s.getBoolean("restore-pending");
        try {
            p.restoreMode = Plot.RestoreMode.valueOf(s.getString("restore-mode", "FULL"));
        } catch (IllegalArgumentException e) {
            p.restoreMode = Plot.RestoreMode.FULL;
        }
        p.restoreArrest = s.getBoolean("restore-arrest");
        p.restoreReason = s.getString("restore-reason");
        p.restoreBatch = s.getString("restore-batch");
        String rf = s.getString("restore-for");
        if (rf != null) {
            p.restoreFor = UUID.fromString(rf);
        }
        return p;
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Plot p : plots.values()) {
            String k = "plots." + p.id + ".";
            yml.set(k + "world", p.world);
            yml.set(k + "box", p.box.serialize());
            yml.set(k + "category", p.category.key());
            yml.set(k + "created-by", p.createdBy == null ? null : p.createdBy.toString());
            yml.set(k + "name", p.name);
            yml.set(k + "description", p.description);
            if (p.hasHolo) {
                yml.set(k + "holo.x", p.holoX);
                yml.set(k + "holo.y", p.holoY);
                yml.set(k + "holo.z", p.holoZ);
            }
            yml.set(k + "reset-on-release", p.resetOnRelease);
            yml.set(k + "grace-until", p.graceUntil);
            yml.set(k + "price", p.price);
            yml.set(k + "rent-per-day", p.rentPerDay);
            yml.set(k + "status", p.status.name());
            yml.set(k + "owner", p.owner == null ? null : p.owner.toString());
            yml.set(k + "owner-name", p.ownerName);
            yml.set(k + "rented-until", p.rentedUntil);
            yml.set(k + "auto-renew", p.autoRenew);
            for (var e : p.grants.entrySet()) {
                yml.set(k + "grants." + e.getKey(), e.getValue().stream().map(Enum::name).toList());
            }
            yml.set(k + "public-perms", p.publicPerms.stream().map(Enum::name).toList());
            yml.set(k + "factory.level", p.level);
            for (var l : p.lines.entrySet()) {
                yml.set(k + "factory.lines." + l.getKey() + ".input", l.getValue().input);
                yml.set(k + "factory.lines." + l.getKey() + ".output", l.getValue().output);
                yml.set(k + "factory.lines." + l.getKey() + ".progress", l.getValue().progress);
            }
            for (var o : p.offers.entrySet()) {
                yml.set(k + "offers." + o.getKey() + ".enabled", o.getValue().enabled);
                yml.set(k + "offers." + o.getKey() + ".price", o.getValue().price);
            }
            yml.set(k + "discount-percent", p.discountPercent);
            yml.set(k + "plant.capacity", p.plantCapacity);
            yml.set(k + "plant.multiplier", p.plantMultiplier);
            yml.set(k + "grid.plant", p.plantId);
            yml.set(k + "grid.reason", p.gridReason);
            yml.set(k + "grid.last-plant", p.lastPlantId);
            yml.set(k + "tax.next", p.nextTaxAt);
            yml.set(k + "tax.debt", p.taxDebt);
            yml.set(k + "account.units", p.account.unitsPerDay());
            yml.set(k + "account.balance", p.account.rawBalance());
            yml.set(k + "account.rate", p.account.ratePerDay());
            yml.set(k + "account.updated", p.account.updatedAtSec());
            for (Plot.Zone z : p.zones.values()) {
                yml.set(k + "zones." + z.name() + ".box", z.box().serialize());
                yml.set(k + "zones." + z.name() + ".icon", z.icon());
            }
            if (p.hasSpawn) {
                yml.set(k + "spawn.x", p.spawnX);
                yml.set(k + "spawn.y", p.spawnY);
                yml.set(k + "spawn.z", p.spawnZ);
                yml.set(k + "spawn.yaw", p.spawnYaw);
                yml.set(k + "spawn.pitch", p.spawnPitch);
            }
            yml.set(k + "restore-pending", p.restorePending);
            yml.set(k + "restore-for", p.restoreFor == null ? null : p.restoreFor.toString());
            yml.set(k + "restore-mode", p.restoreMode.name());
            yml.set(k + "restore-arrest", p.restoreArrest);
            yml.set(k + "restore-reason", p.restoreReason);
            yml.set(k + "restore-batch", p.restoreBatch);
        }
        try {
            plugin.getDataFolder().mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            yml.save(tmp);
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить plots.yml", e);
        }
    }
}
