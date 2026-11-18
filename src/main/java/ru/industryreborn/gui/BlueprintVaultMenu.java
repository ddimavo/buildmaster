package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.util.Text;

/**
 * Хранилище администрации: сюда попадают чертежи, которые нельзя продавать и удалять. Администратор с правом industryreborn.vault забирает
 * предметы; забранный чертёж снова рабочий, его можно выдать кому угодно.
 */
public final class BlueprintVaultMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private static final int FIRST = 9;

    public BlueprintVaultMenu(IndustryRebornPlugin plugin, Player player, int page) {
        super(plugin, player, "<dark_gray>Администрация › Хранилище чертежей", 6);
        List<ItemStack> items = plugin.blueprints().vaultItems();
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= items.size()) {
                break;
            }
            ItemStack stack = items.get(index).clone();
            ItemStack shown = stack.clone();
            var meta = shown.getItemMeta();
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>(meta != null && meta.hasLore() ? meta.lore() : List.of());
            lore.add(Text.mm("<yellow>Нажмите, чтобы забрать в инвентарь"));
            if (meta != null) {
                meta.lore(lore);
                shown.setItemMeta(meta);
            }
            int takeIndex = index;
            set(FIRST + i, shown, e -> {
                if (!plugin.blueprints().mayUseVault(player)) {
                    Text.err(player, "Недостаточно прав.");
                    return;
                }
                ItemStack taken = plugin.blueprints().takeFromVault(takeIndex);
                if (taken == null) {
                    Text.err(player, "Предмет уже забран.");
                } else if (!player.getInventory().addItem(taken).isEmpty()) {
                    plugin.blueprints().deposit(taken, "нет места в инвентаре: " + player.getName());
                    Text.err(player, "В инвентаре нет места: предмет остался в хранилище.");
                } else {
                    Text.ok(player, "Предмет забран из хранилища.");
                }
                new BlueprintVaultMenu(plugin, player, current).open();
            });
        }
        if (items.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>Хранилище пусто"));
        }
        set(4, Text.icon(Material.ENDER_CHEST, "<gold>Хранилище чертежей", "<gray>Предметов: <white>" + items.size(),
                "<gray>Сюда уходят чертежи, которые нельзя", "<gray>продавать через аукцион казны и удалять",
                "<gray>Положить предмет: /district blueprint vault put"));
        navBar(null, null, current, pages, p -> new BlueprintVaultMenu(plugin, player, p).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
