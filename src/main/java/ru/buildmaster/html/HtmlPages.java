package ru.buildmaster.html;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
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
        b.append(command("district storage", "Хранилище", "Вещей: " + stored));
        b.append(command("district journal", "Журнал", "История ваших участков"));
        b.append(command("district warnings", "Уведомления", unread > 0 ? "Непрочитанных: <span class=\"warn\">" + unread + "</span>" : "Непрочитанных нет"));
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
}
