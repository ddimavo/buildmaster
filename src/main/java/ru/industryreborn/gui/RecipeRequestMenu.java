package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.items.ItemRef;
import ru.industryreborn.items.RecipeRequests;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.util.Text;

/**
 * Конструктор заявки на рецепт станка. Станок и время выбираются кнопками; предметы - положите предмет на курсор и нажмите ячейку
 * либо нажмите пустую ячейку, а затем предмет в своём инвентаре (предметы не забираются, это образцы). ЛКМ/ПКМ по заполненной ячейке меняет количество,
 * Shift - на 8, Q - очистить.
 */
public final class RecipeRequestMenu extends Menu {

    /** Черновик заявки: живёт, пока игрок не закроет конструктор. */
    public static final class Draft {
        String station = "furnace";
        double seconds = 10;
        final List<RecipeRequests.Part> inputs = new ArrayList<>();
        RecipeRequests.Part output;
        /** Выбранная ячейка: 0-3 - входы, 4 - результат, -1 - не выбрана. */
        int selected = -1;
    }

    private static final int[] INPUT_SLOTS = {28, 29, 30, 31};
    private static final int OUTPUT_SLOT = 34;

    private final Plot plot;
    private final Runnable back;
    private final Draft draft;

    public RecipeRequestMenu(IndustryRebornPlugin plugin, Player player, Plot plot, Runnable back, Draft existing) {
        super(plugin, player, "<dark_gray>Район › " + Text.esc(plot.displayName()) + " › Новый рецепт", 6);
        this.plot = plot;
        this.back = back;
        this.draft = existing == null ? new Draft() : existing;
        var service = plugin.recipeRequests();

        set(4, Text.icon(Material.CRAFTING_TABLE, "<gold>Заявка на рецепт станка",
                "<gray>Мастерская: <white>" + Text.esc(plot.displayName()),
                "<gray>Входы и результат - образцы предметов:", "<gray>положите предмет на курсор и нажмите ячейку,",
                "<gray>либо нажмите пустую ячейку, затем предмет в инвентаре.",
                "<gray>Заявок на рассмотрении: <white>" + service.pending(plot.id).size()));

        int stationIndex = Math.max(0, RecipeRequests.stations().indexOf(draft.station));
        set(10, Text.icon(Material.FURNACE, "<aqua>Станок: <white>" + RecipeBook.familyTitle(draft.station),
                "<yellow>ЛКМ - следующий, ПКМ - предыдущий"), e -> {
                    int n = RecipeRequests.stations().size();
                    draft.station = RecipeRequests.stations().get(Math.floorMod(stationIndex + (e.isRightClick() ? -1 : 1), n));
                    reopen();
                });
        set(12, Text.icon(Material.CLOCK, "<aqua>Время партии: <white>" + draft.seconds + " с",
                "<yellow>ЛКМ +5 с, ПКМ -5 с", "<yellow>Shift+ЛКМ +30 с, Shift+ПКМ -30 с"), e -> {
                    double step = e.isShiftClick() ? 30 : 5;
                    draft.seconds = Math.max(1, Math.min(3600, draft.seconds + (e.isRightClick() ? -step : step)));
                    reopen();
                });

        for (int i = 0; i < 4; i++) {
            int index = i;
            RecipeRequests.Part part = i < draft.inputs.size() ? draft.inputs.get(i) : null;
            set(INPUT_SLOTS[i], partIcon(part, "Вход " + (i + 1), draft.selected == i), e -> slotClick(e, index));
        }
        set(32, Text.icon(Material.ARROW, "<white>→"));
        set(OUTPUT_SLOT, partIcon(draft.output, "Результат", draft.selected == 4), e -> slotClick(e, 4));

        // ---- проверка и отправка ----
        String error = check();
        boolean ready = error == null;
        List<String> status = new ArrayList<>();
        if (ready) {
            status.add("<green>Рецепт готов к отправке:");
            status.add("<white>" + Text.esc(service.describe(spec())));
            status.add("<gray>Дальше его проверит администратор.");
        } else {
            status.add("<red>" + Text.esc(error));
        }
        set(16, Text.icon(ready ? Material.LIME_DYE : Material.BARRIER, ready ? "<green>Отправить заявку" : "<red>Рецепт не готов",
                status.toArray(new String[0])), e -> {
                    if (!ready) {
                        return;
                    }
                    String result = service.submit(player, plot, spec());
                    if (result == null) {
                        Text.ok(player, "Заявка отправлена администратору. Статус: /district recipe requests");
                        player.closeInventory();
                    } else if (result.isEmpty()) {
                        Text.ok(player, "Рецепт создан и изучен мастерской " + plot.id + ".");
                        player.closeInventory();
                    } else {
                        Text.err(player, result);
                    }
                });
        set(25, Text.icon(Material.BARREL, "<gray>Очистить всё"), e -> {
            draft.inputs.clear();
            draft.output = null;
            draft.selected = -1;
            reopen();
        });
        navBar(back == null ? null : "Назад", back, 0, 1, p -> { });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    // ------------------------------------------------------------ черновик

    private RecipeRequests.Spec spec() {
        return new RecipeRequests.Spec(draft.station, draft.seconds, List.copyOf(draft.inputs), draft.output);
    }

    private String check() {
        if (draft.inputs.isEmpty()) {
            return "Укажите хотя бы один вход.";
        }
        if (draft.output == null) {
            return "Укажите результат.";
        }
        return plugin.recipeRequests().validate(spec());
    }

    private ItemStack partIcon(RecipeRequests.Part part, String title, boolean selected) {
        if (part == null) {
            return Text.icon(selected ? Material.LIME_STAINED_GLASS_PANE : Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>" + title + ": пусто",
                    selected ? "<green>Выбрано: теперь нажмите предмет в своём инвентаре" : "<yellow>Нажмите, чтобы выбрать предмет",
                    "<gray>Или нажмите с предметом на курсоре");
        }
        ItemStack icon = Icons.goods(plugin, part.key());
        icon.setAmount(Math.max(1, Math.min(64, part.amount())));
        icon.editMeta(meta -> {
            meta.displayName(Text.item("<gold><bold>" + Text.esc(plugin.recipeRequests().partText(part))));
            meta.lore(ru.industryreborn.util.Style.lore(new String[] {"<gray>" + title,
                    "<yellow>ЛКМ +1, ПКМ -1 (Shift - по 8)", "<yellow>Q - убрать", selected ? "<green>Выбрано: нажмите предмет в инвентаре" : "<yellow>Нажмите с предметом на курсоре - заменить"})
                    .stream().map(Text::item).toList());
        });
        return icon;
    }

    private void reopen() {
        new RecipeRequestMenu(plugin, player, plot, back, draft).open();
    }

    private static RecipeRequests.Part partOf(IndustryRebornPlugin plugin, ItemStack stack, int amount) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        ItemRef ref = plugin.customItems().identify(stack);
        return new RecipeRequests.Part(ref != null ? ref.key() : stack.getType().name(), amount);
    }

