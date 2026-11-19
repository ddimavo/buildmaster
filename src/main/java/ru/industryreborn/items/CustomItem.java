package ru.industryreborn.items;

import java.util.List;
import java.util.Set;
import org.bukkit.Material;

/**
 * Предмет плагина: описан в items/*.yml либо найден в файлах Nexo по метке PersistentData industryreborn:item.
 *
 * @param nexoId      если задан, внешний вид берётся из предмета Nexo с этим id (метка плагина добавляется на предмет сама)
 * @param model       custom_model_data (null - не задан)
 * @param itemModel   ключ item_model, например «industryreborn:steel» или «nexo:ruby_skin» (null - не задан); для скина - модель, которую он ставит инструменту
 * @param kind        тип предмета
 * @param skinFor     для скина: типы инструментов (pickaxe, axe, shovel, hoe, sword, any)
 * @param nexoSource  предмет найден в файлах Nexo (а не описан в items/*.yml)
 */
public record CustomItem(String id, Material material, String name, List<String> lore, Integer model, String itemModel, String nexoId,
                         ItemKind kind, Set<String> skinFor, boolean nexoSource) {

    public CustomItem(String id, Material material, String name, List<String> lore, Integer model, String itemModel, String nexoId) {
        this(id, material, name, lore, model, itemModel, nexoId, ItemKind.ITEM, Set.of(), false);
    }

    public ItemRef ref() {
        return new ItemRef(ItemRef.Kind.PLUGIN, id);
    }
}
