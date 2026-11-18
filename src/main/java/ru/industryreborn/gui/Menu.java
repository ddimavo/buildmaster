package ru.industryreborn.gui;

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
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.util.Style;
import ru.industryreborn.util.Text;

/** Базовое меню-инвентарь: кнопки привязываются к слотам, клики всегда отменяются. */
public abstract class Menu implements InventoryHolder {

    protected final IndustryRebornPlugin plugin;
    protected final Player player;
    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    protected Menu(IndustryRebornPlugin plugin, Player player, String title, int rows) {
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
        // верхняя строка - тёмная шапка, нижняя - панель навигации цвета раздела, середина - нейтральный фон
        ItemStack top = Text.icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        ItemStack bottom = Text.icon(Ui.theme(getClass()), " ");
        ItemStack inner = Text.icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        int size = inventory.getSize();
        int rows = size / 9;
        for (int i = 0; i < size; i++) {
            if (inventory.getItem(i) == null) {
                int row = i / 9;
                inventory.setItem(i, row == 0 ? top : row == rows - 1 && rows > 1 ? bottom : inner);
            }
        }
    }

    /**
     * Нижняя панель навигации одинакова во всех меню: назад, страницы, главное меню, закрыть.
     * back == null - кнопки «назад» нет; pages <= 1 - без листания.
     */
    protected void navBar(String backName, Runnable back, int page, int pages, java.util.function.IntConsumer goPage) {
        int base = (rows() - 1) * 9;
        if (back != null) {
            set(base, Ui.back(backName), e -> back.run());
        }
        if (pages > 1) {
            if (page > 0) {
                set(base + 1, Ui.prev(page, pages), e -> goPage.accept(page - 1));
            }
            set(base + 3, Text.icon(Material.PAPER, "<white>Страница " + (page + 1) + " из " + pages));
            if (page < pages - 1) {
                set(base + 5, Ui.next(page, pages), e -> goPage.accept(page + 1));
            }
        }
        set(base + 4, Ui.home(), e -> new MainMenu(plugin, player).open());
        set(base + 8, Ui.close(), e -> player.closeInventory());
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

    /** Клик по нижнему инвентарю игрока (по умолчанию ничего; меню-конструкторы выбирают предметы из инвентаря). */
    public void handleBottomClick(InventoryClickEvent event) { }

    protected int rows() {
        return inventory.getSize() / 9;
    }
}