    private void assign(int index, RecipeRequests.Part part) {
        if (part == null) {
            return;
        }
        if (index == 4) {
            draft.output = part;
        } else if (index < draft.inputs.size()) {
            draft.inputs.set(index, part);
        } else if (draft.inputs.size() < 4) {
            draft.inputs.add(part);
        }
    }

    private void slotClick(InventoryClickEvent e, int index) {
        RecipeRequests.Part current = index == 4 ? draft.output : index < draft.inputs.size() ? draft.inputs.get(index) : null;
        ItemStack cursor = e.getCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            assign(index, partOf(plugin, cursor, current == null ? 1 : current.amount()));
            draft.selected = -1;
        } else if (current == null || e.getClick() == ClickType.MIDDLE) {
            draft.selected = index;
        } else if (e.getClick() == ClickType.DROP || e.getClick() == ClickType.CONTROL_DROP) {
            if (index == 4) {
                draft.output = null;
            } else {
                draft.inputs.remove(index);
            }
        } else {
            int step = e.isShiftClick() ? 8 : 1;
            int amount = current.amount() + (e.isRightClick() ? -step : step);
            if (amount < 1) {
                if (index == 4) {
                    draft.output = null;
                } else {
                    draft.inputs.remove(index);
                }
            } else {
                RecipeRequests.Part updated = new RecipeRequests.Part(current.key(), Math.min(64, amount));
                if (index == 4) {
                    draft.output = updated;
                } else {
                    draft.inputs.set(index, updated);
                }
            }
        }
        reopen();
    }

    @Override
    public void handleBottomClick(InventoryClickEvent event) {
        if (draft.selected < 0) {
            return;
        }
        ItemStack clicked = event.getClickedInventory() == null ? null : event.getClickedInventory().getItem(event.getSlot());
        RecipeRequests.Part part = partOf(plugin, clicked, 1);
        if (part == null) {
            return;
        }
        assign(draft.selected, part);
        draft.selected = -1;
        reopen();
    }
}
