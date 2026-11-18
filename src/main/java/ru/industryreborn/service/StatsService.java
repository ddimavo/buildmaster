package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.ServiceOrder;

/**
 * Сводка показателей плагина для внешнего вывода (вебхуки, Plan, HTML). Собирается на основном потоке; результат - обычные данные,
 * их можно передавать в другие потоки.
 */
public final class StatsService {

    /** Один показатель: числовое значение и готовый текст. */
    public record Metric(String id, String label, double value, String text) { }

    /** Раздел показателей. */
    public record Section(String id, String title, List<Metric> metrics) {
        public Metric get(String metricId) {
            for (Metric m : metrics) {
                if (m.id().equals(metricId)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** Строка рейтинга. */
    public record Top(String name, String owner, double value, String text) { }

    /** Полная сводка. */
    public record Stats(long time, List<Section> sections, List<Top> topRevenue) {
        public Section section(String id) {
            for (Section s : sections) {
                if (s.id().equals(id)) {
                    return s;
                }
            }
            return null;
        }

        public Metric metric(String sectionId, String metricId) {
            Section s = section(sectionId);
            return s == null ? null : s.get(metricId);
        }
    }

    private final IndustryRebornPlugin plugin;

    public StatsService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    private String money(double v) {
        return plugin.economy().format(v);
    }

    private static Metric num(String id, String label, double v) {
        return new Metric(id, label, v, v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v));
    }

    private Metric cash(String id, String label, double v) {
        return new Metric(id, label, v, money(v));
    }

    private static Metric percent(String id, String label, double ratio) {
        return new Metric(id, label, ratio * 100, Math.round(ratio * 100) + "%");
    }

    public Stats collect() {
        List<Section> sections = new ArrayList<>();
        var plots = new ArrayList<>(plugin.plots().all());

        // ---- сервер и участки
        List<Metric> general = new ArrayList<>();
        general.add(num("online", "Игроков онлайн", Bukkit.getOnlinePlayers().size()));
        long approved = plots.stream().filter(p -> p.approval == Plot.Approval.APPROVED && p.parentId == null).count();
        long occupied = plots.stream().filter(p -> p.parentId == null && !p.isFree()).count();
        general.add(num("plots", "Участков", approved));
        general.add(num("occupied", "Занято", occupied));
        general.add(percent("occupancy", "Заполненность", approved == 0 ? 0 : (double) occupied / approved));
        Map<Category, int[]> byCat = new EnumMap<>(Category.class);
        for (Plot p : plots) {
            if (p.parentId != null || p.category.isAdminOnly()) {
                continue;
            }
            int[] c = byCat.computeIfAbsent(p.category, k -> new int[2]);
            c[0]++;
            if (!p.isFree()) {
                c[1]++;
            }
        }
        byCat.forEach((cat, c) -> general.add(new Metric("cat." + cat.key(), cat.title(), c[1], c[1] + " из " + c[0])));
        sections.add(new Section("general", "Район", general));

        // ---- казна и экономика
        List<Metric> eco = new ArrayList<>();
        long day = System.currentTimeMillis() - 24 * 3_600_000L;
        eco.add(cash("treasury", "Баланс казны", plugin.treasury().balance()));
        eco.add(cash("income24", "Приход в казну за сутки", plugin.treasury().incomeSince(day)));
        eco.add(cash("expense24", "Расход казны за сутки", plugin.treasury().expenseSince(day)));
        double accounts = plots.stream().filter(p -> !p.isFree()).mapToDouble(p -> plugin.power().balance(p)).sum();
        eco.add(cash("accounts", "Деньги на счетах предприятий", accounts));
        eco.add(cash("debts", "Долги по налогам", plots.stream().mapToDouble(p -> p.taxDebt).sum()));
        eco.add(cash("revenue", "Выручка предприятий за всё время", plots.stream().mapToDouble(p -> p.revenue).sum()));
        eco.add(cash("electricity-price", "Цена 1 V в сутки", plugin.power().base()));
        eco.add(num("rent-tax", "Налог на аренду, %", plugin.bank().rentTax()));
        eco.add(num("sales-tax", "Налог с продаж, %", plugin.goods().salesTaxPercent()));
        if (plugin.modules().enabled(Module.AUTO_BALANCE)) {
            var s = plugin.balance().measure();
            eco.add(num("index", "Индекс цен", Math.round(s.index() * 100) / 100.0));
            eco.add(num("ratio", "Сток к притоку", Math.round(s.ratio() * 100) / 100.0));
        }
        sections.add(new Section("economy", "Экономика", eco));

        // ---- электричество
        if (plugin.modules().enabled(Module.ELECTRICITY)) {
            List<Metric> el = new ArrayList<>();
            var plants = plugin.grid().plants();
            double capacity = plants.stream().mapToDouble(p -> p.plantCapacity).sum();
            double demand = plants.stream().mapToDouble(plugin.grid()::demand).sum();
            el.add(num("plants", "Электростанций", plants.size()));
            el.add(num("capacity", "Мощность, V/сут", capacity));
            el.add(num("demand", "Потребление, V/сут", demand));
            el.add(percent("load", "Загрузка сети", capacity <= 0 ? 0 : demand / capacity));
            el.add(num("max-multiplier", "Наибольший множитель тарифа", plants.stream().mapToDouble(p -> Math.max(1, p.plantMultiplier)).max().orElse(1)));
            el.add(num("connected", "Подключено предприятий", plots.stream().filter(p -> p.plantId != null).count()));
            sections.add(new Section("electricity", "Электросеть", el));
        }

        // ---- производство
        if (plugin.modules().enabled(Module.PRODUCTION)) {
            List<Metric> pr = new ArrayList<>();
            int workshops = 0;
            int machines = 0;
            int assigned = 0;
            int working = 0;
            for (Plot p : plots) {
                if (p.category != Category.WORKSHOP || p.isFree()) {
                    continue;
                }
                workshops++;
                for (var v : plugin.factory().views(p)) {
                    machines++;
                    if (v.recipe() != null) {
                        assigned++;
                    }
                    if (v.status() == ProductionService.Status.WORKING) {
                        working++;
                    }
                }
            }
            pr.add(num("workshops", "Работающих мастерских", workshops));
            pr.add(num("machines", "Станков", machines));
            pr.add(num("assigned", "Станков с заданием", assigned));
            pr.add(num("working", "Работают сейчас", working));
            // предметы плагина, рецепты администратора и игроков, чертежи
            int adminRecipes = 0;
            int playerRecipes = 0;
            for (var r : plugin.recipes().customRecipes()) {
                if (plugin.recipes().author(r.id()) == null) {
                    adminRecipes++;
                } else {
                    playerRecipes++;
                }
            }
            int learned = 0;
            for (Plot p : plots) {
                learned += p.learnedRecipes.size();
            }
            int pendingRequests = (int) plugin.moderation().pending().stream().filter(r -> r.type == ru.industryreborn.model.Request.Type.RECIPE).count();
            pr.add(num("custom-items", "Предметов плагина", plugin.customItems().all().size()));
            pr.add(num("recipes-admin", "Рецептов администратора", adminRecipes));
            pr.add(num("recipes-player", "Рецептов игроков", playerRecipes));
            pr.add(num("recipes-learned", "Изучено рецептов (по предприятиям)", learned));
            pr.add(num("recipe-requests", "Заявок на рецепты ждут решения", pendingRequests));
            pr.add(num("blueprints-used", "Использовано чертежей", plugin.blueprints() == null ? 0 : plugin.blueprints().usedCount()));
            pr.add(num("blueprints-vault", "Предметов в хранилище администрации", plugin.blueprints() == null ? 0 : plugin.blueprints().vaultSize()));
            sections.add(new Section("production", "Производство", pr));
        }

        // ---- отели
        if (plugin.modules().enabled(Module.HOTELS)) {
            List<Metric> ho = new ArrayList<>();
            int hotels = 0;
            int rooms = 0;
            int busy = 0;
            for (Plot p : plots) {
                if (p.category == Category.HOTEL && p.parentId == null) {
                    hotels++;
                }
                if (p.category == Category.ROOM) {
                    rooms++;
                    if (!p.isFree()) {
                        busy++;
                    }
                }
            }
            ho.add(num("hotels", "Отелей", hotels));
            ho.add(num("rooms", "Номеров", rooms));
            ho.add(num("busy", "Занято номеров", busy));
            ho.add(percent("occupancy", "Занятость номеров", rooms == 0 ? 0 : (double) busy / rooms));
            for (ru.industryreborn.model.RoomClass c : ru.industryreborn.model.RoomClass.values()) {
                int count = 0;
                int busyOfClass = 0;
                double revenue = 0;
                for (Plot p : plots) {
                    if (p.category == Category.ROOM && p.roomClass == c) {
                        count++;
                        busyOfClass += p.isFree() ? 0 : 1;
                        revenue += p.roomRevenue;
                    }
                }
                ho.add(num("rooms-" + c.key(), "Номеров класса «" + c.title() + "»", count));
                ho.add(num("busy-" + c.key(), "Занято, класс «" + c.title() + "»", busyOfClass));
                ho.add(cash("revenue-" + c.key(), "Доход класса «" + c.title() + "»", revenue));
            }
            sections.add(new Section("hotels", "Отели", ho));
        }

        // ---- работа и заказы
        List<Metric> work = new ArrayList<>();
        if (plugin.modules().enabled(Module.JOBS)) {
            work.add(num("vacancies", "Открытых вакансий", plugin.jobs().board().size()));
            work.add(num("employees", "Работников", plots.stream().mapToInt(p -> plugin.jobs().employeesOf(p.id).size()).sum()));
        }
        if (plugin.modules().enabled(Module.SERVICES)) {
            var orders = plugin.orders().all();
            work.add(num("orders-active", "Заказов в работе", orders.stream().filter(o -> o.state != ServiceOrder.State.READY).count()));
            work.add(num("orders-ready", "Готовых заказов", orders.stream().filter(o -> o.state == ServiceOrder.State.READY).count()));
        }
        if (!work.isEmpty()) {
            sections.add(new Section("work", "Работа и заказы", work));
        }

        // ---- товары
        if (plugin.modules().enabled(Module.GOODS)) {
            List<Metric> goods = new ArrayList<>();
            goods.add(num("sellers", "Предприятий с товарами", plots.stream().filter(p -> plugin.goods().sells(p) && plugin.goods().total(p) > 0).count()));
            goods.add(num("items", "Товаров на витринах", plots.stream().mapToInt(p -> plugin.goods().holdsGoods(p) ? plugin.goods().total(p) : 0).sum()));
            sections.add(new Section("goods", "Товары", goods));
        }

        List<Top> top = new ArrayList<>();
        plots.stream().filter(p -> p.revenue > 0 && p.parentId == null).sorted(Comparator.comparingDouble((Plot p) -> p.revenue).reversed()).limit(5)
                .forEach(p -> top.add(new Top(p.displayName(), String.valueOf(p.ownerName), p.revenue, money(p.revenue))));
        return new Stats(System.currentTimeMillis(), sections, top);
    }

    /** Плоское представление для простых форматов: «раздел.показатель» -> текст. */
    public static Map<String, String> flat(Stats stats) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Section s : stats.sections()) {
            for (Metric m : s.metrics()) {
                map.put(s.id() + "." + m.id(), m.text());
            }
        }
        return map;
    }
}
