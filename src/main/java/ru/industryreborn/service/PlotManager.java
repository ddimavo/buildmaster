package ru.industryreborn.service;

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
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.RoomClass;

/** Хранилище участков: загрузка/сохранение и быстрый поиск по координатам. */
public final class PlotManager {

    private final IndustryRebornPlugin plugin;
    private final File file;
    private final Map<String, Plot> plots = new java.util.LinkedHashMap<>();
    /** мир -> ключ чанка -> участки, задевающие этот чанк */
    private final Map<String, Map<Long, List<Plot>>> index = new HashMap<>();

    public PlotManager(IndustryRebornPlugin plugin) {
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

    private int modCount;

    /** Счётчик изменений набора участков (для кэшей, зависящих от списка участков). */
    public int modCount() {
        return modCount;
    }

    public void add(Plot plot) {
        plots.put(plot.id.toLowerCase(Locale.ROOT), plot);
        modCount++;
        reindex();
    }

    public void remove(Plot plot) {
        plots.remove(plot.id.toLowerCase(Locale.ROOT));
        modCount++;
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
        // вложенные участки (номера отеля): побеждает самый маленький, то есть самый внутренний
        Plot best = null;
        for (Plot p : list) {
            if (p.box.contains(x, y, z) && (best == null || p.box.volume() < best.box.volume())) {
                best = p;
            }
        }
        return best;
    }

    public Plot overlapping(String world, Box box) {
        return overlapping(world, box, null);
    }

    /** Участок, пересекающийся с областью; участок с id ignoreId (отель для своих номеров) не считается. */
    public Plot overlapping(String world, Box box, String ignoreId) {
        for (Plot p : plots.values()) {
            if (p.world.equals(world) && p.box.intersects(box) && !p.id.equalsIgnoreCase(ignoreId)) {
                return p;
            }
        }
        return null;
    }

    /** Номера отеля. */
    public List<Plot> childrenOf(String parentId) {
        List<Plot> result = new ArrayList<>();
        for (Plot p : plots.values()) {
            if (p.parentId != null && p.parentId.equalsIgnoreCase(parentId)) {
                result.add(p);
            }
        }
        return result;
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
        modCount++;
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
        p.category = ru.industryreborn.model.Category.parse(s.getString("category", "shop"));
        if (p.category == null) {
            p.category = ru.industryreborn.model.Category.SHOP;
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
        try {
            p.kind = Plot.Kind.valueOf(s.getString("kind", "ADMIN"));
            p.approval = Plot.Approval.valueOf(s.getString("approval", "APPROVED"));
        } catch (IllegalArgumentException e) {
            p.kind = Plot.Kind.ADMIN;
        }
        p.regionId = s.getString("region");
        String landlord = s.getString("landlord");
        if (landlord != null) {
            p.landlord = UUID.fromString(landlord);
        }
        p.landlordName = s.getString("landlord-name");
        p.sharedAccess = s.getBoolean("shared-access");
        for (String u : s.getStringList("required-consents")) {
            p.requiredConsents.add(UUID.fromString(u));
        }
        for (String u : s.getStringList("consents")) {
            p.consents.add(UUID.fromString(u));
        }
        for (String u : s.getStringList("region-members")) {
            p.regionMembers.add(UUID.fromString(u));
        }
        p.revenue = s.getDouble("revenue");
        p.linkedTo = s.getString("link");
        p.approvedEnchants.addAll(s.getStringList("approved-enchants"));
        p.learnedRecipes.addAll(s.getStringList("learned-recipes"));
        for (String entry : s.getStringList("stock-custom")) {
            int eq = entry.lastIndexOf('=');
            if (eq > 0) {
                try {
                    int n = Integer.parseInt(entry.substring(eq + 1));
                    if (n > 0) {
                        p.customStock.put(entry.substring(0, eq), n);
                    }
                } catch (NumberFormatException ignored) {
                    // повреждённая запись пропускается
                }
            }
        }
        p.storefront = s.getBoolean("storefront");
        p.linkRequests.addAll(s.getStringList("link-requests"));
        if (s.isList("services-pos") && s.getDoubleList("services-pos").size() == 3) {
            var l = s.getDoubleList("services-pos");
            p.servicesPos = new double[] {l.get(0), l.get(1), l.get(2)};
        }
        if (s.isList("manager-pos") && s.getDoubleList("manager-pos").size() == 3) {
            var l = s.getDoubleList("manager-pos");
            p.managerPos = new double[] {l.get(0), l.get(1), l.get(2)};
        }
        if (s.isList("log-pos") && s.getDoubleList("log-pos").size() == 3) {
            var l = s.getDoubleList("log-pos");
            p.logPos = new double[] {l.get(0), l.get(1), l.get(2)};
        }
        ConfigurationSection goods = s.getConfigurationSection("goods");
        if (goods != null) {
            for (String m : goods.getKeys(false)) {
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(m);
                if (material != null && goods.getInt(m) >= 0) {
                    p.goods.put(material, goods.getInt(m));
                }
            }
        }
        ConfigurationSection prices = s.getConfigurationSection("prices");
        if (prices != null) {
            for (String m : prices.getKeys(false)) {
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(m);
                if (material != null) {
                    p.prices.put(material, prices.getDouble(m));
                }
            }
        }
        for (String entry : s.getStringList("goods-custom")) {
            int eq = entry.lastIndexOf('=');
            try {
                if (eq > 0 && Integer.parseInt(entry.substring(eq + 1)) > 0) {
                    p.customGoods.put(entry.substring(0, eq), Integer.parseInt(entry.substring(eq + 1)));
                }
            } catch (NumberFormatException ignored) {
                // повреждённая запись пропускается
            }
        }
        for (String entry : s.getStringList("prices-custom")) {
            int eq = entry.lastIndexOf('=');
            try {
                if (eq > 0) {
                    p.customPrices.put(entry.substring(0, eq), Double.parseDouble(entry.substring(eq + 1)));
                }
            } catch (NumberFormatException ignored) {
                // повреждённая запись пропускается
            }
        }
        p.parentId = s.getString("parent");
        p.maxRoomRate = s.getDouble("max-room-rate");
        RoomClass loadedClass = RoomClass.parse(s.getString("room-class"));
        p.roomClass = loadedClass == null ? RoomClass.ECONOMY : loadedClass;
        p.roomRevenue = s.getDouble("room-revenue");
        for (String d : s.getStringList("doors")) {
            String[] c = d.split(",");
            p.doors.add(new int[] {Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()), Integer.parseInt(c[2].trim())});
        }
        p.level = s.getInt("factory.level");
        ConfigurationSection legacy = s.getConfigurationSection("factory.lines");
        if (legacy != null) {
            for (String lineId : legacy.getKeys(false)) {
                p.legacyLines.put(lineId, new int[] {legacy.getInt(lineId + ".input"), legacy.getInt(lineId + ".output")});
            }
        }
        ConfigurationSection machines = s.getConfigurationSection("machines");
        if (machines != null) {
            for (String key : machines.getKeys(false)) {
                Plot.MachineState state = new Plot.MachineState();
                state.recipe = machines.getString(key + ".recipe");
                state.progress = machines.getDouble(key + ".progress");
                state.output = machines.getInt(key + ".output");
                p.machineStates.put(key.replace(':', ','), state);
            }
        }
        ConfigurationSection stock = s.getConfigurationSection("stock");
        if (stock != null) {
            for (String m : stock.getKeys(false)) {
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(m);
                if (material != null && stock.getInt(m) > 0) {
                    p.stock.put(material, stock.getInt(m));
                }
            }
        }
        p.rawChests.addAll(s.getStringList("raw-chests"));
        p.productChests.addAll(s.getStringList("product-chests"));
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
        p.restoreKeep = s.getBoolean("restore-keep");
        p.hasBuilding = s.getBoolean("has-building");
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
            yml.set(k + "has-building", p.hasBuilding);
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
            yml.set(k + "kind", p.kind.name());
            yml.set(k + "approval", p.approval.name());
            yml.set(k + "region", p.regionId);
            yml.set(k + "landlord", p.landlord == null ? null : p.landlord.toString());
            yml.set(k + "landlord-name", p.landlordName);
            yml.set(k + "shared-access", p.sharedAccess);
            yml.set(k + "required-consents", p.requiredConsents.stream().map(UUID::toString).toList());
            yml.set(k + "consents", p.consents.stream().map(UUID::toString).toList());
            yml.set(k + "region-members", p.regionMembers.stream().map(UUID::toString).toList());
            yml.set(k + "revenue", p.revenue);
            yml.set(k + "link", p.linkedTo);
            if (!p.approvedEnchants.isEmpty()) {
                yml.set(k + "approved-enchants", new java.util.ArrayList<>(p.approvedEnchants));
            }
            if (!p.learnedRecipes.isEmpty()) {
                yml.set(k + "learned-recipes", new java.util.ArrayList<>(p.learnedRecipes));
            }
            if (!p.customStock.isEmpty()) {
                yml.set(k + "stock-custom", p.customStock.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
            }
            if (p.storefront) {
                yml.set(k + "storefront", true);
            }
            if (!p.linkRequests.isEmpty()) {
                yml.set(k + "link-requests", new java.util.ArrayList<>(p.linkRequests));
            }
            if (p.servicesPos != null) {
                yml.set(k + "services-pos", java.util.List.of(p.servicesPos[0], p.servicesPos[1], p.servicesPos[2]));
            }
            if (p.logPos != null) {
                yml.set(k + "log-pos", java.util.List.of(p.logPos[0], p.logPos[1], p.logPos[2]));
            }
            if (p.managerPos != null) {
                yml.set(k + "manager-pos", java.util.List.of(p.managerPos[0], p.managerPos[1], p.managerPos[2]));
            }
            for (var g : p.goods.entrySet()) {
                yml.set(k + "goods." + g.getKey().name(), g.getValue());
            }
            for (var pr : p.prices.entrySet()) {
                yml.set(k + "prices." + pr.getKey().name(), pr.getValue());
            }
            if (!p.customGoods.isEmpty()) {
                yml.set(k + "goods-custom", p.customGoods.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
            }
            if (!p.customPrices.isEmpty()) {
                yml.set(k + "prices-custom", p.customPrices.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
            }
            yml.set(k + "parent", p.parentId);
            yml.set(k + "max-room-rate", p.maxRoomRate);
            if (p.roomRevenue != 0) {
                yml.set(k + "room-revenue", p.roomRevenue);
            }
            if (p.roomClass != RoomClass.ECONOMY) {
                yml.set(k + "room-class", p.roomClass.name());
            }
            yml.set(k + "doors", p.doors.stream().map(d -> d[0] + "," + d[1] + "," + d[2]).toList());
            yml.set(k + "factory.level", p.level);
            for (var m : p.machineStates.entrySet()) {
                String mk = k + "machines." + m.getKey().replace(',', ':') + ".";
                yml.set(mk + "recipe", m.getValue().recipe);
                yml.set(mk + "progress", m.getValue().progress);
                yml.set(mk + "output", m.getValue().output);
            }
            for (var st : p.stock.entrySet()) {
                yml.set(k + "stock." + st.getKey().name(), st.getValue());
            }
            if (!p.rawChests.isEmpty()) {
                yml.set(k + "raw-chests", p.rawChests);
            }
            if (!p.productChests.isEmpty()) {
                yml.set(k + "product-chests", p.productChests);
            }
            for (var l : p.legacyLines.entrySet()) {
                yml.set(k + "factory.lines." + l.getKey() + ".input", l.getValue()[0]);
                yml.set(k + "factory.lines." + l.getKey() + ".output", l.getValue()[1]);
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
            yml.set(k + "restore-keep", p.restoreKeep);
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
