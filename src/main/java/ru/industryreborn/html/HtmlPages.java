package ru.industryreborn.html;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Employee;
import ru.industryreborn.model.JobApplication;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Request;
import ru.industryreborn.model.ServiceOrder;
import ru.industryreborn.model.Vacancy;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.service.ProductionService;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.service.ServiceCatalog;
import ru.industryreborn.service.WarningLog;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.Treasury;
import ru.industryreborn.service.TreasuryPayouts;
import ru.industryreborn.util.Text;

/** Содержимое HTML-страниц: главное меню, банк и казна. Данные подставляются при открытии и обновляются, пока страница открыта. */
public final class HtmlPages {

    private final IndustryRebornPlugin plugin;

    public HtmlPages(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    private String money(double v) {
        return "<span class=\"num\">" + Html.esc(plugin.economy().format(v)) + "</span>";
    }

    private static String stat(String value, String label) {
        return "<div class=\"stat\"><b>" + value + "</b><span>" + Html.esc(label) + "</span></div>";
    }

    private static String link(String href, String title, String text) {
        return "<a class=\"card\" href=\"" + Html.attr(href) + "\"><h3>" + Html.esc(title) + "</h3><p>" + text + "</p></a>";
    }

    private static String command(String command, String title, String text) {
        return "<a class=\"card\" data-command=\"" + Html.attr(command) + "\"><h3>" + Html.esc(title) + "</h3><p>" + text + "</p></a>";
    }

    // ------------------------------------------------------------ главное меню

    public String main(Player player, String suffix) {
        var id = player.getUniqueId();
        List<Plot> mine = plugin.plots().all().stream().filter(p -> p.involves(id)).toList();
        int stored = plugin.storage().countItems(id, StorageEntry.State.AVAILABLE);
        int unread = plugin.warnings().unread(id);
        int jobs = plugin.jobs().jobsOf(id).size();
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"head\"><img src=\"head:").append(Html.attr(player.getName())).append("\"><div><h1>Район коммерции</h1>")
                .append("<div class=\"sub\">").append(Html.esc(player.getName())).append("</div></div></div>\n");
        b.append("<div class=\"stats\">")
                .append(stat(money(plugin.economy().balance(player)), "Баланс"))
                .append(stat(String.valueOf(mine.size()), "Участков"))
                .append(stat(String.valueOf(stored), "Вещей в хранилище"))
                .append(stat(unread > 0 ? "<span class=\"warn\">" + unread + "</span>" : "0", "Непрочитанных уведомлений"))
                .append(stat(String.valueOf(jobs), "Работаю"))
                .append("</div>\n");
        Plot soonest = mine.stream().filter(p -> p.status == Plot.Status.RENTED && p.isOwner(id))
                .min(Comparator.comparingLong(p -> p.rentedUntil)).orElse(null);
        if (soonest != null) {
            long left = soonest.rentedUntil - System.currentTimeMillis();
            double ratio = Math.max(0, Math.min(1, (double) left / (plugin.service().maxRentDays() * plugin.service().dayMillis())));
            b.append("<h2>Ближайшее окончание аренды</h2><div>").append(Html.esc(soonest.displayName())).append(" № ")
                    .append(Html.esc(soonest.id)).append(" · ").append(Html.esc(Text.duration(left)))
                    .append("</div><div class=\"bar\"><i").append(left < 86_400_000L ? " class=\"low\"" : "")
                    .append(" style=\"width:").append((int) Math.round(ratio * 220)).append("px\"></i></div>\n");
        }
        b.append("<h2>Разделы</h2><div class=\"grid\">");
        b.append(link("bank-" + suffix + ".html", "Банк", "Счета предприятий, пополнение и снятие, условия"));
        if (player.hasPermission("industryreborn.bank")) {
            b.append(link("treasury-" + suffix + ".html", "Казна", "Баланс, отчёт, последние операции"));
        }
        long free = plugin.plots().all().stream().filter(p -> p.approval == Plot.Approval.APPROVED && p.isFree() && !p.restorePending
                && !p.category.isAdminOnly()).count();
        b.insert(b.indexOf("<a class=\"card\" href=\"bank-"), link("free-" + suffix + ".html", "Свободные участки", "Доступно: " + free)
                + link("mine-" + suffix + ".html", "Мои участки", "Всего: " + mine.size()));
        long businesses = mine.stream().filter(p -> p.isOwner(id) && isBusiness(p)).count();
        long hotels = mine.stream().filter(p -> p.isOwner(id) && p.category == Category.HOTEL).count();
        if (businesses > 0) {
            b.append(link("business-" + suffix + ".html", "Мои предприятия", "Мастерские, магазины, склады: " + businesses));
        }
        if (hotels > 0 && plugin.modules().enabled(ru.industryreborn.service.Module.HOTELS)) {
            b.append(link("hotel-" + suffix + ".html", "Мои отели", "Номера, гости, цены: " + hotels));
        }
        if (plugin.jobs().enabled()) {
            b.append(link("jobs-" + suffix + ".html", "Работа", "Вакансий: " + plugin.jobs().board().size() + " · работаю: " + jobs));
        }
        if (plugin.modules().enabled(ru.industryreborn.service.Module.SERVICES)) {
            b.append(link("orders-" + suffix + ".html", "Мои заказы", "Заказов: " + plugin.orders().ofClient(id).size()));
        }
        b.append(link("storage-" + suffix + ".html", "Хранилище", "Вещей: " + stored));
        b.append(link("journal-" + suffix + ".html", "Журнал", "История ваших участков"));
        b.append(link("warnings-" + suffix + ".html", "Уведомления", unread > 0 ? "Непрочитанных: <span class=\"warn\">" + unread + "</span>" : "Непрочитанных нет"));
        if (player.hasPermission("industryreborn.admin") || player.hasPermission("industryreborn.bank") || player.hasPermission("industryreborn.grid")) {
            b.append(link("admin-" + suffix + ".html", "Администрирование", "Заявки, арест вещей, электросеть, банк"));
        }
        b.append(command("district menu classic", "Все разделы", "Участки, работа, заказы и остальное (меню-сундук)"));
        b.append("</div>\n<div class=\"foot\"><a class=\"btn gray\" data-action=\"close\">Закрыть</a></div>");
        return Html.page("Район коммерции", b.toString());
    }

    // ------------------------------------------------------------ банк

