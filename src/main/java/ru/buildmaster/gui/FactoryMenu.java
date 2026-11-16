package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.PlotFilter;
import ru.buildmaster.service.ProductionCatalog;
import ru.buildmaster.service.UpgradeCatalog;
import ru.buildmaster.util.Text;

/** Производство и улучшения мастерской (для управляющего). */
public final class FactoryMenu extends Menu {

    public FactoryMenu(BuildMasterPlugin plugin, Player player, Plot plot, PlotFilter back) {
        super(plugin, player, "<dark_gray>Производство · " + Text.esc(plot.displayName()), 4);
        UpgradeCatalog.Level level = plugin.upgrades().of(plot);
        UpgradeCatalog.Level next = plugin.upgrades().next(plot);

        List<String> head = new ArrayList<>();
        head.add("<gray>Уровень: <white>" + plot.level + "<gray>, линий: <white>" + plot.lines.size() + "/" + level.maxLines());
        head.add("<gray>Скорость ×<white>" + level.speed() + "<gray>, склад ×<white>" + level.stock() + "<gray>, буфер ×<white>" + level.buffer());
        if (next == null) {
            head.add("<green>Максимальный уровень");
        } else {
            head.add("<yellow>Следующий уровень " + next.level() + ": " + plugin.economy().format(next.price()));
            head.add("<gray>Линий " + next.maxLines() + ", скорость ×" + next.speed() + ", склад ×" + next.stock()
                    + ", буфер ×" + next.buffer() + ", очередь заказов +" + next.orderQueue());
            head.add("<yellow>Нажмите, чтобы улучшить (оплата со счёта предприятия)");
        }
        set(4, Text.icon(Material.NETHER_STAR, "<gold>Улучшения мастерской", head.toArray(new String[0])), e -> {
            String error = plugin.factory().upgrade(player, plot);
            if (error != null) {
                Text.err(player, error);
            }
            new FactoryMenu(plugin, player, plot, back).open();
        });

        int slot = 10;
        for (ProductionCatalog.Line def : plugin.production().lines()) {
            if (slot == 17) {
                slot = 19;
            }
            if (slot > 25) {
                break;
            }
            Plot.Line line = plot.lines.get(def.id());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + def.inputAmount() + " × " + def.inputNames() + " → " + def.outputAmount() + " × " + def.output().name());
            lore.add("<gray>Станок: <white>" + def.station() + "<gray> (стоит: " + plugin.stations().countByTariffKey(plot, def.station()) + ")");
            if (line == null) {
                lore.add("<gray>Установка: <white>" + plugin.economy().format(def.installPrice()));
                lore.add("");
                lore.add("<yellow>Нажмите, чтобы установить");
                set(slot++, Text.icon(Material.GRAY_DYE, "<gray>" + Text.esc(def.title()) + " (не установлена)", lore.toArray(new String[0])),
                        e -> {
                            String error = plugin.factory().install(player, plot, def.id());
                            if (error != null) {
                                Text.err(player, error);
                            }
                            new FactoryMenu(plugin, player, plot, back).open();
                        });
            } else {
                double eff = plugin.factory().secondsPerBatch(plot, def);
                lore.add("<gray>Сырьё: <white>" + line.input + "/" + plugin.factory().stockCapacity(plot));
                lore.add("<gray>Продукция: <white>" + line.output + "/" + plugin.factory().bufferCapacity(plot, def));
                lore.add("<gray>Партия: <white>" + (Double.isInfinite(eff) ? "нет станка" : Math.round(eff * 10) / 10.0 + " с"));
                lore.add(plugin.power().isPowered(plot) ? "<green>Работает при наличии сырья" : "<red>Нет электричества: линия стоит");
                lore.add("");
                lore.add("<yellow>ЛКМ - загрузить сырьё из инвентаря");
                lore.add("<yellow>ПКМ - забрать продукцию");
                lore.add("<red>Shift+ЛКМ - снять линию");
                set(slot++, Text.icon(ServicesMenu.iconFor(def.output()), "<gold>" + Text.esc(def.title()), lore.toArray(new String[0])),
                        e -> {
                            String error;
                            if (e.isShiftClick() && e.isLeftClick()) {
                                error = plugin.factory().remove(player, plot, def.id());
                            } else if (e.isRightClick()) {
                                error = plugin.factory().collect(player, plot, def.id());
                            } else {
                                error = plugin.factory().load(player, plot, def.id(), 0);
                            }
                            if (error != null) {
                                Text.err(player, error);
                            }
                            new FactoryMenu(plugin, player, plot, back).open();
                        });
            }
        }
        set(27, Ui.back(null), e -> new PlotMenu(plugin, player, plot, back).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
