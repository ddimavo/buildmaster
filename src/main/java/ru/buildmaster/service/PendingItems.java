package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;

/** Предметы, которые не удалось выдать сразу (игрок оффлайн или нет места) - ждут в файлах. */
public final class PendingItems {

    private final BuildMasterPlugin plugin;
    private final File dir;

    public PendingItems(BuildMasterPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "pending");
    }

    private File file(UUID uuid) {
        return new File(dir, uuid + ".yml");
    }

    public void add(UUID uuid, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        File f = file(uuid);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        List<String> batches = new ArrayList<>(yml.getStringList("batches"));
        batches.add(Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items)));
        yml.set("batches", batches);
        save(yml, f);
    }

    public int count(UUID uuid) {
        int total = 0;
        for (ItemStack s : load(uuid)) {
            total += s.getAmount();
        }
        return total;
    }

    private List<ItemStack> load(UUID uuid) {
        List<ItemStack> result = new ArrayList<>();
        File f = file(uuid);
        if (!f.exists()) {
            return result;
        }
        for (String b : YamlConfiguration.loadConfiguration(f).getStringList("batches")) {
            for (ItemStack s : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(b))) {
                if (s != null && !s.getType().isAir()) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    /** Выдаёт всё, что помещается. Возвращает число оставшихся в хранилище предметов. */
    public int claim(Player player) {
        UUID uuid = player.getUniqueId();
        List<ItemStack> items = load(uuid);
        if (items.isEmpty()) {
            return 0;
        }
        List<ItemStack> left = new ArrayList<>(player.getInventory().addItem(items.toArray(new ItemStack[0])).values());
        File f = file(uuid);
        if (left.isEmpty()) {
            f.delete();
            return 0;
        }
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("batches", List.of(Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(left))));
        save(yml, f);
        int total = 0;
        for (ItemStack s : left) {
            total += s.getAmount();
        }
        return total;
    }

    private void save(YamlConfiguration yml, File f) {
        try {
            dir.mkdirs();
            yml.save(f);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить предметы игрока: " + f.getName(), e);
        }
    }
}