    public String bank(Player player, String suffix) {
        var bank = plugin.bank();
        StringBuilder b = new StringBuilder();
        b.append("<h1>Банк</h1><div class=\"sub\">Счета предприятий и условия банка</div>\n");
        b.append("<h2>Условия</h2><div class=\"stats\">")
                .append(stat(bank.depositCommission() + "%", "Комиссия за пополнение"))
                .append(stat(bank.withdrawCommission() + "%", "Комиссия за снятие"))
                .append(stat(bank.rentTax() + "%", "Налог на аренду"))
                .append(stat(bank.seizurePenalty() + "%", "Штраф при изъятии"))
                .append("</div>\n");
        List<Plot> accounts = new ArrayList<>();
        for (Plot p : plugin.plots().all()) {
            if (p.isOwner(player.getUniqueId())) {
                plugin.power().touch(p);
                accounts.add(p);
            }
        }
        b.append("<h2>Мои счета</h2>");
        if (accounts.isEmpty()) {
            b.append("<div class=\"muted\">У вас нет предприятий со счётом.</div>");
        } else {
            b.append("<table><tr><th>Предприятие</th><th>Баланс</th><th>Электричество</th><th>Действия</th></tr>");
            for (Plot p : accounts) {
                b.append("<tr><td>").append(Html.esc(p.displayName())).append(" <span class=\"muted\">№ ").append(Html.esc(p.id))
                        .append(" · ").append(Html.esc(p.category.title())).append("</span>");
                if (p.taxDebt > 1e-9) {
                    b.append("<br><span class=\"minus\">Долг по налогу: ").append(money(p.taxDebt)).append("</span>");
                }
                b.append("</td><td>").append(money(plugin.power().balance(p))).append("</td><td>");
                if (plugin.power().isBilled(p)) {
                    b.append(money(p.account.ratePerDay())).append(" / сутки<br>")
                            .append(plugin.power().isPowered(p) ? "<span class=\"plus\">есть</span>" : "<span class=\"minus\">обесточено</span>");
                } else {
                    b.append("<span class=\"muted\">-</span>");
                }
                b.append("</td><td>");
                for (int amount : new int[] {100, 1000, 10000}) {
                    b.append("<a class=\"btn\" data-command=\"district account deposit ").append(amount).append(' ').append(Html.attr(p.id))
                            .append("\">+").append(amount).append("</a>");
                }
                for (int amount : new int[] {100, 1000}) {
                    b.append("<a class=\"btn red\" data-command=\"district account withdraw ").append(amount).append(' ')
                            .append(Html.attr(p.id)).append("\">-").append(amount).append("</a>");
                }
                b.append("</td></tr>");
            }
            b.append("</table>");
        }
        b.append("<div class=\"foot\"><a class=\"btn gray\" href=\"main-").append(suffix).append(".html\">Назад</a>");
        if (player.hasPermission("industryreborn.bank")) {
            b.append("<a class=\"btn\" href=\"treasury-").append(suffix).append(".html\">Казна</a>");
            b.append("<a class=\"btn gray\" href=\"bankset-").append(suffix).append(".html\">Настройки банка</a>");
        }
        b.append("<a class=\"btn gray\" data-action=\"close\">Закрыть</a></div>");
        return Html.page("Банк", b.toString());
    }

    // ------------------------------------------------------------ казна

    public String treasury(Player player, String suffix) {
        if (!player.hasPermission("industryreborn.bank")) {
            return Html.page("Казна", "<h1>Казна</h1><div class=\"minus\">Недостаточно прав.</div>"
                    + "<div class=\"foot\"><a class=\"btn gray\" href=\"main-" + suffix + ".html\">Назад</a></div>");
        }
        TreasuryPayouts payouts = plugin.payouts();
        StringBuilder b = new StringBuilder();
        b.append("<h1>Казна</h1><div class=\"sub\">Фонд администрации: деньги не печатаются, тратятся только имеющиеся</div>\n");
        b.append("<div class=\"stats\">").append(stat("<span class=\"plus\">" + money(plugin.treasury().balance()) + "</span>", "Баланс"))
                .append(stat(money(payouts.spentToday()), "Расход за сутки"))
                .append(stat(payouts.dailyLimit() > 0 ? money(payouts.dailyLimit()) : "нет", "Суточный лимит"))
                .append(stat(payouts.maxSingle() > 0 ? money(payouts.maxSingle()) : "нет", "Предел одной выплаты"))
                .append("</div>\n");
        TreasuryPayouts.Report report = payouts.report(7);
        b.append("<h2>За 7 суток: приход ").append(money(report.totalIncome())).append(", расход ").append(money(report.totalExpense()))
                .append("</h2><table><tr><th>Приход</th><th></th><th>Расход</th><th></th></tr>");
        List<java.util.Map.Entry<String, Double>> in = new ArrayList<>(report.income().entrySet());
        List<java.util.Map.Entry<String, Double>> out = new ArrayList<>(report.expense().entrySet());
        for (int i = 0; i < Math.max(in.size(), out.size()); i++) {
            b.append("<tr>");
            b.append(i < in.size() ? "<td>" + Html.esc(in.get(i).getKey()) + "</td><td class=\"plus\">" + money(in.get(i).getValue()) + "</td>"
                    : "<td></td><td></td>");
            b.append(i < out.size() ? "<td>" + Html.esc(out.get(i).getKey()) + "</td><td class=\"minus\">" + money(out.get(i).getValue()) + "</td>"
                    : "<td></td><td></td>");
            b.append("</tr>");
        }
        b.append("</table>\n<h2>Последние операции</h2><table><tr><th>Сумма</th><th>Причина</th><th>Кто</th></tr>");
        List<Treasury.Tx> log = plugin.treasury().log();
        for (int i = log.size() - 1; i >= 0 && i >= log.size() - 12; i--) {
            Treasury.Tx tx = log.get(i);
            b.append("<tr><td class=\"").append(tx.amount() >= 0 ? "plus\">+" : "minus\">-").append(money(Math.abs(tx.amount())))
                    .append("</td><td>").append(Html.esc(tx.reason())).append("</td><td class=\"muted\">")
                    .append(Html.esc(tx.actor())).append("</td></tr>");
        }
        b.append("</table>\n<div class=\"sub\">Выплаты: /district treasury subsidy &lt;участок&gt; &lt;сумма&gt; &lt;причина&gt; · prize &lt;игрок&gt; &lt;сумма&gt; &lt;мероприятие&gt;</div>");
        b.append("<div class=\"foot\"><a class=\"btn gray\" href=\"main-").append(suffix).append(".html\">Назад</a>")
                .append("<a class=\"btn gray\" href=\"bank-").append(suffix).append(".html\">Банк</a>")
                .append("<a class=\"btn gray\" data-command=\"district treasury menu classic\">Меню-сундук (субсидии, призы)</a>")
                .append("<a class=\"btn gray\" data-action=\"close\">Закрыть</a></div>");
        return Html.page("Казна", b.toString());
    }

    // ------------------------------------------------------------ участки

    private static String nav(String suffix, boolean close) {
        return "<div class=\"foot\"><a class=\"btn gray\" href=\"main-" + suffix + ".html\">Назад</a>"
                + (close ? "<a class=\"btn gray\" data-action=\"close\">Закрыть</a>" : "") + "</div>";
    }

    private static String cmdBtn(String command, String text, String css) {
        return "<a class=\"btn" + (css.isEmpty() ? "" : " " + css) + "\" data-command=\"" + Html.attr(command) + "\">" + Html.esc(text) + "</a>";
    }

    private List<Integer> rentOptions() {
        List<Integer> days = new ArrayList<>();
        for (Integer d : plugin.getConfig().getIntegerList("rent-day-options")) {
            if (d != null && d > 0) {
                days.add(d);
            }
        }
        if (days.isEmpty()) {
            days.add(1);
        }
        return days;
    }

