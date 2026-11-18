package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.util.Text;

/** Хранилище игрока: вещи, возвращённые с участков. */
public final class StorageMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private static final int FIRST = 9;

    private record Ref(UUID entry, String plot, long created, byte[] data) { }

    public StorageMenu(IndustryRebornPlugin plugin, Player player, int page) {
        super(plugin, player, "<dark_gray>Район › Хранилище", 6);
        UUID id = player.getUniqueId();
        List<Ref> refs = new ArrayList<>();
        int arrested = 0;
        for (StorageEntry e : plugin.storage().entries(id)) {
            if (e.state == StorageEntry.State.AVAILABLE) {
                for (byte[] b : e.stacks) {
                    refs.add(new Ref(e.id, e.plotId, e.createdAt, b));
                }
            } else {
                arrested += e.stacks.size();
            }
        }
        int pages = Math.max(1, (refs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        int days = plugin.getConfig().getInt("storage-days", 60);

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= refs.size()) {
                break;
            }
            Ref ref = refs.get(index);
            ItemStack icon = ItemStack.deserializeBytes(ref.data());
            long left = ref.created() + days * 86_400_000L - System.currentTimeMillis();
            icon.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(Text.item(""));
                lore.add(Text.item("<gray>С участка: <white>" + Text.esc(ref.plot())));
                lore.add(Text.item("<gray>Хранится ещё: <white>" + Text.duration(left)));
                lore.add(Text.item("<yellow>Нажмите, чтобы забрать"));
                meta.lore(lore);
            });
            set(FIRST + i, icon, e -> {
                ItemStack taken = plugin.storage().take(id, ref.entry(), ref.data());
                if (taken != null) {
                    var left2 = player.getInventory().addItem(taken);
                    if (!left2.isEmpty()) {
                        plugin.storage().putBack(id, ref.plot(), left2.values().iterator().next());
                        Text.err(player, "В инвентаре нет места.");
                    }
                }
                new StorageMenu(plugin, player, current).open();
            });
        }
        if (refs.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>Хранилище пусто"));
        }
        set(47, Text.icon(Material.HOPPER, "<green>Забрать всё, что поместится"), e -> {
            boolean[] full = new boolean[1];
            plugin.storage().takeAll(player, full);
            if (full[0]) {
                Text.err(player, "Инвентарь заполнен. Остальное осталось в хранилище.");
            }
            new StorageMenu(plugin, player, 0).open();
        });
        if (arrested > 0) {
            set(51, Text.icon(Material.IRON_BARS, "<red>Арестовано вещей: " + arrested,
                    "<gray>Решение примет администрация"));
        }
        set(4, Text.icon(refs.isEmpty() ? Material.BARREL : Material.CHEST, "<aqua>Хранилище",
                "<gray>Вещей: <white>" + refs.size(), "<gray>Хранятся: <white>" + days + " суток",
                "<gray>Вещи сюда попадают с участков при отказе, выселении и сбросе"));
        navBar(null, null, current, pages, p -> new StorageMenu(plugin, player, p).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
