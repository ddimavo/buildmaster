package ru.industryreborn.items;

import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.service.Journal;
import ru.industryreborn.util.Text;

/**
 * Скины: одноразовый предмет, который расходуется при применении и навсегда меняет внешний вид одного инструмента (компонент item_model).
 * Прочность, зачарования и название инструмента остаются. На инструмент ставится метка industryreborn:skin = id скина; повторное применение другого скина заменяет прежний.
 */
public final class SkinService {

    private final IndustryRebornPlugin plugin;
    private java.util.function.BiConsumer<ItemMeta, NamespacedKey> modelSetter = ItemMeta::setItemModel;

    public SkinService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    /** Только для тестов: подмена установки модели (MockBukkit не сохраняет item_model при копировании метаданных). */
    public void setModelSetter(java.util.function.BiConsumer<ItemMeta, NamespacedKey> setter) {
        this.modelSetter = setter == null ? ItemMeta::setItemModel : setter;
    }

    public boolean enabled() {
        return plugin.customItems().enabled();
    }

    /** Скин в предмете или null. */
    public CustomItem skinOf(ItemStack stack) {
        ItemRef ref = stack == null || stack.getType().isAir() ? null : plugin.customItems().identify(stack);
        CustomItem d = ref == null || ref.kind() != ItemRef.Kind.PLUGIN ? null : plugin.customItems().get(ref.id());
        return d != null && d.kind() == ItemKind.SKIN ? d : null;
    }

    /** id скина, применённого к инструменту, или null. */
    public String appliedSkin(ItemStack tool) {
        if (tool == null || !tool.hasItemMeta()) {
            return null;
        }
        return tool.getItemMeta().getPersistentDataContainer().get(plugin.customItems().skinKey(), PersistentDataType.STRING);
    }

    /** Тип инструмента по материалу: pickaxe, axe, shovel, hoe, sword; null - другое. */
    public static String category(Material m) {
        String n = m.name();
        if (n.endsWith("_PICKAXE")) {
            return "pickaxe";
        }
        if (n.endsWith("_AXE")) {
            return "axe";
        }
        if (n.endsWith("_SHOVEL")) {
            return "shovel";
        }
        if (n.endsWith("_HOE")) {
            return "hoe";
        }
        if (n.endsWith("_SWORD")) {
            return "sword";
        }
        return null;
    }

    /** Подходит ли предмет под скин: инструмент нужного типа (или любой предмет с прочностью при skin_for: any), не сам предмет плагина-скин. */
    public boolean eligible(CustomItem skin, ItemStack target) {
        if (skin == null || target == null || target.getType().isAir() || skinOf(target) != null) {
            return false;
        }
        if (skin.skinFor().contains("any")) {
            return target.getType().getMaxDurability() > 0;
        }
        String cat = category(target.getType());
        return cat != null && skin.skinFor().contains(cat);
    }

    /** Применяет скин из основной руки к предмету в слоте инвентаря. Возвращает ошибку или null. */
    public String apply(Player player, int slot) {
        if (!enabled()) {
            return "Скины сейчас отключены.";
        }
        var inv = player.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        CustomItem skin = skinOf(hand);
        if (skin == null) {
            return "Возьмите скин в основную руку.";
        }
        if (skin.itemModel() == null) {
            return "У этого скина не задана модель: обратитесь к администрации.";
        }
        NamespacedKey model = NamespacedKey.fromString(skin.itemModel().toLowerCase(Locale.ROOT));
        if (model == null) {
            return "У этого скина неверная модель: обратитесь к администрации.";
        }
        if (slot < 0 || slot >= inv.getSize() || slot == inv.getHeldItemSlot()) {
            return "Выберите инструмент в инвентаре (не скин в руке).";
        }
        ItemStack target = inv.getItem(slot);
        if (target == null || target.getType().isAir()) {
            return "В этом слоте ничего нет.";
        }
        if (skinOf(target) != null) {
            return "Скин нельзя применить к другому скину.";
        }
        if (!eligible(skin, target)) {
            return "Этот скин ставится на: " + String.join(", ", skin.skinFor()) + ". Выбран другой предмет.";
        }
        if (skin.id().equals(appliedSkin(target))) {
            return "Этот скин уже стоит на выбранном предмете.";
        }
        ItemMeta meta = target.getItemMeta();
        try {
            modelSetter.accept(meta, model);
        } catch (RuntimeException | LinkageError e) {
            return "Сервер не поддерживает смену модели предметов (нужен Paper 1.21.4+).";
        }
        meta.getPersistentDataContainer().set(plugin.customItems().skinKey(), PersistentDataType.STRING, skin.id());
        target.setItemMeta(meta);
        inv.setItem(slot, target);
        // скин расходуется (после того как предмет изменён)
        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
        } else {
            inv.setItemInMainHand(null);
        }
        plugin.journal().add("-", player.getUniqueId(), player.getName(), Journal.Action.FACTORY,
                "Применён скин «" + skin.id() + "» на " + target.getType().name());
        Text.ok(player, "Скин «" + Text.esc(plugin.customItems().displayName(skin.ref())) + "» применён. Скин расходован, внешний вид закреплён за этим предметом.");
        return null;
    }

    /** Снимает скин с инструмента в основной руке (сам скин не возвращается). Возвращает ошибку или null. */
    public String remove(Player player) {
        if (!enabled()) {
            return "Скины сейчас отключены.";
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (appliedSkin(tool) == null) {
            return "В основной руке нет предмета со скином.";
        }
        ItemMeta meta = tool.getItemMeta();
        try {
            modelSetter.accept(meta, null);
        } catch (RuntimeException | LinkageError e) {
            return "Сервер не поддерживает смену модели предметов (нужен Paper 1.21.4+).";
        }
        meta.getPersistentDataContainer().remove(plugin.customItems().skinKey());
        tool.setItemMeta(meta);
        player.getInventory().setItemInMainHand(tool);
        Text.ok(player, "Скин снят. Сам скин не возвращается.");
        return null;
    }
}
