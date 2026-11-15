package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

public final class PlotMenu extends Menu {

    private final Plot plot;
    private final PlotListMenu.Mode back;

    public PlotMenu(BuildMasterPlugin plugin, Player player, Plot plot, PlotListMenu.Mode back) {
        super(plugin, player, "<dark_gray>Участок " + Text.esc(plot.id), 4);
        this.plot = plot;
        this.back = back;
        boolean owner = plot.isOwner(player.getUniqueId());
        set(4, Text.icon(Icons.statusMaterial(plot, owner), "<gold>Участок <white>" + Text.esc(plot.id),
                Icons.plotLore(plugin, plot)));

        if (plugin.service().isLocked(plot)) {
            set(13, Text.icon(Material.CLOCK, "<yellow>Участок обрабатывается", "<gray>Подождите несколько секунд"));
        } else if (plot.isFree()) {
            buildFree();
        } else if (owner) {
            buildOwner();
        }
        if (plugin.getConfig().getBoolean("allow-visit-teleport", true) || owner
                || player.hasPermission("buildmaster.admin")) {
            set(30, Text.icon(Material.ENDER_PEARL, "<light_purple>Телепортироваться"),
                    e -> {
                        player.closeInventory();
                        plugin.service().teleport(player, plot);
                    });
        }
        set(27, Text.icon(Material.ARROW, "<yellow>Назад"), e -> new PlotListMenu(plugin, player, back, 0).open());
        set(35, Text.icon(Material.BARRIER, "<red>Закрыть"), e -> player.closeInventory());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private void refresh() {
        new PlotMenu(plugin, player, plot, back).open();
    }

    private void buildFree() {
        if (plot.price >= 0) {
            set(11, Text.icon(Material.GOLD_INGOT, "<green>Купить",
                    "<gray>Цена: <white>" + plugin.economy().format(plot.price),
                    "<gray>Участок станет вашим навсегда", "<yellow>Нажмите для покупки"),
                    e -> {
                        plugin.service().buy(player, plot);
                        refresh();
                    });
        }
        if (plot.rentPerDay >= 0) {
            List<Integer> options = plugin.getConfig().getIntegerList("rent-day-options");
            int slot = 14;
            for (int days : options) {
                if (days < 1 || slot > 17) {
                    continue;
                }
                ItemStack icon = Text.icon(Material.CLOCK, "<aqua>Арендовать на " + days + " сут.",
                        "<gray>Стоимость: <white>" + plugin.economy().format(plot.rentPerDay * days),
                        "<yellow>Нажмите для аренды");
                icon.setAmount(Math.min(days, 64));
                set(slot++, icon, e -> {
                    plugin.service().rent(player, plot, days);
                    refresh();
                });
            }
        }
    }

    private void buildOwner() {
        set(10, Text.icon(Material.BRICKS, "<gold>Изменить здание",
                "<gray>Заменить материал фасада,", "<gray>крыши, стен и т.д."),
                e -> new ZoneMenu(plugin, player, plot, back).open());

        if (plot.status == Plot.Status.RENTED) {
            int slot = 12;
            for (int days : new int[] {1, 7, 30}) {
                ItemStack icon = Text.icon(Material.CLOCK, "<aqua>Продлить на " + days + " сут.",
                        "<gray>Стоимость: <white>" + plugin.economy().format(plot.rentPerDay * days));
                icon.setAmount(days);
                set(slot++, icon, e -> {
                    plugin.service().extend(player, plot, days);
                    refresh();
                });
            }
            set(16, Text.icon(plot.autoRenew ? Material.LIME_DYE : Material.GRAY_DYE,
                    "<white>Автопродление: " + (plot.autoRenew ? "<green>включено" : "<red>выключено"),
                    "<gray>Продлевается на " + plugin.getConfig().getInt("autorenew-days", 7) + " сут.",
                    "<gray>при окончании срока", "<yellow>Нажмите, чтобы переключить"),
                    e -> {
                        plugin.service().setAutoRenew(plot, !plot.autoRenew);
                        refresh();
                    });
        }

        List<String> names = new ArrayList<>();
        for (java.util.UUID id : plot.trusted) {
            OfflinePlayer p = Bukkit.getOfflinePlayer(id);
            names.add("<white>" + Text.esc(String.valueOf(p.getName())));
        }
        if (names.isEmpty()) {
            names.add("<gray>никого нет");
        }
        names.add("");
        names.add("<gray>/district trust <игрок>");
        set(19, Text.icon(Material.PLAYER_HEAD, "<aqua>Доверенные игроки", names.toArray(new String[0])));

        set(25, Text.icon(Material.RED_STAINED_GLASS, "<red>Отказаться от участка",
                "<gray>Предметы вернутся вам,", "<gray>а здание вернётся к исходному виду"),
                e -> new ConfirmMenu(plugin, player,
                        "Отказаться от участка " + plot.id + "?",
                        new String[] {"<gray>Все ваши постройки и изменения", "<gray>будут удалены, предметы вернутся."},
                        () -> {
                            plugin.service().release(player, plot);
                            player.closeInventory();
                        },
                        this::refresh).open());
    }
}
