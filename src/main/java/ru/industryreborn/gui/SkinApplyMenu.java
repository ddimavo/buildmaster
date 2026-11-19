package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.items.CustomItem;
import ru.industryreborn.util.Text;

/** Применение скина: инструменты из вашего инвентаря, подходящие под скин в основной руке. Скин расходуется на один выбранный предмет. */
public final class SkinApplyMenu extends Menu {

    public SkinApplyMenu(IndustryRebornPlugin plugin, Player player) {
        super(plugin, player, "<dark_gray>Район › Применить скин", 6);
        CustomItem skin = plugin.skins().skinOf(player.getInventory().getItemInMainHand());
        if (skin == null) {
            set(22, Text.icon(Material.BARRIER, "<red>Скин не в руке", "<gray>Возьмите скин в основную руку",
                    "<gray>и откройте меню снова: /district skin"));
        } else {
            var inv = player.getInventory();
            int shown = 0;
            for (int slot = 0; slot < 36 && shown < 36; slot++) {
                ItemStack stack = inv.getItem(slot);
                if (slot == inv.getHeldItemSlot() || !plugin.skins().eligible(skin, stack)) {
                    continue;
                }
                List<String> lore = new ArrayList<>();
                String current = plugin.skins().appliedSkin(stack);
                lore.add(current == null ? "<gray>Скина нет" : "<gray>Сейчас: <white>" + Text.esc(current));
                lore.add("<red>Скин расходуется и закрепляется");
                lore.add("<red>за этим предметом");
                lore.add("<yellow>Нажмите, чтобы применить");
                ItemStack icon = stack.clone();
                icon.editMeta(meta -> meta.lore(ru.industryreborn.util.Style.lore(lore.toArray(new String[0])).stream().map(Text::item).toList()));
                int target = slot;
                set(9 + shown++, icon, e -> {
                    String error = plugin.skins().apply(player, target);
                    if (error != null) {
                        Text.err(player, error);
                        new SkinApplyMenu(plugin, player).open();
                    } else {
                        player.closeInventory();
                    }
                });
            }
            if (shown == 0) {
                set(22, Text.icon(Material.BARRIER, "<gray>Подходящих инструментов нет",
                        "<gray>Скин ставится на: <white>" + String.join(", ", skin.skinFor())));
            }
            set(4, Text.icon(Material.NETHER_STAR, "<gold>Скин: " + Text.esc(plugin.customItems().displayName(skin.ref())),
                    "<gray>Ставится на: <white>" + String.join(", ", skin.skinFor()),
                    "<gray>Одноразовый: расходуется при применении"));
        }
        navBar(null, null, 0, 1, p -> { });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
