package ru.industryreborn.plan;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.ElementOrder;
import com.djrapitops.plan.extension.FormatType;
import com.djrapitops.plan.extension.NotReadyException;
import com.djrapitops.plan.extension.annotation.DoubleProvider;
import com.djrapitops.plan.extension.annotation.InvalidateMethod;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PercentageProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.annotation.StringProvider;
import com.djrapitops.plan.extension.annotation.Tab;
import com.djrapitops.plan.extension.annotation.TabInfo;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import java.util.UUID;

/**
 * Показатели IndustryReborn на панели Plan: сводка района, экономика, электросеть, производство, отели, работа и заказы, товары, таблицы,
 * а на страницах игроков - их участки, предприятия, выручка, аренда, работа и заказы.
 */
@PluginInfo(name = "IndustryReborn", iconName = "industry", iconFamily = Family.SOLID, color = Color.AMBER)
// Plan хранит результаты провайдеров в своей базе и сам их не удаляет. Таблицы раньше лежали в этом дополнении, и их старые данные остались в базе:
// вместе с показателями они дают на панели ошибку «Invalid card width: 18». Эти аннотации просят Plan при регистрации удалить старые результаты и провайдеры.
@InvalidateMethod("plotsByCategory")
@InvalidateMethod("topRevenue")
@InvalidateMethod("treasuryOperations")
@InvalidateMethod("playerPlotsTable")
@TabInfo(tab = "Экономика", iconName = "coins", elementOrder = {ElementOrder.VALUES})
@TabInfo(tab = "Электросеть", iconName = "bolt", elementOrder = {ElementOrder.VALUES})
@TabInfo(tab = "Производство", iconName = "gears", elementOrder = {ElementOrder.VALUES})
@TabInfo(tab = "Отели", iconName = "hotel", elementOrder = {ElementOrder.VALUES})
@TabInfo(tab = "Работа и заказы", iconName = "briefcase", elementOrder = {ElementOrder.VALUES})
@TabInfo(tab = "Товары", iconName = "shop", elementOrder = {ElementOrder.VALUES})
public class IndustryRebornExtension implements DataExtension {

    private final PlanHook hook;

    public IndustryRebornExtension(PlanHook hook) {
        this.hook = hook;
    }

    private PlanSnapshot s() {
        PlanSnapshot snap = hook.snapshot();
        if (snap == null) {
            throw new NotReadyException();
        }
        return snap;
    }

    private long n(String section, String metric) {
        return Math.round(s().value(section, metric));
    }

    @Override
    public CallEvents[] callExtensionMethodsOn() {
        return new CallEvents[] {CallEvents.PLAYER_JOIN, CallEvents.PLAYER_LEAVE, CallEvents.SERVER_EXTENSION_REGISTER, CallEvents.SERVER_PERIODICAL};
    }

    // ------------------------------------------------------------ район (главная вкладка)

    @NumberProvider(text = "Участков", description = "Участки района без номеров отелей", priority = 100, iconName = "city", iconColor = Color.AMBER)
    public long plots() {
        return n("general", "plots");
    }

    @NumberProvider(text = "Занято участков", priority = 90, iconName = "user-check", iconColor = Color.AMBER)
    public long occupied() {
        return n("general", "occupied");
    }

    @PercentageProvider(text = "Заполненность района", priority = 80, iconName = "chart-pie", iconColor = Color.AMBER)
    public double occupancy() {
        return s().value("general", "occupancy") / 100.0;
    }

    @DoubleProvider(text = "Баланс казны", priority = 70, iconName = "vault", iconColor = Color.GREEN)
    public double treasury() {
        return s().value("economy", "treasury");
    }

    // ------------------------------------------------------------ экономика

    @Tab("Экономика")
    @DoubleProvider(text = "Приход в казну за сутки", priority = 100, iconName = "arrow-down", iconColor = Color.GREEN)
    public double income24() {
        return s().value("economy", "income24");
    }

    @Tab("Экономика")
    @DoubleProvider(text = "Расход казны за сутки", priority = 90, iconName = "arrow-up", iconColor = Color.RED)
    public double expense24() {
        return s().value("economy", "expense24");
    }

    @Tab("Экономика")
    @DoubleProvider(text = "Деньги на счетах предприятий", priority = 80, iconName = "wallet", iconColor = Color.TEAL)
    public double accounts() {
        return s().value("economy", "accounts");
    }

