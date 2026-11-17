package ru.buildmaster.html;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.PlotFilter;
import ru.buildmaster.service.ProductionService;
import ru.buildmaster.service.RecipeBook;
import ru.buildmaster.service.ServiceCatalog;
import ru.buildmaster.service.WarningLog;
import ru.buildmaster.model.StorageEntry;
import ru.buildmaster.service.Treasury;
import ru.buildmaster.service.TreasuryPayouts;
import ru.buildmaster.util.Text;

/** Содержимое HTML-страниц: главное меню, банк и казна. Данные подставляются при открытии и обновляются, пока страница открыта. */
public final class HtmlPages {

    private final BuildMasterPlugin plugin;

    public HtmlPages(BuildMasterPlugin plugin) {
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
        if (player.hasPermission("buildmaster.bank")) {
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
        if (hotels > 0) {
            b.append(link("hotel-" + suffix + ".html", "Мои отели", "Номера, гости, цены: " + hotels));
        }
        b.append(link("storage-" + suffix + ".html", "Хранилище", "Вещей: " + stored));
        b.append(link("journal-" + suffix + ".html", "Журнал", "История ваших участков"));
        b.append(link("warnings-" + suffix + ".html", "Уведомления", unread > 0 ? "Непрочитанных: <span class=\"warn\">" + unread + "</span>" : "Непрочитанных нет"));
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
        if (player.hasPermission("buildmaster.bank")) {
            b.append("<a class=\"btn\" href=\"treasury-").append(suffix).append(".html\">Казна</a>");
            b.append("<a class=\"btn gray\" data-command=\"district bank classic\">Настройки банка</a>");
        }
        b.append("<a class=\"btn gray\" data-action=\"close\">Закрыть</a></div>");
        return Html.page("Банк", b.toString());
    }

    // ------------------------------------------------------------ казна

    public String treasury(Player player, String suffix) {
        if (!player.hasPermission("buildmaster.bank")) {
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
            if (e.state != ru.buildmaster.model.StorageEntry.State.AVAILABLE) {
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
}
