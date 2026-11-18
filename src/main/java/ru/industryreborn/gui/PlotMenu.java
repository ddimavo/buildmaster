package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Request;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.service.PlotService;
import ru.industryreborn.service.RentRules;
import ru.industryreborn.util.Text;

/**
 * Карточка участка. Шапка (слот 4) - сведения об участке; ниже группы кнопок с цветными заголовками:
 * для свободного - ПОКУПКА И АРЕНДА, для управляющего - АРЕНДА, ПРЕДПРИЯТИЕ, ВИД И ДОСТУП и ОПАСНАЯ ЗОНА, для администратора - АДМИНИСТРАЦИЯ.
 * Внизу: назад (к списку), телепорт, главное меню, закрыть.
 */
public final class PlotMenu extends Menu {

    private final Plot plot;
    private final PlotFilter back;

    public PlotMenu(IndustryRebornPlugin plugin, Player player, Plot plot, PlotFilter back) {
        super(plugin, player, "<dark_gray>Район › " + (back != null && back.mode == PlotFilter.Mode.FREE ? "Свободные участки" : "Участки")
                + " › " + Text.esc(plot.displayName()), 6);
        this.plot = plot;
        this.back = back;
        boolean owner = plot.isOwner(player.getUniqueId());
        boolean admin = player.hasPermission("industryreborn.admin");
        set(4, Text.icon(Icons.categoryMaterial(plot.category), Icons.dot(plugin, plot, owner) + "<gold>" + Text.esc(plot.displayName()),
                Icons.plotLore(plugin, plot)));

        if (plugin.service().isLocked(plot)) {
            set(22, Text.icon(Material.CLOCK, "<yellow>Участок обрабатывается", "<gray>Подождите несколько секунд"));
        } else if (plot.isFree()) {
            buildFree();
        } else if (owner) {
            buildOwner(admin);
        } else {
            buildVisitor();
        }
        if (admin && !plot.isFree() && !plugin.service().isLocked(plot)) {
            buildAdmin();
        }
        if (plot.category == Category.ROOM && (admin || plugin.hotels().hotelOf(plot) != null && plugin.hotels().hotelOf(plot).isOwner(player.getUniqueId()))) {
            set(51, Text.icon(plot.roomClass.icon(), "<gold>Класс номера", "<gray>Сейчас: " + plot.roomClass.badge(),
                    admin ? "<yellow>Нажмите: назначить класс" : "<yellow>Нажмите: подать заявку на смену"),
                    e -> new RoomClassMenu(plugin, player, plot, () -> new PlotMenu(plugin, player, plot, back)).open());
        }
        if (plugin.getConfig().getBoolean("allow-visit-teleport", true) || owner || admin) {
            set(47, Text.icon(Material.ENDER_PEARL, "<light_purple>Телепорт", "<yellow>Нажмите, чтобы перенестись к участку"), e -> {
                player.closeInventory();
                plugin.service().teleport(player, plot);
            });
        }
        navBar(back == null || back.mode == PlotFilter.Mode.FREE ? "Список участков" : "Мои участки",
                () -> new PlotListMenu(plugin, player, back == null ? new PlotFilter(PlotFilter.Mode.MINE) : back, 0).open(), 0, 1, i -> { });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private void refresh() {
        new PlotMenu(plugin, player, plot, back).open();
    }

    private void reopenList() {
        new PlotListMenu(plugin, player, back == null ? new PlotFilter(PlotFilter.Mode.MINE) : back, 0).open();
    }

    // ------------------------------------------------------------ свободный участок

    private void buildFree() {
        set(9, Ui.section("Покупка и аренда", Material.LIGHT_BLUE_STAINED_GLASS_PANE));
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
            int max = plugin.service().maxRentDays(plot);
            int slot = 13;
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
        if (plot.category == Category.HOTEL) {
            set(18, Ui.section("Отель", Material.ORANGE_STAINED_GLASS_PANE));
            rooms(20);
        }
        if (plugin.lots().isLot(plot)) {
            var lot = plugin.lots().get(plot);
            set(22, Text.icon(Material.GOLD_BLOCK, "<gold>Выставлен на аукцион",
                    "<gray>" + (lot.hasBids() ? "Последняя ставка: <white>" + plugin.economy().format(lot.bid) : "Старт: <white>" + plugin.economy().format(lot.start)),
                    "<gray>Следующая ставка: <white>" + plugin.economy().format(lot.minNext()),
                    "<yellow>Нажмите: аукционы участков"), e -> {
                        player.closeInventory();
                        player.performCommand("district lot menu");
                    });
        }
    }

    private void rooms(int slot) {
        long free = plugin.hotels().freeRooms(plot);
        set(slot, Text.icon(Material.RED_BED, "<gold>Номера отеля",
                "<gray>Свободно: <white>" + free + "<gray> из <white>" + plugin.hotels().rooms(plot).size(),
                "<yellow>Нажмите, чтобы посмотреть и снять"),
                e -> new RoomsMenu(plugin, player, plot, back, 0).open());
    }

    // ------------------------------------------------------------ чужой занятый участок

    private void buildVisitor() {
        set(18, Ui.section("Предприятие", Material.ORANGE_STAINED_GLASS_PANE));
        int slot = 20;
        if (plot.category == Category.HOTEL) {
            rooms(slot++);
        }
        if (plot.category == Category.WORKSHOP) {
            set(slot++, Text.icon(Material.FURNACE, "<gold>Услуги мастерской",
                    "<gray>Переплавка, ремонт и другие услуги", "<yellow>Нажмите, чтобы посмотреть"),
                    e -> new ServicesMenu(plugin, player, plot, back).open());
        }
        if (plugin.goods().holdsGoods(plot) && plugin.goods().sells(plot)) {
            set(slot++, Text.icon(Material.EMERALD, "<green>Витрина",
                    "<gray>Товаров в продаже: <white>" + plugin.goods().listed(plot).size(),
                    "<yellow>Нажмите, чтобы посмотреть и купить"), e -> new StorefrontMenu(plugin, player, plot, back, 0).open());
        }
    }

    // ------------------------------------------------------------ управляющий

    /** Окно продления аренды на любой срок (степпер с пересчётом цены). */
    static void extendDialog(IndustryRebornPlugin plugin, Player player, Plot plot, Runnable after) {
        AccountDialogs.extend(plugin, player, plot, after);
    }

    private void buildOwner(boolean admin) {
        boolean rented = plot.status == Plot.Status.RENTED;
        if (rented) {
            long now = System.currentTimeMillis();
            int allowed = RentRules.maxExtendDays(now, plot.rentedUntil, plugin.service().dayMillis(), plugin.service().maxRentDays(plot));
            set(9, Ui.section("Аренда", Material.LIGHT_BLUE_STAINED_GLASS_PANE));
            int slot = 11;
            for (int days : new int[] {1, 7, 14}) {
                boolean ok = days <= allowed;
                ItemStack icon = Text.icon(ok ? Material.CLOCK : Material.GRAY_DYE,
                        (ok ? "<aqua>" : "<dark_gray>") + "Продлить на " + days + " сут.",
                        "<gray>Стоимость: <white>" + plugin.economy().format(RentRules.cost(plot.rentPerDay, days)),
                        ok ? "<yellow>Нажмите" : "<red>Больше " + plugin.service().maxRentDays(plot) + " суток нельзя");
                icon.setAmount(days);
                set(slot++, icon, ok ? e -> {
                    plugin.service().extend(player, plot, days);
                    refresh();
                } : null);
            }
            set(14, Text.icon(plot.autoRenew ? Material.LIME_DYE : Material.GRAY_DYE,
                    "<white>Автопродление: " + (plot.autoRenew ? "<green>включено" : "<red>выключено"),
                    "<gray>Продлевается на " + plugin.getConfig().getInt("autorenew-days", 7) + " сут.",
                    "<gray>при окончании срока. Не хватит денег -",
                    "<gray>отсрочка " + plugin.getConfig().getInt("grace-days", 3) + " сут. и заморозка.",
                    "<yellow>Нажмите, чтобы переключить"),
                    e -> {
                        plugin.service().setAutoRenew(plot, !plot.autoRenew);
                        refresh();
                    });
            set(15, Text.icon(Material.DIAMOND, "<aqua>Другой срок…",
                    "<gray>Окно с ползунком: от 1 до <white>" + Math.max(1, allowed) + "<gray> сут.", "<yellow>Нажмите, чтобы выбрать"),
                    e -> extendDialog(plugin, player, plot, this::refresh));
        }

        // ---- предприятие ----
        set(18, Ui.section("Предприятие", Material.ORANGE_STAINED_GLASS_PANE));
        int slot = 20;
        plugin.power().touch(plot);
        List<String> accountLore = new ArrayList<>();
        accountLore.add("<gray>Баланс: <white>" + plugin.economy().format(plugin.power().balance(plot)));
        if (plugin.power().isBilled(plot)) {
            if (plugin.power().mode() == ru.industryreborn.service.PowerService.Mode.USAGE) {
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
        set(slot++, Text.icon(Material.GOLD_NUGGET, "<gold>Счёт предприятия", accountLore.toArray(new String[0])), e -> {
            AccountDialogs.money(plugin, player, plot, e.isRightClick(), this::refresh);
        });
        if (plugin.jobs().enabled() && plugin.jobs().categoryAllowed(plot.category)) {
            set(slot++, Text.icon(Material.VILLAGER_SPAWN_EGG, "<gold>Работники",
                    "<gray>Работают: <white>" + plugin.jobs().employeesOf(plot.id).size(),
                    "<gray>Откликов: <white>" + plugin.jobs().applicationsFor(plot.id).size(),
                    "<yellow>Вакансии, наём, зарплаты"),
                    e -> new StaffMenu(plugin, player, plot, back, StaffMenu.Tab.VACANCIES).open());
        }
        if (plot.category == Category.WORKSHOP) {
            set(slot++, Text.icon(Material.BLAST_FURNACE, "<gold>Производство и улучшения",
                    "<gray>Линии, склад сырья, продукция", "<gray>Уровень мастерской: <white>" + plot.level),
                    e -> new FactoryMenu(plugin, player, plot, back).open());
            set(slot++, Text.icon(Material.WRITABLE_BOOK, "<gold>Прайс услуг",
                    "<gray>Включить услуги и назначить цены"), e -> new OffersMenu(plugin, player, plot, back).open());
        }
        if (plugin.goods().holdsGoods(plot)) {
            set(slot++, Text.icon(Material.BARREL, "<gold>Товары и связи",
                    "<gray>Склад товаров: <white>" + plugin.goods().total(plot) + " из " + plugin.goods().capacity(plot),
                    "<gray>Цены, витрина, связи с мастерскими и магазинами",
                    "<yellow>Нажмите, чтобы открыть"), e -> new GoodsMenu(plugin, player, plot, back, 0).open());
        }
        if (plot.category == Category.HOTEL && slot <= 26) {
            rooms(slot++);
        }
        if (slot <= 26 && plugin.buildings().playerBuildEnabled() && plugin.buildings().plotError(player, plot) == null) {
            set(slot++, Text.icon(Material.BRICKS, "<aqua>Заказать здание",
                    "<gray>Цена блока: <white>" + plugin.economy().format(plugin.buildings().pricePerBlock(plot.category)),
                    "<gray>Участок должен быть пустым",
                    "<yellow>Нажмите: выбрать здание"), e -> {
                        player.closeInventory();
                        player.performCommand("district build menu " + plot.id);
                    });
        }

        // ---- вид и доступ ----
        set(27, Ui.section("Вид и доступ", Material.CYAN_STAINED_GLASS_PANE));
        set(29, Text.icon(Material.NAME_TAG, "<aqua>Название",
                "<gray>Сейчас: <white>" + (plot.name == null ? "не задано" : Text.esc(plot.name)),
                "<gray>Публикуется после проверки", "<yellow>Нажмите, чтобы предложить"),
                e -> plugin.dialogs().text(player, "Название участка",
                        List.of("<gray>Название появится после проверки администратором"),
                        "Название (до " + plugin.getConfig().getInt("moderation.max-name-length", 32) + " символов)", plot.name,
                        plugin.getConfig().getInt("moderation.max-name-length", 32), text -> {
                            submit(Request.Type.NAME, text);
                        }, this::refresh));
        set(30, Text.icon(Material.WRITABLE_BOOK, "<aqua>Описание",
                "<gray>Сейчас: <white>" + (plot.description == null ? "не задано" : Text.esc(plot.description)),
                "<gray>Публикуется после проверки", "<yellow>Нажмите, чтобы предложить"),
                e -> plugin.dialogs().text(player, "Описание участка",
                        List.of("<gray>Описание появится после проверки администратором"),
                        "Описание (до " + plugin.getConfig().getInt("moderation.max-description-length", 120) + " символов)", plot.description,
                        plugin.getConfig().getInt("moderation.max-description-length", 120), text -> {
                            submit(Request.Type.DESCRIPTION, text);
                        }, this::refresh));
        set(31, Text.icon(Material.ENDER_EYE, "<aqua>Позиция голограммы",
                "<gray>Голограмма появится там, где вы стоите", "<gray>Публикуется после проверки",
                "<yellow>Встаньте на место и нажмите"), e -> {
            player.closeInventory();
            String error = plugin.moderation().submitHolo(player, plot, player.getEyeLocation());
            if (error != null) {
                Text.err(player, error);
            }
        });
        set(32, Text.icon(Material.BOOK, "<aqua>Права",
                "<gray>Игроков с правами: <white>" + plot.grants.size(),
                "<gray>Публичный доступ: <white>" + (plot.publicPerms.isEmpty() ? "нет"
                        : String.join(", ", plot.publicPerms.stream().map(Plot.Perm::title).toList())),
                "<yellow>Нажмите, чтобы настроить"),
                e -> new RightsMenu(plugin, player, plot, back).open());

        // ---- опасная зона ----
        boolean combined = admin;       // у администратора ряд занят решениями: личные кнопки уходят в правый край
        int resetSlot = combined ? 43 : 38;
        int releaseSlot = combined ? 44 : 40;
        if (!combined) {
            set(36, Ui.section("Опасная зона", Material.RED_STAINED_GLASS_PANE));
        }
        set(resetSlot, Text.icon(plot.resetOnRelease ? Material.LIME_DYE : Material.GRAY_DYE,
                "<white>Сброс здания при отказе: " + (plot.resetOnRelease ? "<green>да" : "<red>нет"),
                "<gray>Да - здание вернётся к исходному виду.",
                "<gray>Нет - останется как есть (ваши вещи всё равно",
                "<gray>вернутся в хранилище). Администрация вправе", "<gray>сбросить здание в любой момент.",
                "<yellow>Нажмите, чтобы переключить"),
                e -> {
                    plugin.service().setResetOnRelease(plot, !plot.resetOnRelease);
                    refresh();
                });
        set(releaseSlot, Text.icon(Material.TNT, "<red>Отказаться от участка",
                "<gray>Ваши вещи вернутся в хранилище",
                plot.resetOnRelease ? "<gray>Здание вернётся к исходному виду." : "<gray>Здание останется как есть.",
                "<yellow>Потребуется подтверждение"),
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

    // ------------------------------------------------------------ администрация

    /** Решения администратора: каждое требует причину. */
    private void buildAdmin() {
        set(36, Ui.section("Администрация", Material.RED_STAINED_GLASS_PANE));
        set(38, Text.icon(Material.TNT, "<red>Сбросить здание",
                "<gray>Здание вернётся к исходному виду,", "<gray>вещи игроков - в их хранилища",
                "<yellow>Потребуется причина"),
                e -> askReason("Сброс здания", reason -> {
                    String error = plugin.service().reset(plot, player.getName(), reason);
                    if (error != null) {
                        Text.err(player, error);
                    }
                }));
        set(39, Text.icon(Material.IRON_DOOR, "<red>Выселить",
                "<gray>Участок освобождается, вещи - в хранилище", "<yellow>Потребуется причина"),
                e -> askReason("Выселение", reason -> plugin.service().evict(plot,
                        new PlotService.Release(player.getName(), reason, Journal.Action.EVICTED, false,
                                Plot.RestoreMode.FULL))));
        set(41, Text.icon(Material.IRON_BARS, "<dark_red>Изъять (арест вещей)",
                "<gray>Участок отбирается, вещи арестовываются", "<gray>до вашего решения", "<yellow>Потребуется причина"),
                e -> askReason("Изъятие участка", reason -> plugin.service().evict(plot,
                        new PlotService.Release(player.getName(), reason, Journal.Action.SEIZED, true,
                                Plot.RestoreMode.FULL))));
        if (!plot.isPlayerPlot() && !plot.category.isRentOnly()) {
            set(40, Text.icon(Material.OAK_DOOR, "<red>Выселить, здание оставить",
                    "<gray>Участок освобождается, вещи - в хранилище,", "<gray>здание остаётся и становится исходным видом", "<yellow>Потребуется причина"),
                    e -> askReasonKeep("Выселение, здание остаётся", Journal.Action.EVICTED, false));
            set(42, Text.icon(Material.TRIPWIRE_HOOK, "<dark_red>Изъять, здание оставить",
                    "<gray>Вещи арестовываются, здание остаётся", "<gray>и становится исходным видом", "<yellow>Потребуется причина"),
                    e -> askReasonKeep("Изъятие, здание остаётся", Journal.Action.SEIZED, true));
        }
    }

    private void askReasonKeep(String title, Journal.Action action, boolean arrest) {
        askReason(title, reason -> plugin.service().evict(plot, PlotService.Release.keeping(player.getName(), reason, action, arrest)));
    }

    private void askReason(String title, java.util.function.Consumer<String> action) {
        plugin.dialogs().text(player, title + " - участок " + plot.id,
                List.of("<gray>Причина обязательна: она попадёт в журнал и уведомление игроку"), "Причина", "", 120, reason -> {
                    if (reason.isBlank()) {
                        Text.err(player, "Действие отменено: причина обязательна.");
                    } else {
                        action.accept(reason);
                    }
                    player.closeInventory();
                }, this::refresh);
    }
}