    @Tab("Экономика")
    @DoubleProvider(text = "Долги по налогам", priority = 70, iconName = "file-invoice-dollar", iconColor = Color.RED)
    public double debts() {
        return s().value("economy", "debts");
    }

    @Tab("Экономика")
    @DoubleProvider(text = "Выручка предприятий за всё время", priority = 60, iconName = "cash-register", iconColor = Color.AMBER)
    public double revenue() {
        return s().value("economy", "revenue");
    }

    @Tab("Экономика")
    @DoubleProvider(text = "Цена 1 V электричества в сутки", priority = 50, iconName = "bolt", iconColor = Color.YELLOW)
    public double electricityPrice() {
        return s().value("economy", "electricity-price");
    }

    @Tab("Экономика")
    @DoubleProvider(text = "Налог на аренду, %", priority = 40, iconName = "percent", iconColor = Color.ORANGE)
    public double rentTax() {
        return s().value("economy", "rent-tax");
    }

    @Tab("Экономика")
    @DoubleProvider(text = "Налог с продаж, %", priority = 30, iconName = "percent", iconColor = Color.ORANGE)
    public double salesTax() {
        return s().value("economy", "sales-tax");
    }

    @Tab("Экономика")
    @DoubleProvider(text = "Индекс цен (автокорректировка)", description = "Больше 1 - деньги копятся, цены растут; 0 - функция выключена", priority = 20,
            iconName = "scale-balanced", iconColor = Color.PURPLE)
    public double priceIndex() {
        return s().value("economy", "index");
    }

    // ------------------------------------------------------------ электросеть

    @Tab("Электросеть")
    @NumberProvider(text = "Электростанций", priority = 100, iconName = "industry", iconColor = Color.YELLOW)
    public long plants() {
        return n("electricity", "plants");
    }

    @Tab("Электросеть")
    @NumberProvider(text = "Мощность сети, V/сут", priority = 90, iconName = "bolt", iconColor = Color.YELLOW)
    public long capacity() {
        return n("electricity", "capacity");
    }

    @Tab("Электросеть")
    @NumberProvider(text = "Потребление, V/сут", priority = 80, iconName = "plug", iconColor = Color.ORANGE)
    public long demand() {
        return n("electricity", "demand");
    }

    @Tab("Электросеть")
    @PercentageProvider(text = "Загрузка сети", priority = 70, iconName = "gauge-high", iconColor = Color.ORANGE)
    public double load() {
        return s().value("electricity", "load") / 100.0;
    }

    @Tab("Электросеть")
    @DoubleProvider(text = "Наибольший множитель тарифа", priority = 60, iconName = "triangle-exclamation", iconColor = Color.RED)
    public double multiplier() {
        return s().value("electricity", "max-multiplier");
    }

    @Tab("Электросеть")
    @NumberProvider(text = "Подключено предприятий", priority = 50, iconName = "link", iconColor = Color.TEAL)
    public long connected() {
        return n("electricity", "connected");
    }

    // ------------------------------------------------------------ производство

    @Tab("Производство")
    @NumberProvider(text = "Работающих мастерских", priority = 100, iconName = "hammer", iconColor = Color.BROWN)
    public long workshops() {
        return n("production", "workshops");
    }

    @Tab("Производство")
    @NumberProvider(text = "Станков", priority = 90, iconName = "gears", iconColor = Color.BROWN)
    public long machines() {
        return n("production", "machines");
    }

    @Tab("Производство")
    @NumberProvider(text = "Станков с заданием", priority = 80, iconName = "list-check", iconColor = Color.BROWN)
    public long assigned() {
        return n("production", "assigned");
    }

    @Tab("Производство")
    @NumberProvider(text = "Работают сейчас", priority = 70, iconName = "play", iconColor = Color.GREEN)
    public long working() {
        return n("production", "working");
    }

    // ------------------------------------------------------------ отели

    @Tab("Отели")
    @NumberProvider(text = "Отелей", priority = 100, iconName = "hotel", iconColor = Color.INDIGO)
    public long hotels() {
        return n("hotels", "hotels");
    }

    @Tab("Отели")
    @NumberProvider(text = "Номеров", priority = 90, iconName = "door-closed", iconColor = Color.INDIGO)
    public long rooms() {
        return n("hotels", "rooms");
    }

