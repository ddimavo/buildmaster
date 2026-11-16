package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.ProductionService;
import ru.buildmaster.service.RecipeBook;
import ru.buildmaster.util.Style;
import ru.buildmaster.util.Text;

/** Настройка станка: что он будет делать. Рецепты берутся из реестра игры для типа станка. */
public final class MachineMenu extends Menu {

    private static final int PAGE_SIZE = 36;

    public MachineMenu(BuildMasterPlugin plugin, Player player, Plot plot, String pos, String category, int page, String search) {
        super(plugin, player, "<dark_gray>Станок · " + pos, 6);
        var f = plugin.factory();
        ProductionService.View view = f.view(plot, pos);
        Material block = view.block();
        List<RecipeBook.Recipe> all = plugin.recipes().forBlock(block);

        // ---- фильтры: категория и поиск ----
        Set<String> categories = new LinkedHashSet<>();
        for (RecipeBook.Recipe r : all) {
            if (!r.category().isEmpty()) {
                categories.add(r.category());
            }
        }
        String q = search == null ? "" : search.toLowerCase(Locale.ROOT);
        List<RecipeBook.Recipe> list = new ArrayList<>();
        for (RecipeBook.Recipe r : all) {
            if ((category == null || r.category().equals(category))
                    && (q.isEmpty() || r.output().name().toLowerCase(Locale.ROOT).contains(q)
                    || r.inputs().stream().anyMatch(i -> i.options().stream().anyMatch(m -> m.name().toLowerCase(Locale.ROOT).contains(q))))) {
                list.add(r);
            }
        }
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= list.size()) {
                break;
            }
            RecipeBook.Recipe r = list.get(index);
            boolean active = view.recipe() != null && view.recipe().id().equals(r.id());
            List<String> lore = new ArrayList<>();
            for (RecipeBook.Ingredient ing : r.inputs()) {
                int have = 0;
                for (Material m : ing.options()) {
                    have += f.stock(plot, m);
                }
                lore.add("<gray>Сырьё: <white>" + ing.count() + " × " + names(ing) + " <dark_gray>(на складе " + have + ")");
            }
            lore.add("<gray>Результат: <white>" + r.outputAmount() + " × " + r.output().name());
            lore.add("<gray>Время партии: <white>" + Math.round(f.secondsPerBatch(plot, r) * 10) / 10.0 + " с");
            if (active) {
                lore.add("<green>● Сейчас назначено");
            }
            lore.add("<yellow>Нажмите, чтобы назначить");
            set(i, Text.icon(ServicesMenu.iconFor(r.output()), (active ? "<green>" : "<gold>") + r.output().name(), lore.toArray(new String[0])),
                    e -> {
                        String error = f.assign(player, plot, pos, r.id());
                        if (error != null) {
                            Text.err(player, error);
                            new MachineMenu(plugin, player, plot, pos, category, current, search).open();
                        } else {
                            Text.ok(player, "Станку назначен рецепт: " + r.output().name() + ".");
                            new FactoryMenu(plugin, player, plot, null).open();
                        }
                    });
        }
        if (all.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>Этот станок ничего не производит",
                    "<gray>Он обслуживает заказы игроков", "<gray>Производят: печи, наковальни, стол зачарований"));
        } else if (list.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<red>Ничего не найдено", "<gray>Измените категорию или поиск"));
        }

        // ---- категории (строка 36-44) ----
        int cs = 36;
        if (!categories.isEmpty()) {
            set(cs++, Text.icon(category == null ? Material.LIME_DYE : Material.GRAY_DYE, "<white>Все",
                    "<yellow>Нажмите, чтобы показать всё"), e -> new MachineMenu(plugin, player, plot, pos, null, 0, search).open());
            for (String c : categories) {
                if (cs > 42) {
                    break;
                }
                boolean on = c.equals(category);
                set(cs++, Text.icon(on ? Material.LIME_DYE : Material.GRAY_DYE, (on ? "<green>" : "<white>") + c,
                        "<yellow>Нажмите, чтобы отфильтровать"), e -> new MachineMenu(plugin, player, plot, pos, c, 0, search).open());
            }
        }
        // ---- навигация ----
        if (current > 0) {
            set(45, Ui.prev(current, pages), e -> new MachineMenu(plugin, player, plot, pos, category, current - 1, search).open());
        }
        List<String> info = new ArrayList<>();
        info.add("<gray>Тип: <white>" + FactoryMenu.blockTitle(block));
        info.add("<gray>Задание: <white>" + (view.recipe() == null ? "не назначено" : view.recipe().output().name()));
        info.add("<gray>Статус: <white>" + view.status().title());
        if (view.recipe() != null && view.status() == ProductionService.Status.WORKING) {
            info.add(Style.bar(view.ratio(), 12, "green"));
        }
        info.add("<gray>Станков с заданием: <white>" + f.assignedCount(plot) + " из " + f.slots(plot));
        set(46, Text.icon(block == Material.AIR ? Material.FURNACE : block, "<gold>Этот станок", info.toArray(new String[0])));
        if (view.recipe() != null) {
            set(47, Text.icon(Material.BARRIER, "<red>Снять задание", "<gray>Станок будет обслуживать заказы игроков",
                    "<yellow>Нажмите, чтобы снять"), e -> {
                String error = f.clear(player, plot, pos);
                if (error != null) {
                    Text.err(player, error);
                }
                new MachineMenu(plugin, player, plot, pos, category, current, search).open();
            });
        }
        set(48, Text.icon(Material.COMPASS, "<aqua>Поиск", q.isEmpty() ? "<gray>По названию результата или сырья" : "<gray>Сейчас: <white>" + Text.esc(search),
                "<yellow>ЛКМ - ввести, ПКМ - сбросить"), e -> {
                    if (e.isRightClick()) {
                        new MachineMenu(plugin, player, plot, pos, category, 0, "").open();
                    } else {
                        plugin.chatPrompt().ask(player, "Введите часть названия предмета:", text ->
                                new MachineMenu(plugin, player, plot, pos, category, 0,
                                        text.equalsIgnoreCase("отмена") ? search : text).open());
                    }
                });
        set(49, Ui.back("Производство"), e -> new FactoryMenu(plugin, player, plot, null).open());
        if (current < pages - 1) {
            set(53, Ui.next(current, pages), e -> new MachineMenu(plugin, player, plot, pos, category, current + 1, search).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private static String names(RecipeBook.Ingredient ing) {
        if (ing.options().size() <= 3) {
            return String.join(" / ", ing.options().stream().map(Material::name).toList());
        }
        return ing.options().get(0).name() + " и ещё " + (ing.options().size() - 1) + " вариантов";
    }
}
