package ru.buildmaster.listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Box;
import ru.buildmaster.util.Text;

/** Выделение области админом: золотой топор (ЛКМ - точка 1, ПКМ - точка 2) или команды pos1/pos2. */
public final class SelectionListener implements Listener {

    public static final class Selection {
        public String world;
        public int[] p1;
        public int[] p2;

        /** Область выделения или null, если не заданы обе точки. */
        public Box box() {
            return p1 == null || p2 == null ? null : Box.of(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2]);
        }
    }

    private final BuildMasterPlugin plugin;
    private final NamespacedKey wandKey;
    private final Map<UUID, Selection> selections = new HashMap<>();

    public SelectionListener(BuildMasterPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "wand");
    }

    public Selection get(Player player) {
        return selections.get(player.getUniqueId());
    }

    public void setPoint(Player player, int number, int x, int y, int z) {
        Selection sel = selections.computeIfAbsent(player.getUniqueId(), k -> new Selection());
        String world = player.getWorld().getName();
        if (sel.world != null && !sel.world.equals(world)) {
            sel.p1 = null;
            sel.p2 = null;
        }
        sel.world = world;
        if (number == 1) {
            sel.p1 = new int[] {x, y, z};
        } else {
            sel.p2 = new int[] {x, y, z};
        }
        Box box = sel.box();
        Text.ok(player, "Точка " + number + ": <white>" + x + ", " + y + ", " + z + "</white>"
                + (box == null ? "" : " (область " + box.describe() + ", " + box.volume() + " блоков)"));
    }

    public ItemStack createWand() {
        ItemStack wand = new ItemStack(Material.GOLDEN_AXE);
        wand.editMeta(meta -> {
            meta.displayName(Text.item("<gold>Выделение участка"));
            meta.lore(java.util.List.of(
                    Text.item("<gray>ЛКМ - первая точка"),
                    Text.item("<gray>ПКМ - вторая точка")));
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        });
        return wand;
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isWand(event.getItem())) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() == Action.PHYSICAL) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("buildmaster.admin")) {
            return;
        }
        event.setCancelled(true);
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            setPoint(player, 1, block.getX(), block.getY(), block.getZ());
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            setPoint(player, 2, block.getX(), block.getY(), block.getZ());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
    }
}