    @Tab("Отели")
    @NumberProvider(text = "Занято номеров", priority = 80, iconName = "bed", iconColor = Color.INDIGO)
    public long busyRooms() {
        return n("hotels", "busy");
    }

    @Tab("Отели")
    @PercentageProvider(text = "Занятость номеров", priority = 70, iconName = "chart-pie", iconColor = Color.INDIGO)
    public double hotelOccupancy() {
        return s().value("hotels", "occupancy") / 100.0;
    }

    // ------------------------------------------------------------ работа, заказы, товары

    @Tab("Работа и заказы")
    @NumberProvider(text = "Открытых вакансий", priority = 100, iconName = "clipboard-list", iconColor = Color.LIGHT_GREEN)
    public long vacancies() {
        return n("work", "vacancies");
    }

    @Tab("Работа и заказы")
    @NumberProvider(text = "Работников", priority = 90, iconName = "user-tie", iconColor = Color.LIGHT_GREEN)
    public long employees() {
        return n("work", "employees");
    }

    @Tab("Работа и заказы")
    @NumberProvider(text = "Заказов в работе", priority = 80, iconName = "hourglass-half", iconColor = Color.ORANGE)
    public long ordersActive() {
        return n("work", "orders-active");
    }

    @Tab("Работа и заказы")
    @NumberProvider(text = "Готовых заказов", priority = 70, iconName = "box-open", iconColor = Color.GREEN)
    public long ordersReady() {
        return n("work", "orders-ready");
    }

    @Tab("Товары")
    @NumberProvider(text = "Предприятий с товарами", priority = 100, iconName = "shop", iconColor = Color.CYAN)
    public long sellers() {
        return n("goods", "sellers");
    }

    @Tab("Товары")
    @NumberProvider(text = "Товаров на витринах", priority = 90, iconName = "boxes-stacked", iconColor = Color.CYAN)
    public long items() {
        return n("goods", "items");
    }

    // ------------------------------------------------------------ игрок

    private PlanSnapshot.PlayerView p(UUID id) {
        PlanSnapshot.PlayerView v = hook.player(id);
        if (v == null) {
            throw new NotReadyException();
        }
        return v;
    }

    @NumberProvider(text = "Участков и номеров", priority = 100, iconName = "city", iconColor = Color.AMBER, showInPlayerTable = true)
    public long playerPlots(UUID playerUUID) {
        return p(playerUUID).plots();
    }

    @NumberProvider(text = "Предприятий", priority = 90, iconName = "shop", iconColor = Color.AMBER, showInPlayerTable = true)
    public long playerBusinesses(UUID playerUUID) {
        return p(playerUUID).businesses();
    }

    @DoubleProvider(text = "Выручка предприятий", priority = 80, iconName = "coins", iconColor = Color.GREEN, showInPlayerTable = true)
    public double playerRevenue(UUID playerUUID) {
        return p(playerUUID).revenue();
    }

    @DoubleProvider(text = "Деньги на счетах предприятий", priority = 70, iconName = "wallet", iconColor = Color.TEAL)
    public double playerAccounts(UUID playerUUID) {
        return p(playerUUID).accounts();
    }

    @StringProvider(text = "Ближайшее окончание аренды", priority = 60, iconName = "calendar-xmark", iconColor = Color.ORANGE)
    public String playerNearestRent(UUID playerUUID) {
        return p(playerUUID).nearestRent();
    }

    @NumberProvider(text = "Работает", priority = 50, iconName = "briefcase", iconColor = Color.LIGHT_GREEN)
    public long playerJobs(UUID playerUUID) {
        return p(playerUUID).jobs();
    }

    @NumberProvider(text = "Заказов в работе", priority = 40, iconName = "hourglass-half", iconColor = Color.ORANGE)
    public long playerOrdersActive(UUID playerUUID) {
        return p(playerUUID).ordersActive();
    }

    @NumberProvider(text = "Готовых заказов", priority = 30, iconName = "box-open", iconColor = Color.GREEN)
    public long playerOrdersReady(UUID playerUUID) {
        return p(playerUUID).ordersReady();
    }

    @NumberProvider(text = "Вещей в хранилище", priority = 20, iconName = "warehouse", iconColor = Color.BROWN)
    public long playerStored(UUID playerUUID) {
        return p(playerUUID).stored();
    }

}
