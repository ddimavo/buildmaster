package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;

/**
 * Зачарования для станков мастерской. Управляющий предлагает связку «предмет + зачарование + уровень» (настоящие и кастомные
 * зачарования берутся из реестра игры), администратор одобряет её, чтобы не получился дисбаланс. Стоимость считается по формуле
 * из конфига (предмет + лазурит), одобренный рецепт назначается столу зачарований.
 */
public final class EnchantService {

    private final IndustryRebornPlugin plugin;

    public EnchantService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    public int maxRecipes() {
        return plugin.getConfig().getInt("enchanting.max-recipes-per-workshop", 20);
    }

    /** Макс. уровень: по умолчанию как в игре; конфиг может разрешить больше (enchanting.max-level-bonus). */
    public int maxLevel(Enchantment e) {
        return e.getMaxLevel() + Math.max(0, plugin.getConfig().getInt("enchanting.max-level-bonus", 0));
    }

    /** Предложить зачарование для мастерской. Администратору применяется сразу. Возвращает ошибку или null. */
    public String propose(Player manager, Plot plot, Material item, String enchantKey, int level) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.ENCHANTING);
        if (moduleOff != null) {
            return moduleOff;
        }
        if (plot.category != Category.WORKSHOP || plot.isFree()) {
            return "Зачарования назначаются мастерским станкам.";
        }
        if (!plot.isOwner(manager.getUniqueId()) && !manager.hasPermission("industryreborn.admin")) {
            return "Зачарования предлагает управляющий мастерской.";
        }
        if (plugin.service().isLocked(plot)) {
            return "Мастерская сейчас обрабатывается.";
        }
        Enchantment e = plugin.recipes().enchantLookup().get(enchantKey);
        if (e == null) {
            return "Неизвестное зачарование «" + enchantKey + "». Список: /district enchant list";
        }
        if (item == null || item.isAir() || !item.isItem()) {
            return "Неизвестный предмет.";
        }
        if (!e.canEnchantItem(new ItemStack(item))) {
            return "Зачарование «" + RecipeBook.shortKey(e) + "» нельзя наложить на " + item.name() + ".";
        }
        if (level < 1 || level > maxLevel(e)) {
            return "Уровень зачарования: от 1 до " + maxLevel(e) + ".";
        }
        String id = RecipeBook.enchantId(item, RecipeBook.shortKey(e), level);
        if (plot.approvedEnchants.contains(id)) {
            return "Это зачарование уже одобрено: назначьте его столу зачарований.";
        }
        if (!manager.hasPermission("industryreborn.admin")) {
            for (var r : plugin.moderation().pendingEnchants(plot.id)) {
                if (r.text.equals(id)) {
                    return "Заявка на это зачарование уже ждёт решения администратора.";
                }
            }
        }
        if (plot.approvedEnchants.size() + plugin.moderation().pendingEnchants(plot.id).size() >= maxRecipes()) {
            return "Достигнут предел зачарований мастерской (" + maxRecipes() + "). Удалите ненужные: /district enchant remove";
        }
        plugin.moderation().submitEnchant(manager, plot, id);
        return null;
    }

    /** Убирает одобренное зачарование и снимает его со станков. */
    public String remove(Player manager, Plot plot, String id) {
        String moduleOff = plugin.modules().refusal(ru.industryreborn.service.Module.ENCHANTING);
        if (moduleOff != null) {
            return moduleOff;
        }
        if (!plot.isOwner(manager.getUniqueId()) && !manager.hasPermission("industryreborn.admin")) {
            return "Зачарования удаляет управляющий мастерской.";
        }
        if (!plot.approvedEnchants.remove(id)) {
            return "Такого зачарования у мастерской нет.";
        }
        for (Plot.MachineState state : plot.machineStates.values()) {
            if (id.equals(state.recipe)) {
                state.recipe = null;
                state.progress = 0;
            }
        }
        plugin.plots().save();
        return null;
    }

    /** Список зачарований, подходящих предмету (для подсказок). */
    public List<String> applicable(Material item) {
        List<String> names = new ArrayList<>();
        for (Enchantment e : plugin.recipes().enchantLookup().all()) {
            if (item == null || e.canEnchantItem(new ItemStack(item))) {
                names.add(RecipeBook.shortKey(e));
            }
        }
        names.sort(String::compareTo);
        return names;
    }
}
