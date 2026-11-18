package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.service.ProductionService;
import ru.industryreborn.service.UpgradeCatalog;
import ru.industryreborn.util.Style;
import ru.industryreborn.util.Text;

/** Производство мастерской: склад, сундуки, улучшения и список станков с заданиями. */
public final class FactoryMenu extends Menu {

    public FactoryMenu(IndustryRebornPlugin plugin, Player player, Plot plot, PlotFilter back) {
        super(plugin, player, "<dark_gray>Район › Участки › " + Text.esc(plot.displayName()) + " › Производство", 5);
        var f = plugin.factory();
        UpgradeCatalog.Level level = plugin.upgrades().of(plot);
        UpgradeCatalog.Level next = plugin.upgrades().next(plot);

        // ---- сводка ----
        List<ProductionService.View> views = f.views(plot);
        long working = views.stream().filter(v -> v.status() == ProductionService.Status.WORKING).count();
        set(4, Text.icon(Material.BLAST_FURNACE, "<gold>Производство",
                "<gray>Станков с заданием: <white>" + f.assignedCount(plot) + " из " + f.slots(plot),
                "<gray>Сейчас работают: <white>" + working,
                "<gray>Электричество: " + (plugin.power().isPowered(plot) ? "<green>есть" : "<red>нет")));

        // ---- улучшения ----
        List<String> up = new ArrayList<>();
        up.add("<gray>Уровень: <white>" + plot.level);
        up.add("<gray>Скорость ×<white>" + level.speed() + "<gray>, склад ×<white>" + level.stock() + "<gray>, буфер ×<white>" + level.buffer());
        if (next == null) {
            up.add("<green>Максимальный уровень");
        } else {
            up.add("<yellow>Следующий уровень " + next.level() + ": " + plugin.economy().format(next.price()));
            up.add("<gray>Станков с заданием " + next.maxLines() + ", скорость ×" + next.speed() + ", склад ×" + next.stock()
                    + ", буфер ×" + next.buffer() + ", очередь заказов +" + next.orderQueue());
            up.add("<yellow>Нажмите, чтобы улучшить (оплата со счёта предприятия)");
        }
        set(10, Text.icon(Material.NETHER_STAR, "<gold>Улучшения мастерской", up.toArray(new String[0])), e -> {
            String error = f.upgrade(player, plot);
            if (error != null) {
                Text.err(player, error);
            }
            new FactoryMenu(plugin, player, plot, back).open();
        });

        // ---- склад сырья ----
        List<String> stock = new ArrayList<>();
        stock.add("<gray>Занято: <white>" + f.stockTotal(plot) + " из " + f.stockCapacity(plot));
        stock.add(Style.bar((double) f.stockTotal(plot) / Math.max(1, f.stockCapacity(plot)), 12, "aqua"));
        int shown = 0;
        for (Map.Entry<Material, Integer> e : plot.stock.entrySet()) {
            if (shown++ >= 8) {
                stock.add("<gray>... и ещё " + (plot.stock.size() - 8));
                break;
            }
            stock.add("<gray>" + e.getKey().name() + ": <white>" + e.getValue());
        }
        if (plot.stock.isEmpty()) {
            stock.add("<gray>Склад пуст: положите сырьё в сундук сырья");
        }
        stock.add("<yellow>ЛКМ - загрузить сырьё из инвентаря");
        set(12, Text.icon(Material.BARREL, "<aqua>Склад сырья", stock.toArray(new String[0])), e -> {
            String error = f.load(player, plot, 0);
            if (error != null) {
                Text.err(player, error);
            }
            new FactoryMenu(plugin, player, plot, back).open();
        });

        // ---- продукция ----
        int product = views.stream().mapToInt(ProductionService.View::output).sum();
        set(14, Text.icon(Material.CHEST_MINECART, "<green>Продукция в буферах станков",
                "<gray>Ждёт выгрузки: <white>" + product,
                "<gray>Сундуки продукции забирают её сами",
                "<yellow>ЛКМ - забрать всё в инвентарь"), e -> {
            String error = f.collect(player, plot);
            if (error != null) {
                Text.err(player, error);
            }
            new FactoryMenu(plugin, player, plot, back).open();
        });

        // ---- сундуки ----
        set(16, Text.icon(Material.CHEST, "<white>Сундуки мастерской",
                "<gray>Сундуков сырья: <white>" + plot.rawChests.size(),
                "<gray>Сундуков продукции: <white>" + plot.productChests.size(),
                "<gray>Посмотрите на сундук и выполните:",
                "<white>/district chest raw <gray>- сырьё",
                "<white>/district chest product <gray>- продукция",
                "<white>/district chest unmark <gray>- снять"));

        // ---- станки ----
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (ProductionService.View v : views) {
            if (i >= slots.length) {
                break;
            }
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Позиция: <white>" + v.pos());
            if (v.recipe() == null) {
                lore.add("<gray>Задание: <dark_gray>не назначено");
                lore.add("<gray>Обслуживает заказы игроков");
            } else {
                lore.add("<gray>Делает: <white>" + v.recipe().output().name() + " × " + v.recipe().outputAmount());
                lore.add("<gray>Статус: " + (v.status() == ProductionService.Status.WORKING ? "<green>" : "<red>") + v.status().title());
                if (v.status() == ProductionService.Status.WORKING) {
                    lore.add(Style.bar(v.ratio(), 12, "green"));
                }
                lore.add("<gray>Продукция: <white>" + v.output() + " из " + v.bufferCapacity());
            }
            lore.add("<yellow>Нажмите, чтобы настроить");
            Material icon = v.block() == Material.AIR ? Material.FURNACE : v.block();
            String pos = v.pos();
            set(slots[i++], Text.icon(icon, "<gold>" + Text.esc(blockTitle(icon)), lore.toArray(new String[0])),
                    e -> new MachineMenu(plugin, player, plot, pos, null, 0, "").open());
        }
        if (views.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>В мастерской нет рабочих станков",
                    "<gray>Поставьте печь или наковальню: установка платная"));
        }
        set(36, Ui.back(null), e -> new PlotMenu(plugin, player, plot, back).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    static String blockTitle(Material m) {
        return switch (m) {
            case FURNACE -> "Печь";
            case BLAST_FURNACE -> "Плавильная печь";
            case SMOKER -> "Коптильня";
            case BREWING_STAND -> "Варочная стойка";
            case ENCHANTING_TABLE -> "Стол зачарований";
            default -> m.name().endsWith("ANVIL") ? "Наковальня" : m.name();
        };
    }
}