    /** Свободные участки по типам: строка раскрывается, внутри цена, описание и кнопки аренды и покупки. */
    public String free(Player player, String suffix) {
        List<Plot> plots = new PlotFilter(PlotFilter.Mode.FREE).apply(
                plugin.plots().all().stream().filter(p -> p.approval == Plot.Approval.APPROVED).toList(), player.getUniqueId());
        StringBuilder b = new StringBuilder("<h1>Свободные участки</h1><div class=\"sub\">Нажмите на участок, чтобы увидеть цену и действия</div>\n");
        if (plots.isEmpty()) {
            b.append("<div class=\"muted\">Свободных участков сейчас нет.</div>");
        }
        Map<Category, List<Plot>> byCategory = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            byCategory.put(c, new ArrayList<>());
        }
        plots.forEach(p -> byCategory.get(p.category).add(p));
        for (var e : byCategory.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            b.append("<h2>").append(Html.esc(e.getKey().title())).append(" · ").append(e.getValue().size()).append("</h2>");
            for (Plot p : e.getValue()) {
                b.append("<details class=\"row\"><summary><b>").append(Html.esc(p.displayName())).append("</b> <span class=\"muted\">№ ")
                        .append(Html.esc(p.id)).append(" · ").append(Html.esc(p.box.describe())).append("</span> ")
                        .append(p.rentPerDay >= 0 ? "<span class=\"plus\">" + money(p.rentPerDay) + "/сут</span> " : "")
                        .append(p.price >= 0 ? "<span class=\"warn\">" + money(p.price) + "</span>" : "").append("</summary><div class=\"inner\">");
                if (p.description != null && !p.description.isBlank()) {
                    b.append("<div class=\"sub\">").append(Html.esc(p.description)).append("</div>");
                }
                if (p.rentPerDay >= 0) {
                    for (int d : rentOptions()) {
                        b.append(cmdBtn("district rent " + d + " " + p.id, "Аренда " + d + " сут.", ""));
                    }
                }
                if (p.price >= 0) {
                    b.append(cmdBtn("district buy " + p.id, "Купить", ""));
                }
                if (p.price < 0 && p.rentPerDay < 0) {
                    b.append("<span class=\"muted\">Цена не назначена</span>");
                }
                b.append(cmdBtn("district tp " + p.id, "Телепорт", "gray")).append("</div></details>");
            }
        }
        return Html.page("Свободные участки", b.append(nav(suffix, true)).toString());
    }

    /** Мои участки: статус, срок, продление, автопродление и счёт. */
    public String mine(Player player, String suffix) {
        var id = player.getUniqueId();
        List<Plot> plots = new PlotFilter(PlotFilter.Mode.MINE).apply(plugin.plots().all(), id);
        StringBuilder b = new StringBuilder("<h1>Мои участки</h1><div class=\"sub\">Нажмите на участок, чтобы продлить аренду или открыть счёт</div>\n");
        if (plots.isEmpty()) {
            b.append("<div class=\"muted\">У вас пока нет участков. Выберите свободный в разделе «Свободные участки».</div>");
        }
        for (Plot p : plots) {
            boolean owner = p.isOwner(id);
            b.append("<details class=\"row\"><summary><b>").append(Html.esc(p.displayName())).append("</b> <span class=\"muted\">№ ")
                    .append(Html.esc(p.id)).append(" · ").append(Html.esc(p.category.title())).append("</span> ");
            if (!owner) {
                b.append("<span class=\"warn\">доступ</span>");
            } else if (p.status == Plot.Status.RENTED) {
                long left = p.rentedUntil - System.currentTimeMillis();
                b.append("<span class=\"").append(p.isFrozen() ? "minus\">ожидает оплаты" : left < 86_400_000L ? "warn\">" + Html.esc(Text.duration(left))
                        : "plus\">" + Html.esc(Text.duration(left))).append("</span>");
            } else {
                b.append("<span class=\"plus\">куплен</span>");
            }
            b.append("</summary><div class=\"inner\">");
            if (owner && p.status == Plot.Status.RENTED) {
                b.append("<div class=\"sub\">Аренда до ").append(Html.esc(Text.date(p.rentedUntil))).append(", автопродление: ")
                        .append(p.autoRenew ? "включено" : "выключено").append(", ").append(money(p.rentPerDay)).append(" в сутки</div>");
                for (int d : rentOptions()) {
                    b.append(cmdBtn("district extend " + d + " " + p.id, "Продлить на " + d + " сут.", ""));
                }
                b.append(cmdBtn("district autorenew " + (p.autoRenew ? "off" : "on") + " " + p.id,
                        p.autoRenew ? "Выключить автопродление" : "Включить автопродление", "gray"));
            }
            if (owner && !p.isFree()) {
                b.append("<div class=\"sub\">Счёт: ").append(money(plugin.power().balance(p))).append("</div>");
            }
            b.append(cmdBtn("district tp " + p.id, "Телепорт", "gray"));
            if (owner) {
                b.append(cmdBtn("district release " + p.id, "Отказаться от участка", "red"));
            }
            b.append("</div></details>");
        }
        return Html.page("Мои участки", b.append(nav(suffix, true)).toString());
    }

    // ------------------------------------------------------------ хранилище, журнал, уведомления

    private static String itemName(org.bukkit.Material m) {
        StringBuilder sb = new StringBuilder();
        for (String w : m.name().toLowerCase(java.util.Locale.ROOT).split("_")) {
            sb.append(sb.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    public String storage(Player player, String suffix) {
        var id = player.getUniqueId();
        int days = plugin.getConfig().getInt("storage-days", 60);
        Map<String, int[]> rows = new LinkedHashMap<>();
        Map<String, Long> oldest = new LinkedHashMap<>();
        int arrested = 0;
        for (var e : plugin.storage().entries(id)) {
            if (e.state != ru.industryreborn.model.StorageEntry.State.AVAILABLE) {
                arrested += e.stacks.size();
                continue;
            }
            for (byte[] data : e.stacks) {
                org.bukkit.inventory.ItemStack stack = org.bukkit.inventory.ItemStack.deserializeBytes(data);
                String key = itemName(stack.getType()) + "|" + e.plotId;
                rows.computeIfAbsent(key, k -> new int[1])[0] += stack.getAmount();
                oldest.merge(key, e.createdAt, Math::min);
            }
        }
        StringBuilder b = new StringBuilder("<h1>Хранилище</h1><div class=\"sub\">Вещи, возвращённые с участков. Хранятся ")
                .append(days).append(" суток</div>\n");
        if (rows.isEmpty()) {
            b.append("<div class=\"muted\">Хранилище пусто.</div>");
        } else {
            b.append("<table><tr><th>Предмет</th><th>Количество</th><th>С участка</th><th>Хранится ещё</th></tr>");
            for (var e : rows.entrySet()) {
                String[] k = e.getKey().split("\\|", 2);
                long left = oldest.get(e.getKey()) + days * 86_400_000L - System.currentTimeMillis();
                b.append("<tr><td>").append(Html.esc(k[0])).append("</td><td>").append(e.getValue()[0]).append("</td><td>")
                        .append(Html.esc(k.length > 1 ? k[1] : "")).append("</td><td class=\"")
                        .append(left < 86_400_000L * 3 ? "warn" : "muted").append("\">").append(Html.esc(Text.duration(Math.max(0, left))))
                        .append("</td></tr>");
            }
            b.append("</table><div class=\"foot\">").append(cmdBtn("district storage take", "Забрать всё, что поместится", ""))
                    .append(cmdBtn("district storage classic", "Выбрать вручную (меню-сундук)", "gray")).append("</div>");
        }
        if (arrested > 0) {
            b.append("<div class=\"minus\">Арестовано вещей: ").append(arrested).append(". Решение примет администрация.</div>");
        }
        return Html.page("Хранилище", b.append(nav(suffix, true)).toString());
    }

    public String journal(Player player, String suffix) {
        List<Journal.Entry> entries = plugin.journal().forPlayer(player.getUniqueId());
        StringBuilder b = new StringBuilder("<h1>Журнал</h1><div class=\"sub\">История ваших участков и решения администрации (последние 40)</div>\n");
        if (entries.isEmpty()) {
            b.append("<div class=\"muted\">Записей нет.</div>");
        } else {
            b.append("<table><tr><th>Дата</th><th>Участок</th><th>Событие</th><th>Подробности</th></tr>");
            for (int i = 0; i < Math.min(40, entries.size()); i++) {
                Journal.Entry e = entries.get(i);
                b.append("<tr><td class=\"muted\">").append(Html.esc(Text.date(e.time()))).append("</td><td>").append(Html.esc(e.plotId()))
                        .append("</td><td>").append(Html.esc(e.action().title())).append("</td><td>").append(Html.esc(e.reason())).append("</td></tr>");
            }
            b.append("</table>");
        }
        return Html.page("Журнал", b.append(nav(suffix, true)).toString());
    }

    public String warnings(Player player, String suffix) {
        List<WarningLog.Warning> list = plugin.warnings().forPlayer(player.getUniqueId());
        StringBuilder b = new StringBuilder("<h1>Уведомления</h1><div class=\"sub\">Аренда, электричество, отклики и решения (последние 40)</div>\n");
        if (list.isEmpty()) {
            b.append("<div class=\"plus\">Предупреждений нет.</div>");
        } else {
            b.append("<table><tr><th></th><th>Дата</th><th>Участок</th><th>Сообщение</th></tr>");
            for (int i = 0; i < Math.min(40, list.size()); i++) {
                WarningLog.Warning w = list.get(i);
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Text.mm(w.text));
                b.append("<tr><td class=\"warn\">").append(w.read ? "" : "●").append("</td><td class=\"muted\">").append(Html.esc(Text.date(w.time)))
                        .append("</td><td>").append(Html.esc(w.plotId)).append("</td><td>").append(Html.esc(plain)).append("</td></tr>");
            }
            b.append("</table>");
        }
        return Html.page("Уведомления", b.append(nav(suffix, true)).toString());
    }

    // ------------------------------------------------------------ предприятия и отели

    private static boolean isBusiness(Plot p) {
        return p.category == Category.WORKSHOP || p.category == Category.SHOP || p.category == Category.RESTAURANT
                || p.category == Category.WAREHOUSE;
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private String accountBlock(Plot p) {
        plugin.power().touch(p);
        StringBuilder b = new StringBuilder("<div class=\"sub\">Счёт: ").append(money(plugin.power().balance(p)));
        if (plugin.power().isBilled(p)) {
            b.append(" · электричество ").append(money(p.account.ratePerDay())).append("/сут, ")
                    .append(plugin.power().isPowered(p) ? "<span class=\"plus\">есть</span>" : "<span class=\"minus\">обесточено</span>");
        }
        b.append("</div>");
        for (int amount : new int[] {100, 1000, 10000}) {
            b.append(cmdBtn("district account deposit " + amount + " " + p.id, "+" + amount, ""));
        }
        for (int amount : new int[] {100, 1000}) {
            b.append(cmdBtn("district account withdraw " + amount + " " + p.id, "-" + amount, "red"));
        }
        return b.toString();
    }

    /** Предприятия игрока (мастерские, магазины, рестораны, склады): счёт, производство, услуги и товары. */
    public String business(Player player, String suffix) {
        var id = player.getUniqueId();
        StringBuilder b = new StringBuilder("<h1>Мои предприятия</h1><div class=\"sub\">Нажмите на предприятие, чтобы управлять</div>\n");
        boolean any = false;
        for (Plot p : plugin.plots().all()) {
            if (!p.isOwner(id) || !isBusiness(p)) {
                continue;
            }
            any = true;
            b.append("<details class=\"row\"><summary><b>").append(Html.esc(p.displayName())).append("</b> <span class=\"muted\">№ ")
                    .append(Html.esc(p.id)).append(" · ").append(Html.esc(p.category.title())).append("</span> ")
                    .append(money(plugin.power().balance(p))).append("</summary><div class=\"inner\">").append(accountBlock(p));
            if (p.category == Category.WORKSHOP) {
                workshopBlock(b, p);
            }
            staffBlock(b, p);
            goodsBlock(b, p);
            b.append("</div></details>");
        }
        if (!any) {
            b.append("<div class=\"muted\">У вас нет предприятий.</div>");
        }
        return Html.page("Мои предприятия", b.append(nav(suffix, true)).toString());
    }

    private void workshopBlock(StringBuilder b, Plot p) {
        var f = plugin.factory();
        var level = plugin.upgrades().of(p);
        var next = plugin.upgrades().next(p);
        List<ProductionService.View> views = f.views(p);
        long working = views.stream().filter(v -> v.status() == ProductionService.Status.WORKING).count();
        b.append("<h2>Производство</h2><div class=\"stats\">")
                .append(stat(f.assignedCount(p) + " из " + f.slots(p), "Станков с заданием"))
                .append(stat(String.valueOf(working), "Работают сейчас"))
                .append(stat(f.stockTotal(p) + " / " + f.stockCapacity(p), "Склад сырья"))
                .append(stat("ур. " + p.level + " · ×" + num(level.speed()), "Уровень, скорость")).append("</div>");
        b.append(cmdBtn("district factory load " + p.id, "Загрузить сырьё из инвентаря", ""))
                .append(cmdBtn("district factory collect " + p.id, "Забрать продукцию", ""));
        if (next != null) {
            b.append(cmdBtn("district factory upgrade " + p.id, "Улучшить до ур. " + next.level() + " · " + plain(next.price()), "gray"));
        }
        if (!p.stock.isEmpty()) {
            b.append("<table><tr><th>Сырьё на складе</th><th>Кол-во</th></tr>");
            int shown = 0;
            for (var e : p.stock.entrySet()) {
                if (shown++ >= 12) {
                    b.append("<tr><td class=\"muted\" colspan=\"2\">... и ещё ").append(p.stock.size() - 12).append("</td></tr>");
                    break;
                }
                b.append("<tr><td>").append(Html.esc(itemName(e.getKey()))).append("</td><td>").append(e.getValue()).append("</td></tr>");
            }
            b.append("</table>");
        }
        b.append("<h2>Станки</h2>");
        if (views.isEmpty()) {
            b.append("<div class=\"muted\">Рабочих станков нет: поставьте печь или наковальню.</div>");
        }
        for (ProductionService.View v : views) {
            String name = v.station() == null ? v.block().name() : v.station().title();
            b.append("<details class=\"row\"><summary>").append(Html.esc(name)).append(" <span class=\"muted\">").append(Html.esc(v.pos()))
                    .append("</span> ");
            if (v.recipe() == null) {
                b.append("<span class=\"muted\">без задания (обслуживает заказы)</span>");
            } else {
                b.append(Html.esc(recipeTitle(v.recipe()))).append(" <span class=\"")
                        .append(v.status() == ProductionService.Status.WORKING ? "plus" : "minus").append("\">")
                        .append(Html.esc(v.status().title())).append("</span> <span class=\"muted\">продукции ").append(v.output()).append("/")
                        .append(v.bufferCapacity()).append("</span>");
            }
            b.append("</summary><div class=\"inner\">");
            if (v.recipe() != null) {
                b.append(cmdBtn("district machine at " + p.id + " " + v.pos() + " clear", "Снять задание", "red"));
            }
            String family = RecipeBook.familyOf(v.block());
            List<RecipeBook.Recipe> recipes = family == null ? List.of() : f.recipesFor(p, v.block());
            if ("craft".equals(family) || recipes.size() > 30) {
                b.append(cmdBtn("district machine at " + p.id + " " + v.pos() + " menu", "Выбрать рецепт (меню-сундук)", "gray"));
            } else if (recipes.isEmpty()) {
                b.append("<span class=\"muted\">")
                        .append("enchant".equals(family) ? "Одобренных зачарований нет: /district enchant propose" : "Рецептов нет").append("</span>");
            } else {
                for (RecipeBook.Recipe r : recipes) {
                    b.append(cmdBtn("district machine at " + p.id + " " + v.pos() + " set " + r.id(), recipeTitle(r),
                            v.recipe() != null && v.recipe().id().equals(r.id()) ? "" : "gray"));
                }
            }
            b.append("</div></details>");
        }
        servicesBlock(b, p);
    }

    private String plain(double v) {
        return plugin.economy().format(v);
    }

    private static String recipeTitle(RecipeBook.Recipe r) {
        String t = itemName(r.output()) + (r.outputAmount() > 1 ? " ×" + r.outputAmount() : "");
        return r.enchanting() ? t + " + " + r.enchantKey() + " " + r.enchantLevel() : t;
    }

    private void servicesBlock(StringBuilder b, Plot p) {
        List<ServiceCatalog.Offer> offers = plugin.catalog().offers();
        if (offers.isEmpty()) {
            return;
        }
        b.append("<h2>Услуги для игроков</h2><table><tr><th>Услуга</th><th>Цена за ед.</th><th>Действия</th></tr>");
        for (ServiceCatalog.Offer offer : offers) {
            Plot.Offer o = p.offers.get(offer.key());
            boolean on = o != null && o.enabled;
            double price = o != null && o.price > 0 ? o.price : offer.defaultPrice();
            b.append("<tr><td>").append(Html.esc(offer.title())).append(on ? " <span class=\"plus\">включена</span>" : " <span class=\"muted\">выключена</span>")
                    .append("</td><td>").append(money(price)).append("</td><td>")
                    .append(cmdBtn("district service toggle " + p.id + " " + offer.key() + " " + (on ? "off" : "on"), on ? "Выключить" : "Включить", on ? "red" : ""))
                    .append(cmdBtn("district service price " + p.id + " " + offer.key() + " " + num(Math.max(0.01, price * 0.9)), "-10%", "gray"))
                    .append(cmdBtn("district service price " + p.id + " " + offer.key() + " " + num(Math.min(offer.maxPrice(), price * 1.1)), "+10%", "gray"))
                    .append("</td></tr>");
        }
        b.append("</table>");
    }

    private void goodsBlock(StringBuilder b, Plot p) {
        if (!plugin.goods().holdsGoods(p)) {
            return;
        }
        b.append("<h2>Товары (").append(plugin.goods().total(p)).append(" / ").append(plugin.goods().capacity(p)).append(")</h2>");
        if (p.goods.isEmpty()) {
            b.append("<div class=\"muted\">Склад товаров пуст.</div>");
        } else {
            b.append("<table><tr><th>Товар</th><th>Кол-во</th><th>Цена</th><th></th></tr>");
            for (var e : p.goods.entrySet()) {
                double price = plugin.goods().price(p, e.getKey());
                b.append("<tr><td>").append(Html.esc(itemName(e.getKey()))).append("</td><td>").append(e.getValue()).append("</td><td>")
                        .append(price > 0 ? money(price) : "<span class=\"muted\">не задана</span>").append("</td><td>");
                if (price > 0) {
                    b.append(cmdBtn("district goods price " + e.getKey().name() + " " + num(Math.max(0.01, price * 0.9)) + " " + p.id, "-10%", "gray"))
                            .append(cmdBtn("district goods price " + e.getKey().name() + " " + num(price * 1.1) + " " + p.id, "+10%", "gray"));
                }
                b.append(cmdBtn("district goods take " + e.getKey().name() + " all " + p.id, "Забрать", "red")).append("</td></tr>");
            }
            b.append("</table>");
        }
        b.append(cmdBtn("district goods deposit " + p.id, "Выложить товары из инвентаря", ""))
                .append(cmdBtn("district goods menu " + p.id, "Назначить цены (меню-сундук)", "gray"));
    }

    /** Отели игрока: номера со статусом и гостем, цена номера, счёт и электричество. */
    public String hotel(Player player, String suffix) {
        var id = player.getUniqueId();
        StringBuilder b = new StringBuilder("<h1>Мои отели</h1><div class=\"sub\">Электричество оплачивается только за занятые номера</div>\n");
        boolean any = false;
        for (Plot h : plugin.plots().all()) {
            if (!h.isOwner(id) || h.category != Category.HOTEL) {
                continue;
            }
            any = true;
            List<Plot> rooms = plugin.hotels().rooms(h);
            b.append("<details class=\"row\"><summary><b>").append(Html.esc(h.displayName())).append("</b> <span class=\"muted\">№ ")
                    .append(Html.esc(h.id)).append("</span> свободно ").append(plugin.hotels().freeRooms(h)).append(" из ").append(rooms.size())
                    .append("</summary><div class=\"inner\">").append(accountBlock(h));
            b.append("<div class=\"stats\">").append(stat(money(h.revenue), "Выручка всего"))
                    .append(stat(num(plugin.power().unitsFor(h)) + " V", "Потребление в сутки"))
                    .append(stat(money(h.maxRoomRate), "Предел цены номера")).append("</div>");
            if (rooms.isEmpty()) {
                b.append("<div class=\"muted\">Номеров пока нет: их создаёт администратор.</div>");
            } else {
                b.append("<table><tr><th>Номер</th><th>Статус</th><th>Размер</th><th>Цена за сутки</th><th></th></tr>");
                for (Plot r : rooms) {
                    b.append("<tr><td>").append(Html.esc(r.id)).append("</td><td>");
                    if (r.isFree()) {
                        b.append(r.restorePending ? "<span class=\"warn\">уборка</span>" : "<span class=\"plus\">свободен</span>");
                    } else {
                        b.append("<span class=\"minus\">занят</span> ").append(Html.esc(String.valueOf(r.ownerName)))
                                .append(" <span class=\"muted\">до ").append(Html.esc(Text.date(r.rentedUntil))).append("</span>");
                    }
                    b.append("</td><td>").append(Html.esc(r.box.describe())).append("</td><td>").append(money(r.rentPerDay)).append("</td><td>")
                            .append(cmdBtn("district room rate " + r.id + " " + num(Math.max(1, Math.floor(r.rentPerDay * 0.9 + 1e-6))), "-10%", "gray"))
                            .append(cmdBtn("district room rate " + r.id + " " + num(Math.ceil(r.rentPerDay * 1.1 - 1e-6)), "+10%", "gray"))
                            .append("</td></tr>");
                }
                b.append("</table>");
            }
            b.append("</div></details>");
        }
        if (!any) {
            b.append("<div class=\"muted\">У вас нет отелей.</div>");
        }
        return Html.page("Мои отели", b.append(nav(suffix, true)).toString());
    }

    // ------------------------------------------------------------ работа и заказы

    private void staffBlock(StringBuilder b, Plot p) {
        if (!plugin.jobs().enabled() || !plugin.jobs().categoryAllowed(p.category)) {
            return;
        }
        List<Vacancy> vacancies = plugin.jobs().vacanciesOf(p.id);
        List<JobApplication> applications = plugin.jobs().applicationsFor(p.id);
        List<Employee> staff = plugin.jobs().employeesOf(p.id);
        b.append("<h2>Работники</h2>");
        if (vacancies.isEmpty() && applications.isEmpty() && staff.isEmpty()) {
            b.append("<div class=\"muted\">Вакансий и работников нет. Создать вакансию: /district job post ").append(Html.esc(p.id))
                    .append(" &lt;должность&gt; &lt;мест&gt; &lt;ставка&gt;</div>");
            return;
        }
        for (Vacancy v : vacancies) {
            b.append("<div class=\"sub\">Вакансия №").append(v.id).append(": ").append(Html.esc(v.role.title())).append(", мест ")
                    .append(plugin.jobs().freeSlots(v)).append(" из ").append(v.slots).append(", ").append(Html.esc(plugin.jobs().describePay(v)))
                    .append(v.open ? "" : " <span class=\"warn\">(набор закрыт)</span>").append("</div>")
                    .append(cmdBtn("district job " + (v.open ? "close " : "open ") + v.id, v.open ? "Закрыть набор" : "Открыть набор", "gray"))
                    .append(cmdBtn("district job delete " + v.id, "Удалить вакансию", "red"));
        }
        if (!applications.isEmpty()) {
            b.append("<table><tr><th>Отклик</th><th>Должность</th><th></th></tr>");
            for (JobApplication a : applications) {
                Vacancy v = plugin.jobs().vacancy(a.vacancyId);
                b.append("<tr><td>").append(Html.esc(a.applicantName)).append("</td><td>").append(v == null ? "-" : Html.esc(v.role.title()))
                        .append("</td><td>").append(cmdBtn("district job accept " + a.id, "Принять", ""))
                        .append(cmdBtn("district job decline " + a.id, "Отклонить", "red")).append("</td></tr>");
            }
            b.append("</table>");
        }
        if (!staff.isEmpty()) {
            b.append("<table><tr><th>Работник</th><th>Должность</th><th>К выплате</th><th>Выплачено</th><th></th></tr>");
            for (Employee e : staff) {
                Vacancy v = plugin.jobs().vacancy(e.vacancyId);
                b.append("<tr><td>").append(Html.esc(e.name)).append("</td><td>").append(v == null ? "-" : Html.esc(v.role.title()))
                        .append("</td><td>").append(money(e.accrued)).append("</td><td>").append(money(e.totalPaid)).append("</td><td>")
                        .append(cmdBtn("district job fire " + p.id + " " + e.name, "Уволить", "red")).append("</td></tr>");
            }
            b.append("</table>");
        }
    }

    /** Доска объявлений, мои работы и отклики. */
    public String jobs(Player player, String suffix) {
        var id = player.getUniqueId();
        StringBuilder b = new StringBuilder("<h1>Работа</h1><div class=\"sub\">Зарплата идёт, пока вы находитесь на участке работодателя</div>\n");
        if (!plugin.jobs().enabled()) {
            return Html.page("Работа", b.append("<div class=\"muted\">Работа на этом сервере отключена.</div>").append(nav(suffix, true)).toString());
        }
        List<Employee> mine = plugin.jobs().jobsOf(id);
        b.append("<h2>Я работаю</h2>");
        if (mine.isEmpty()) {
            b.append("<div class=\"muted\">Вы нигде не работаете.</div>");
        } else {
            b.append("<table><tr><th>Предприятие</th><th>Должность</th><th>К выплате</th><th>Выплачено</th><th>Сегодня</th><th></th></tr>");
            for (Employee e : mine) {
                Vacancy v = plugin.jobs().vacancy(e.vacancyId);
                Plot plot = plugin.plots().get(e.plotId);
                b.append("<tr><td>").append(Html.esc(plot == null ? e.plotId : plot.displayName())).append("</td><td>")
                        .append(v == null ? "-" : Html.esc(v.role.title())).append("</td><td>").append(money(e.accrued)).append("</td><td>")
                        .append(money(e.totalPaid)).append("</td><td>").append(e.todayMinutes).append(" мин</td><td>")
                        .append(cmdBtn("district job quit " + e.plotId, "Уволиться", "red")).append("</td></tr>");
            }
            b.append("</table>");
        }
        List<JobApplication> apps = plugin.jobs().applicationsBy(id);
        if (!apps.isEmpty()) {
            b.append("<h2>Мои отклики</h2><table><tr><th>Предприятие</th><th>Должность</th><th></th></tr>");
            for (JobApplication a : apps) {
                Vacancy v = plugin.jobs().vacancy(a.vacancyId);
                b.append("<tr><td>").append(Html.esc(a.plotId)).append("</td><td>").append(v == null ? "вакансия снята" : Html.esc(v.role.title()))
                        .append("</td><td>").append(cmdBtn("district job withdraw " + a.id, "Отозвать", "gray")).append("</td></tr>");
            }
            b.append("</table>");
        }
        List<Vacancy> board = plugin.jobs().board();
        b.append("<h2>Вакансии (").append(board.size()).append(")</h2>");
        if (board.isEmpty()) {
            b.append("<div class=\"muted\">Сейчас вакансий нет.</div>");
        } else {
            b.append("<table><tr><th>Должность</th><th>Предприятие</th><th>Работодатель</th><th>Зарплата</th><th>Места</th><th></th></tr>");
            for (Vacancy v : board) {
                Plot plot = plugin.plots().get(v.plotId);
                if (plot == null) {
                    continue;
                }
                JobApplication my = apps.stream().filter(a -> a.vacancyId == v.id).findFirst().orElse(null);
                boolean working = plugin.jobs().employee(v.plotId, id) != null;
                b.append("<tr><td>").append(Html.esc(v.role.title())).append("</td><td>").append(Html.esc(plot.displayName()))
                        .append(" <span class=\"muted\">№ ").append(Html.esc(plot.id)).append("</span></td><td>").append(Html.esc(String.valueOf(plot.ownerName)))
                        .append("</td><td class=\"plus\">").append(Html.esc(plugin.jobs().describePay(v))).append("</td><td>")
                        .append(plugin.jobs().freeSlots(v)).append("/").append(v.slots).append("</td><td>");
                if (working) {
                    b.append("<span class=\"plus\">вы здесь работаете</span>");
                } else if (my != null) {
                    b.append(cmdBtn("district job withdraw " + my.id, "Отозвать отклик", "gray"));
                } else {
                    b.append(cmdBtn("district job apply " + v.id, "Откликнуться", ""));
                }
                b.append("</td></tr>");
            }
            b.append("</table>");
        }
        return Html.page("Работа", b.append(nav(suffix, true)).toString());
    }

    /** Заказы игрока во всех мастерских: готовые забрать, ожидающие отменить. */
    public String orders(Player player, String suffix) {
        List<ServiceOrder> orders = plugin.orders().ofClient(player.getUniqueId());
        StringBuilder b = new StringBuilder("<h1>Мои заказы</h1><div class=\"sub\">Услуги мастерских: переплавка, ремонт и другие</div>\n");
        if (orders.isEmpty()) {
            b.append("<div class=\"muted\">Заказов нет. Откройте мастерскую и закажите услугу: /district service order.</div>");
        } else {
            b.append("<table><tr><th>Услуга</th><th>Мастерская</th><th>Объём</th><th>Оплачено</th><th>Статус</th><th></th></tr>");
            for (ServiceOrder o : orders) {
                var def = plugin.catalog().get(o.offerKey);
                b.append("<tr><td>").append(Html.esc(def == null ? o.offerKey : def.title())).append(" <span class=\"muted\">№ ").append(Html.esc(o.shortId()))
                        .append("</span></td><td>").append(Html.esc(o.plotId)).append("</td><td>").append(o.units).append("</td><td>")
                        .append(money(o.paid)).append("</td><td>");
                switch (o.state) {
                    case PENDING -> b.append("<span class=\"warn\">в очереди</span> <span class=\"muted\">").append(Html.esc(plugin.orders().eta(o))).append("</span>");
                    case ACTIVE -> b.append("<span class=\"plus\">выполняется ").append(o.unitsDone).append("/").append(o.units)
                            .append("</span> <span class=\"muted\">").append(Html.esc(plugin.orders().eta(o))).append("</span>");
                    case READY -> b.append("<span class=\"plus\">готов</span>");
                }
                b.append("</td><td>");
                if (o.state == ServiceOrder.State.READY) {
                    b.append(cmdBtn("district service collect " + o.shortId(), "Забрать", ""));
                } else if (o.state == ServiceOrder.State.PENDING) {
                    b.append(cmdBtn("district service cancel " + o.shortId(), "Отменить", "red"));
                }
                b.append("</td></tr>");
            }
            b.append("</table>");
        }
        return Html.page("Мои заказы", b.append(nav(suffix, true)).toString());
    }

    // ------------------------------------------------------------ администрирование

    private String denied(String title, String suffix) {
        return Html.page(title, "<h1>" + Html.esc(title) + "</h1><div class=\"minus\">Недостаточно прав.</div>" + nav(suffix, true));
    }

    private static String adminNav(String suffix) {
        return "<div class=\"foot\"><a class=\"btn gray\" href=\"admin-" + suffix + ".html\">Назад</a>"
                + "<a class=\"btn gray\" data-action=\"close\">Закрыть</a></div>";
    }

    /** Центр администрирования: ссылки на разделы со счётчиками ожидающего. */
    public String admin(Player player, String suffix) {
        boolean admin = player.hasPermission("industryreborn.admin");
        boolean bank = player.hasPermission("industryreborn.bank");
        boolean grid = player.hasPermission("industryreborn.grid");
        if (!admin && !bank && !grid) {
            return denied("Администрирование", suffix);
        }
        StringBuilder b = new StringBuilder("<h1>Администрирование</h1><div class=\"sub\">Разделы по вашим правам</div><div class=\"grid\">");
        if (admin) {
            int pending = plugin.moderation().pending().size();
            b.append(link("moderation-" + suffix + ".html", "Модерация", "Заявок ожидает: " + (pending > 0 ? "<span class=\"warn\">" + pending + "</span>" : "0")));
            b.append(link("arrests-" + suffix + ".html", "Арестованные вещи", "Игроков: " + plugin.arrests().players().size()));
        }
        if (admin) {
            long off = java.util.Arrays.stream(ru.industryreborn.service.Module.values()).filter(m -> !plugin.modules().enabled(m)).count();
            b.append(link("modules-" + suffix + ".html", "Функции плагина", off == 0 ? "Все включены" : "Отключено: <span class=\"warn\">" + off + "</span>"));
        }
        if (grid) {
            b.append(link("grid-" + suffix + ".html", "Электросеть", "Станций: " + plugin.grid().plants().size()));
        }
        if (bank) {
            b.append(link("treasury-" + suffix + ".html", "Казна", "Баланс: " + money(plugin.treasury().balance())));
            b.append(link("bankset-" + suffix + ".html", "Настройки банка", "Комиссии, налоги, штраф"));
        }
        b.append("</div>").append(nav(suffix, true));
        return Html.page("Администрирование", b.toString());
    }

    private static final String[] REJECT_REASONS = {"Не соответствует правилам сервера", "Неподходящее название или описание", "Повторная заявка"};

    /** Заявки игроков: название, описание, участок, голограмма, зачарование. */
    public String moderation(Player player, String suffix) {
        if (!player.hasPermission("industryreborn.admin")) {
            return denied("Модерация", suffix);
        }
        List<Request> list = plugin.moderation().pending();
        StringBuilder b = new StringBuilder("<h1>Модерация</h1><div class=\"sub\">Заявки, ожидающие решения: ").append(list.size()).append("</div>\n");
        if (list.isEmpty()) {
            b.append("<div class=\"plus\">Новых заявок нет.</div>");
        }
        for (Request r : list) {
            String id = ru.industryreborn.service.ModerationService.shortId(r);
            b.append("<details class=\"row\"><summary><b>").append(Html.esc(r.type.title())).append("</b> <span class=\"muted\">№ ")
                    .append(Html.esc(r.plotId)).append(" · ").append(Html.esc(r.requesterName)).append(" · ").append(Html.esc(Text.date(r.createdAt)))
                    .append("</span></summary><div class=\"inner\"><div class=\"sub\">").append(Html.esc(r.text)).append("</div>")
                    .append(cmdBtn("district moderation approve " + id, "Одобрить", ""))
                    .append(cmdBtn("district moderation tp " + id, "Телепорт", "gray"));
            for (String reason : REJECT_REASONS) {
                b.append(cmdBtn("district moderation reject " + id + " " + reason, "Отклонить: " + reason, "red"));
            }
            b.append("<div class=\"sub\">Своя причина: /district moderation reject ").append(id).append(" &lt;причина&gt;</div></div></details>");
        }
        return Html.page("Модерация", b.append(adminNav(suffix)).toString());
    }

    /** Арестованные вещи по игрокам: вернуть, удалить, выставить на аукцион. */
    public String arrests(Player player, String suffix) {
        if (!player.hasPermission("industryreborn.admin")) {
            return denied("Арестованные вещи", suffix);
        }
        var ids = plugin.arrests().players();
        StringBuilder b = new StringBuilder("<h1>Арестованные вещи</h1><div class=\"sub\">Решение по каждому игроку записывается в журнал</div>\n");
        if (ids.isEmpty()) {
            b.append("<div class=\"plus\">Арестованных вещей нет.</div>");
        }
        for (java.util.UUID id : ids) {
            String name = String.valueOf(org.bukkit.Bukkit.getOfflinePlayer(id).getName());
            b.append("<details class=\"row\"><summary><b>").append(Html.esc(name)).append("</b> <span class=\"muted\">вещей: ")
                    .append(plugin.arrests().count(id)).append("</span></summary><div class=\"inner\">")
                    .append(cmdBtn("district arrest release " + name + " Решение администрации", "Вернуть владельцу", ""))
                    .append(cmdBtn("district arrest auction " + name + " Решение администрации", "На аукцион казны", "gray"))
                    .append(cmdBtn("district arrest delete " + name + " Решение администрации", "Удалить", "red"))
                    .append("<div class=\"sub\">Своя причина: /district arrest &lt;release|auction|delete&gt; ").append(Html.esc(name)).append(" &lt;причина&gt;</div></div></details>");
        }
        return Html.page("Арестованные вещи", b.append(adminNav(suffix)).toString());
    }

    /** Электросеть: цена, станции с нагрузкой, подключённые и отключённые предприятия. */
    public String grid(Player player, String suffix) {
        if (!player.hasPermission("industryreborn.grid")) {
            return denied("Электросеть", suffix);
        }
        var grid = plugin.grid();
        List<Plot> plants = grid.plants();
        double price = plugin.power().base();
        StringBuilder b = new StringBuilder("<h1>Электросеть</h1><div class=\"sub\">Станции, нагрузка и подключения</div>\n");
        b.append("<div class=\"stats\">").append(stat(money(price), "Цена 1 V в сутки"))
                .append(stat(String.valueOf(plants.size()), "Станций"))
                .append(stat(grid.active() ? "<span class=\"plus\">действует</span>" : "<span class=\"muted\">нет станций</span>", "Сеть")).append("</div>");
        b.append(cmdBtn("district grid price " + num(Math.max(0.01, price * 0.9)), "Цена -10%", "gray"))
                .append(cmdBtn("district grid price " + num(price * 1.1), "Цена +10%", "gray"))
                .append(cmdBtn("district grid autoconnect", "Подключить все автоматически", ""));
        for (Plot plant : plants) {
            double load = grid.load(plant);
            b.append("<h2>").append(Html.esc(plant.displayName())).append(" <span class=\"muted\">№ ").append(Html.esc(plant.id)).append("</span></h2>")
                    .append("<div class=\"stats\">").append(stat(num(plant.plantCapacity) + " V", "Мощность"))
                    .append(stat(num(grid.demand(plant)) + " V", "Потребление"))
                    .append(stat("<span class=\"" + (load > 1 ? "minus" : load > 0.8 ? "warn" : "plus") + "\">" + Math.round(load * 100) + "%</span>", "Нагрузка"))
                    .append(stat("×" + num(Math.max(1.0, plant.plantMultiplier)), "Множитель тарифа")).append("</div>");
            for (int d : new int[] {-500, -100, 100, 500}) {
                b.append(cmdBtn("district grid capacity " + plant.id + " " + num(Math.max(0, plant.plantCapacity + d)), (d > 0 ? "+" : "") + d + " V", "gray"));
            }
            List<Plot> connected = grid.connected(plant);
            if (!connected.isEmpty()) {
                b.append("<table><tr><th>Подключено</th><th>Потребление</th><th></th></tr>");
                for (Plot p : connected) {
                    b.append("<tr><td>").append(Html.esc(p.displayName())).append(" <span class=\"muted\">№ ").append(Html.esc(p.id)).append("</span></td><td>")
                            .append(num(p.account.unitsPerDay())).append(" V</td><td>")
                            .append(cmdBtn("district grid disconnect " + p.id + " Отключено администратором", "Отключить", "red")).append("</td></tr>");
                }
                b.append("</table>");
            }
        }
        List<Plot> off = new ArrayList<>();
        for (Plot p : plugin.plots().all()) {
            if (ru.industryreborn.service.GridService.gridClient(p) && p.plantId == null && !p.isFree()) {
                off.add(p);
            }
        }
        if (!off.isEmpty()) {
            b.append("<h2>Не подключены</h2><table><tr><th>Предприятие</th><th>Причина</th><th></th></tr>");
            for (Plot p : off) {
                b.append("<tr><td>").append(Html.esc(p.displayName())).append(" <span class=\"muted\">№ ").append(Html.esc(p.id)).append("</span></td><td>")
                        .append(Html.esc(p.gridReason == null || p.gridReason.isBlank() ? "не подключено" : p.gridReason)).append("</td><td>");
                if (p.lastPlantId != null) {
                    b.append(cmdBtn("district grid restore " + p.id, "Вернуть", ""));
                }
                for (Plot plant : plants) {
                    b.append(cmdBtn("district grid connect " + p.id + " " + plant.id, "К " + plant.id, "gray"));
                }
                b.append("</td></tr>");
            }
            b.append("</table>");
        }
        return Html.page("Электросеть", b.append(adminNav(suffix)).toString());
    }

    private String adjust(String command, double value, double step, double max, String unit, boolean reset) {
        StringBuilder b = new StringBuilder();
        b.append(cmdBtn(command + " " + num(Math.max(0, value - step)), "-" + num(step) + unit, "gray"))
                .append(cmdBtn(command + " " + num(Math.min(max, value + step)), "+" + num(step) + unit, "gray"));
        if (reset) {
            b.append(cmdBtn(command + " reset", "Сброс", "red"));
        }
        return b.toString();
    }

    /** Настройки банка: комиссии, штраф, налоги, режим «только в банке». */
    public String bankSettings(Player player, String suffix) {
        if (!player.hasPermission("industryreborn.bank")) {
            return denied("Настройки банка", suffix);
        }
        var bank = plugin.bank();
        StringBuilder b = new StringBuilder("<h1>Настройки банка</h1><div class=\"sub\">Изменения действуют сразу и записываются в журнал</div>\n");
        b.append("<table><tr><th>Параметр</th><th>Сейчас</th><th></th></tr>");
        b.append("<tr><td>Комиссия за пополнение</td><td>").append(num(bank.depositCommission())).append("%</td><td>")
                .append(adjust("district bank admin commission deposit", bank.depositCommission(), 0.5, 100, "%", true)).append("</td></tr>");
        b.append("<tr><td>Комиссия за снятие</td><td>").append(num(bank.withdrawCommission())).append("%</td><td>")
                .append(adjust("district bank admin commission withdraw", bank.withdrawCommission(), 0.5, 100, "%", true)).append("</td></tr>");
        b.append("<tr><td>Штраф при изъятии участка</td><td>").append(num(bank.seizurePenalty())).append("%</td><td>")
                .append(adjust("district bank admin penalty", bank.seizurePenalty(), 5, 100, "%", true)).append("</td></tr>");
        b.append("<tr><td>Налог на аренду</td><td>").append(num(bank.rentTax())).append("%</td><td>")
                .append(adjust("district bank admin tax rent", bank.rentTax(), 1, 100, "%", true)).append("</td></tr>");
        for (Category c : Category.values()) {
            if (c.isAdminOnly() || c.isRentOnly()) {
                continue;
            }
            b.append("<tr><td>Налог на имущество: ").append(Html.esc(c.title())).append("</td><td>").append(money(bank.propertyTax(c))).append("</td><td>")
                    .append(adjust("district bank admin tax property " + c.key(), bank.propertyTax(c), 50, 1_000_000, "", true)).append("</td></tr>");
        }
        b.append("<tr><td>Операции только в банке</td><td>").append(bank.onlyInBank() ? "<span class=\"warn\">да</span>" : "нет").append("</td><td>")
                .append(cmdBtn("district bank admin only-in-bank " + (bank.onlyInBank() ? "off" : "on"), bank.onlyInBank() ? "Выключить" : "Включить", "gray"))
                .append(cmdBtn("district bank admin only-in-bank reset", "Сброс", "red")).append("</td></tr></table>");
        return Html.page("Настройки банка", b.append(adminNav(suffix)).toString());
    }

    /** Функции плагина: включение и выключение. */
    public String modules(Player player, String suffix) {
        if (!player.hasPermission("industryreborn.admin")) {
            return denied("Функции плагина", suffix);
        }
        StringBuilder b = new StringBuilder("<h1>Функции плагина</h1><div class=\"sub\">Отключённая функция сразу блокируется для всех, включая администрацию</div>\n");
        b.append("<table><tr><th>Функция</th><th>Описание</th><th>Состояние</th><th></th></tr>");
        for (ru.industryreborn.service.Module m : ru.industryreborn.service.Module.values()) {
            boolean on = plugin.modules().enabled(m);
            b.append("<tr><td>").append(Html.esc(m.title())).append("</td><td class=\"muted\">").append(Html.esc(m.description())).append("</td><td>")
                    .append(on ? "<span class=\"plus\">включено</span>" : "<span class=\"minus\">отключено</span>").append("</td><td>")
                    .append(cmdBtn("district module " + m.key() + " " + (on ? "off" : "on"), on ? "Отключить" : "Включить", on ? "red" : ""))
                    .append("</td></tr>");
        }
        b.append("</table>");
        return Html.page("Функции плагина", b.append(adminNav(suffix)).toString());
    }
}
