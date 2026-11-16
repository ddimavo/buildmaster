package ru.buildmaster.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Request;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.PlotFilter;
import ru.buildmaster.service.PlotService;
import ru.buildmaster.service.RentRules;
import ru.buildmaster.util.Text;

/** Карточка участка: для свободного - покупка и аренда, для управляющего - настройки, для админа - решения. */
public final class PlotMenu extends Menu {

    private final Plot plot;
    private final PlotFilter back;

    public PlotMenu(BuildMasterPlugin plugin, Player player, Plot plot, PlotFilter back) {
        super(plugin, player, "<dark_gray>Участок " + Text.esc(plot.id), 5);
        this.plot = plot;
        this.back = back;
        boolean owner = plot.isOwner(player.getUniqueId());
        boolean admin = player.hasPermission("buildmaster.admin");
        set(4, Text.icon(Icons.statusMaterial(plot, owner), "<gold>" + Text.esc(plot.displayName()),
                Icons.plotLore(plugin, plot)));

        if (plugin.service().isLocked(plot)) {
            set(22, Text.icon(Material.CLOCK, "<yellow>Участок обрабатывается", "<gray>Подождите несколько секунд"));
        } else if (plot.isFree()) {
            buildFree();
        } else if (owner) {
            buildOwner();
        }
        if (admin && !plot.isFree() && !plugin.service().isLocked(plot)) {
            buildAdmin();
        }
        if (plot.category == ru.buildmaster.model.Category.HOTEL) {
            long free = plugin.hotels().freeRooms(plot);
            set(22, Text.icon(Material.RED_BED, "<gold>Номера отеля",
                    "<gray>Свободно: <white>" + free + "<gray> из <white>" + plugin.hotels().rooms(plot).size(),
                    "<yellow>Нажмите, чтобы посмотреть и снять"),
                    e -> new RoomsMenu(plugin, player, plot, back, 0).open());
        }
        if (plot.category == ru.buildmaster.model.Category.WORKSHOP && !plot.isFree()
                && !plugin.service().isLocked(plot)) {
            if (owner) {
                set(13, Text.icon(Material.BLAST_FURNACE, "<gold>Производство и улучшения",
                        "<gray>Линии, склад сырья, продукция", "<gray>Уровень мастерской: <white>" + plot.level),
                        e -> new FactoryMenu(plugin, player, plot, back).open());
                set(14, Text.icon(Material.WRITABLE_BOOK, "<gold>Прайс услуг",
                        "<gray>Включить услуги и назначить цены"),
                        e -> new OffersMenu(plugin, player, plot, back).open());
            }
            if (!owner) {
                set(22, Text.icon(Material.FURNACE, "<gold>Услуги мастерской",
                        "<gray>Переплавка, ремонт и другие услуги", "<yellow>Нажмите, чтобы посмотреть"),
                        e -> new ServicesMenu(plugin, player, plot, back).open());
            }
        }
        if (plugin.getConfig().getBoolean("allow-visit-teleport", true) || owner || admin) {
            set(39, Text.icon(Material.ENDER_PEARL, "<light_purple>Телепортироваться"),
                    e -> {
                        player.closeInventory();
                        plugin.service().teleport(player, plot);
                    });
        }
        set(36, Text.icon(Material.ARROW, "<yellow>Назад"), e -> new PlotListMenu(plugin, player, back, 0).open());
        set(44, Text.icon(Material.BARRIER, "<red>Закрыть"), e -> player.closeInventory());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private void refresh() {
        new PlotMenu(plugin, player, plot, back).open();
    }

    private void buildFree() {
        if (plot.price >= 0) {
            set(11, Text.icon(Material.GOLD_INGOT, "<green>Купить",
                    "<gray>Цена: <white>" + plugin.economy().format(plot.price),
                    "<gray>Вы станете управляющим участка,", "<gray>администрация остаётся главным владельцем",
                    "<yellow>Нажмите для покупки"),
                    e -> {
                        plugin.service().buy(player, plot);
                        refresh();
                    });
        }
        if (plot.rentPerDay >= 0) {
            int max = plugin.service().maxRentDays();
            int slot = 14;
            for (int days : plugin.getConfig().getIntegerList("rent-day-options")) {
                if (days < 1 || days > max || slot > 17) {
                    continue;
                }
                ItemStack icon = Text.icon(Material.CLOCK, "<aqua>Арендовать на " + days + " сут.",
                        "<gray>Стоимость: <white>" + plugin.economy().format(RentRules.cost(plot.rentPerDay, days)),
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
        if (!plot.isPlayerPlot()) {
            set(10, Text.icon(Material.BRICKS, "<gold>Изменить здание",
                    "<gray>Заменить материал фасада,", "<gray>крыши, стен и т.д."),
                    e -> new ZoneMenu(plugin, player, plot, back).open());
        }

        set(11, Text.icon(Material.NAME_TAG, "<aqua>Название",
                "<gray>Сейчас: <white>" + (plot.name == null ? "не задано" : Text.esc(plot.name)),
                "<gray>Публикуется после проверки", "<yellow>Нажмите, чтобы предложить"),
                e -> plugin.chatPrompt().ask(player, "Введите название (до "
                        + plugin.getConfig().getInt("moderation.max-name-length", 32) + " символов):",
                        text -> submit(Request.Type.NAME, text)));
        set(12, Text.icon(Material.WRITABLE_BOOK, "<aqua>Описание",
                "<gray>Сейчас: <white>" + (plot.description == null ? "не задано" : Text.esc(plot.description)),
                "<gray>Публикуется после проверки", "<yellow>Нажмите, чтобы предложить"),
                e -> plugin.chatPrompt().ask(player, "Введите описание (до "
                        + plugin.getConfig().getInt("moderation.max-description-length", 120) + " символов):",
                        text -> submit(Request.Type.DESCRIPTION, text)));

        set(15, Text.icon(plot.resetOnRelease ? Material.LIME_DYE : Material.GRAY_DYE,
                "<white>Сброс здания при отказе: " + (plot.resetOnRelease ? "<green>да" : "<red>нет"),
                "<gray>Да - здание вернётся к исходному виду.",
                "<gray>Нет - останется как есть (ваши вещи всё равно",
                "<gray>вернутся в хранилище). Администрация вправе", "<gray>сбросить здание в любой момент.",
                "<yellow>Нажмите, чтобы переключить"),
                e -> {
                    plugin.service().setResetOnRelease(plot, !plot.resetOnRelease);
                    refresh();
                });

        if (plot.status == Plot.Status.RENTED) {
            long now = System.currentTimeMillis();
            int allowed = RentRules.maxExtendDays(now, plot.rentedUntil, plugin.service().dayMillis(),
                    plugin.service().maxRentDays());
            int slot = 19;
            for (int days : new int[] {1, 7, 14}) {
                boolean ok = days <= allowed;
                ItemStack icon = Text.icon(ok ? Material.CLOCK : Material.GRAY_DYE,
                        (ok ? "<aqua>" : "<dark_gray>") + "Продлить на " + days + " сут.",
                        "<gray>Стоимость: <white>" + plugin.economy().format(RentRules.cost(plot.rentPerDay, days)),
                        ok ? "<yellow>Нажмите" : "<red>Больше " + plugin.service().maxRentDays() + " суток нельзя");
                icon.setAmount(days);
                set(slot++, icon, ok ? e -> {
                    plugin.service().extend(player, plot, days);
                    refresh();
                } : null);
            }
            set(23, Text.icon(plot.autoRenew ? Material.LIME_DYE : Material.GRAY_DYE,
                    "<white>Автопродление: " + (plot.autoRenew ? "<green>включено" : "<red>выключено"),
                    "<gray>Продлевается на " + plugin.getConfig().getInt("autorenew-days", 7) + " сут.",
                    "<gray>при окончании срока. Не хватит денег -",
                    "<gray>отсрочка " + plugin.getConfig().getInt("grace-days", 3) + " сут. и заморозка.",
                    "<yellow>Нажмите, чтобы переключить"),
                    e -> {
                        plugin.service().setAutoRenew(plot, !plot.autoRenew);
                        refresh();
                    });
        }

        set(25, Text.icon(Material.WRITABLE_BOOK, "<aqua>Права",
                "<gray>Игроков с правами: <white>" + plot.grants.size(),
                "<gray>Публичный доступ: <white>" + (plot.publicPerms.isEmpty() ? "нет"
                        : String.join(", ", plot.publicPerms.stream().map(Plot.Perm::title).toList())),
                "<yellow>Нажмите, чтобы настроить"),
                e -> new RightsMenu(plugin, player, plot, back).open());

        plugin.power().touch(plot);
        List<String> accountLore = new ArrayList<>();
        accountLore.add("<gray>Баланс: <white>" + plugin.economy().format(plugin.power().balance(plot)));
        if (plugin.power().isBilled(plot)) {
            if (plugin.power().mode() == ru.buildmaster.service.PowerService.Mode.USAGE) {
                accountLore.add("<gray>Электричество: <white>оплата по использованию станков");
                accountLore.add(plugin.power().isPowered(plot) ? "<gray>Станки работают" : "<red>На счёте нет средств");
            } else {
                accountLore.add("<gray>Тариф электричества: <white>" + plugin.economy().format(plot.account.ratePerDay()) + " / сутки");
                long left = plot.account.secondsLeft(plugin.power().nowSec());
                accountLore.add(plugin.power().isPowered(plot)
                        ? "<gray>Хватит на: <white>" + (left == Long.MAX_VALUE ? "без ограничения" : Text.duration(left * 1000L))
                        : "<red>Обесточено: станки не работают");
            }
            if (plot.discountPercent > 0) {
                accountLore.add("<green>Скидка: " + plot.discountPercent + "%");
            }
        }
        accountLore.add("<yellow>ЛКМ - пополнить, ПКМ - снять");
        set(24, Text.icon(Material.GOLD_NUGGET, "<gold>Счёт предприятия", accountLore.toArray(new String[0])), e -> {
            boolean withdraw = e.isRightClick();
            plugin.chatPrompt().ask(player, "Введите сумму, чтобы " + (withdraw ? "снять со счёта:" : "внести на счёт:"), text -> {
                if (!text.equalsIgnoreCase("отмена")) {
                    try {
                        double amount = Double.parseDouble(text.replace(',', '.'));
                        if (withdraw) {
                            plugin.power().withdraw(player, plot, amount);
                        } else {
                            plugin.power().deposit(player, plot, amount);
                        }
                    } catch (NumberFormatException ex) {
                        Text.err(player, "Нужно число.");
                    }
                }
                refresh();
            });
        });

        set(40, Text.icon(Material.BELL, "<gold>Журнал предупреждений",
                "<gray>Непрочитанных: <white>" + plugin.warnings().unread(player.getUniqueId())),
                e -> new WarningsMenu(plugin, player, 0).open());

        set(31, Text.icon(Material.RED_STAINED_GLASS, "<red>Отказаться от участка",
                "<gray>Ваши вещи вернутся в хранилище"),
                e -> new ConfirmMenu(plugin, player,
                        "Отказаться от участка " + plot.id + "?",
                        new String[] {"<gray>Ваши вещи вернутся в хранилище.",
                                plot.resetOnRelease ? "<gray>Здание вернётся к исходному виду." : "<gray>Здание останется как есть."},
                        () -> {
                            plugin.service().release(player, plot);
                            player.closeInventory();
                        },
                        this::refresh).open());
    }

    private void submit(Request.Type type, String text) {
        if (!text.equalsIgnoreCase("отмена")) {
            String error = plugin.moderation().submit(player, plot, type, text);
            if (error != null) {
                Text.err(player, error);
            }
        }
        refresh();
    }

    /** Решения администратора: каждое требует причину. */
    private void buildAdmin() {
        set(29, Text.icon(Material.TNT, "<red>Сбросить здание",
                "<gray>Здание вернётся к исходному виду,", "<gray>вещи игроков - в их хранилища",
                "<yellow>Потребуется причина"),
                e -> askReason("Причина сброса здания:", reason -> {
                    String error = plugin.service().reset(plot, player.getName(), reason);
                    if (error != null) {
                        Text.err(player, error);
                    }
                }));
        set(30, Text.icon(Material.IRON_DOOR, "<red>Выселить",
                "<gray>Участок освобождается, вещи - в хранилище", "<yellow>Потребуется причина"),
                e -> askReason("Причина выселения:", reason -> plugin.service().evict(plot,
                        new PlotService.Release(player.getName(), reason, Journal.Action.EVICTED, false,
                                Plot.RestoreMode.FULL))));
        set(32, Text.icon(Material.IRON_BARS, "<dark_red>Изъять (арест вещей)",
                "<gray>Участок отбирается, вещи арестовываются", "<gray>до вашего решения", "<yellow>Потребуется причина"),
                e -> askReason("Причина изъятия участка:", reason -> plugin.service().evict(plot,
                        new PlotService.Release(player.getName(), reason, Journal.Action.SEIZED, true,
                                Plot.RestoreMode.FULL))));
    }

    private void askReason(String prompt, java.util.function.Consumer<String> action) {
        plugin.chatPrompt().ask(player, prompt + " (обязательно)", reason -> {
            if (reason.isBlank() || reason.equalsIgnoreCase("отмена")) {
                Text.err(player, "Действие отменено: причина обязательна.");
            } else {
                action.accept(reason);
            }
            player.closeInventory();
        });
    }
}
