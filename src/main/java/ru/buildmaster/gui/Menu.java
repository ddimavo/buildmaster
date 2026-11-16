package ru.buildmaster.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.util.Style;
import ru.buildmaster.util.Text;

/** Базовое меню-инвентарь: кнопки привязываются к слотам, клики всегда отменяются. */
public abstract class Menu implements InventoryHolder {

    protected final BuildMasterPlugin plugin;
    protected final Player player;
    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    protected Menu(BuildMasterPlugin plugin, Player player, String title, int rows) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, rows * 9, Component.text("").append(Text.mm(Style.title(title))));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open() {
        player.openInventory(inventory);
    }

    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        } else {
            actions.remove(slot);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /**
     * Оформляет пустые слоты единой рамкой: край окна окрашен в цвет раздела, внутри нейтральное серое стекло.
     * Параметр оставлен для совместимости: рамку определяет тема раздела (Ui.theme).
     */
    protected void fill(Material ignored) {
        Material edge = Ui.theme(getClass());
        ItemStack border = Text.icon(edge, " ");
        ItemStack inner = Text.icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        int size = inventory.getSize();
        int rows = size / 9;
        for (int i = 0; i < size; i++) {
            if (inventory.getItem(i) == null) {
                int row = i / 9;
                int col = i % 9;
                boolean onEdge = row == 0 || row == rows - 1 || col == 0 || col == 8;
                inventory.setItem(i, onEdge ? border : inner);
            }
        }
    }

    protected void clear() {
        inventory.clear();
        actions.clear();
    }

    public void handleClick(InventoryClickEvent event) {
        Consumer<InventoryClickEvent> action = actions.get(event.getRawSlot());
        if (action != null) {
            Ui.clickSound(player);
            action.accept(event);
        }
    }

    protected int rows() {
        return inventory.getSize() / 9;
    }
}
