package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.StorageEntry;

/**
 * Хранилище игрока: сюда попадают вещи, возвращённые с участков (сундуки, поставленные блоки и т.д.).
 * Хранится в файлах, срок хранения ограничен (по умолчанию 60 суток).
 */
public final class StorageService {

    private final BuildMasterPlugin plugin;
    private final File dir;
    private final Map<UUID, List<StorageEntry>> cache = new HashMap<>();

    public StorageService(BuildMasterPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "storage");
    }

    private File file(UUID owner) {
        return new File(dir, owner + ".yml");
    }

    public List<StorageEntry> entries(UUID owner) {
        return cache.computeIfAbsent(owner, this::load);
    }

    private List<StorageEntry> load(UUID owner) {
        List<StorageEntry> list = new ArrayList<>();
        File f = file(owner);
        if (!f.exists()) {
            return list;
        }
        ConfigurationSection root = YamlConfiguration.loadConfiguration(f).getConfigurationSection("entries");
        if (root == null) {
            return list;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            StorageEntry e = new StorageEntry(UUID.fromString(id), s.getString("plot", "?"), s.getLong("created"),
                    s.getString("batch", ""), StorageEntry.State.valueOf(s.getString("state", "AVAILABLE")),
                    s.getString("reason"));
            for (String b : s.getStringList("stacks")) {
                e.stacks.add(Base64.getDecoder().decode(b));
            }
            list.add(e);
        }
        return list;
    }

    private void save(UUID owner) {
        List<StorageEntry> list = entries(owner);
        File f = file(owner);
        if (list.isEmpty()) {
            f.delete();
            cache.remove(owner);
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        for (StorageEntry e : list) {
            String k = "entries." + e.id + ".";
            yml.set(k + "plot", e.plotId);
            yml.set(k + "created", e.createdAt);
            yml.set(k + "batch", e.batch);
            yml.set(k + "state", e.state.name());
            yml.set(k + "reason", e.reason);
            List<String> stacks = new ArrayList<>();
            for (byte[] b : e.stacks) {
                stacks.add(Base64.getEncoder().encodeToString(b));
            }
            yml.set(k + "stacks", stacks);
        }
        try {
            dir.mkdirs();
            yml.save(f);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить хранилище " + owner, ex);
        }
    }

    /** Добавляет вещи в хранилище. Повторные вызовы с тем же batch дополняют одну запись. */
    public void add(UUID owner, String plotId, List<ItemStack> items, StorageEntry.State state, String reason,
                    String batch) {
        if (items.isEmpty()) {
            return;
        }
        List<StorageEntry> list = entries(owner);
        StorageEntry target = null;
        for (StorageEntry e : list) {
            if (e.batch.equals(batch) && e.state == state && e.plotId.equals(plotId)) {
                target = e;
                break;
            }
        }
        if (target == null) {
            target = new StorageEntry(UUID.randomUUID(), plotId, System.currentTimeMillis(), batch, state, reason);
            list.add(target);
        }
        for (ItemStack s : items) {
            if (s != null && !s.getType().isAir()) {
                target.stacks.add(s.serializeAsBytes());
            }
        }
        save(owner);
    }

    /** Число предметов в хранилище с указанным состоянием. */
    public int countItems(UUID owner, StorageEntry.State state) {
        int total = 0;
        for (StorageEntry e : entries(owner)) {
            if (e.state == state) {
                for (byte[] b : e.stacks) {
                    total += ItemStack.deserializeBytes(b).getAmount();
                }
            }
        }
        return total;
    }

    /** Забирает конкретную запись-стак. Возвращает предмет или null, если его уже нет. */
    public ItemStack take(UUID owner, UUID entryId, byte[] ref) {
        for (StorageEntry e : entries(owner)) {
            if (e.id.equals(entryId) && e.state == StorageEntry.State.AVAILABLE) {
                for (int i = 0; i < e.stacks.size(); i++) {
                    if (e.stacks.get(i) == ref) {
                        e.stacks.remove(i);
                        if (e.stacks.isEmpty()) {
                            entries(owner).remove(e);
                        }
                        save(owner);
                        return ItemStack.deserializeBytes(ref);
                    }
                }
            }
        }
        return null;
    }

    /** Забирает предмет, ожидающий аукциона. Возвращает его или null, если записи уже нет. */
    public ItemStack takeForAuction(UUID owner, UUID entryId, byte[] ref) {
        for (StorageEntry e : entries(owner)) {
            if (e.id.equals(entryId) && e.state == StorageEntry.State.AUCTION) {
                for (int i = 0; i < e.stacks.size(); i++) {
                    if (e.stacks.get(i) == ref) {
                        e.stacks.remove(i);
                        if (e.stacks.isEmpty()) {
                            entries(owner).remove(e);
                        }
                        save(owner);
                        return ItemStack.deserializeBytes(ref);
                    }
                }
            }
        }
        return null;
    }

    /** Возвращает предмет обратно (если в инвентаре не нашлось места). */
    public void putBack(UUID owner, String plotId, ItemStack item) {
        add(owner, plotId, List.of(item), StorageEntry.State.AVAILABLE, null, "putback");
    }

    /** Удаляет просроченные записи. Возвращает число удалённых записей. */
    public int sweepExpired(long now, int storageDays) {
        return sweepExpired(now, storageDays, false);
    }

    /** Просроченные записи удаляются либо (toAuction) передаются на аукцион. Возвращает число затронутых записей. */
    public int sweepExpired(long now, int storageDays, boolean toAuction) {
        int removed = 0;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            UUID owner;
            try {
                owner = UUID.fromString(f.getName().replace(".yml", ""));
            } catch (IllegalArgumentException e) {
                continue;
            }
            List<StorageEntry> list = entries(owner);
            int before = list.size();
            if (toAuction) {
                int moved = 0;
                for (StorageEntry e : list) {
                    if (e.expired(now, storageDays)) {
                        e.state = StorageEntry.State.AUCTION;
                        moved++;
                    }
                }
                if (moved > 0) {
                    removed += moved;
                    save(owner);
                }
                continue;
            }
            list.removeIf(e -> e.expired(now, storageDays));
            if (list.size() != before) {
                removed += before - list.size();
                save(owner);
            }
        }
        return removed;
    }

    /** Игроки, у которых есть арестованные вещи. */
    public List<UUID> playersWithState(StorageEntry.State state) {
        List<UUID> result = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return result;
        }
        for (File f : files) {
            try {
                UUID owner = UUID.fromString(f.getName().replace(".yml", ""));
                if (entries(owner).stream().anyMatch(e -> e.state == state)) {
                    result.add(owner);
                }
            } catch (IllegalArgumentException ignored) {
                // посторонний файл
            }
        }
        return result;
    }

    /** Решение администратора по арестованным вещам игрока. Возвращает число затронутых записей. */
    public int decideArrested(UUID owner, StorageEntry.State newState) {
        return decideArrested(owner, null, newState);
    }

    /** То же для одной записи (entryId) либо для всех арестованных (entryId == null). */
    public int decideArrested(UUID owner, UUID entryId, StorageEntry.State newState) {
        List<StorageEntry> list = entries(owner);
        int n = 0;
        java.util.function.Predicate<StorageEntry> match = e -> e.state == StorageEntry.State.ARRESTED
                && (entryId == null || e.id.equals(entryId));
        if (newState == null) { // удалить
            n = (int) list.stream().filter(match).count();
            list.removeIf(match);
        } else {
            for (StorageEntry e : list) {
                if (match.test(e)) {
                    e.state = newState;
                    n++;
                }
            }
        }
        if (n > 0) {
            save(owner);
        }
        return n;
    }
}
