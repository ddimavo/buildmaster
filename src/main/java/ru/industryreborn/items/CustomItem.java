package ru.industryreborn.items;

import java.util.List;
import org.bukkit.Material;

/**
 * Предмет плагина (описывает администратор в items/*.yml).
 *
 * @param nexoId   если задан, внешний вид берётся из предмета Nexo с этим id (метка плагина добавляется на предмет сама)
 * @param model    custom_model_data (null - не задан)
 * @param itemModel ключ item_model, например «industryreborn:steel» (null - не задан)
 */
public record CustomItem(String id, Material material, String name, List<String> lore, Integer model, String itemModel, String nexoId) {

    public ItemRef ref() {
        return new ItemRef(ItemRef.Kind.PLUGIN, id);
    }
}
