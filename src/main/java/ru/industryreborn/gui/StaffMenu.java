package ru.industryreborn.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Employee;
import ru.industryreborn.model.JobApplication;
import ru.industryreborn.model.JobRole;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Vacancy;
import ru.industryreborn.service.PlotFilter;
import ru.industryreborn.util.Text;

/** Работники предприятия для управляющего: вакансии, отклики, работники. Вкладки внизу. */
public final class StaffMenu extends Menu {

    public enum Tab { VACANCIES, APPLICATIONS, EMPLOYEES, NEW }

    private final Plot plot;
    private final PlotFilter back;

    public StaffMenu(IndustryRebornPlugin plugin, Player player, Plot plot, PlotFilter back, Tab tab) {
        super(plugin, player, "<dark_gray>Работники · " + Text.esc(plot.displayName()), 6);
        this.plot = plot;
        this.back = back;
        switch (tab) {
            case VACANCIES -> vacancies();
            case APPLICATIONS -> applications();
            case EMPLOYEES -> employees();
            case NEW -> roles();
        }
        set(45, Text.icon(Material.NETHER_STAR, "<green>Новая вакансия", "<gray>Должность, места, зарплата"),
                e -> new StaffMenu(plugin, player, plot, back, Tab.NEW).open());
        set(47, Text.icon(Material.BOOK, "<aqua>Вакансии" + (tab == Tab.VACANCIES ? " ◄" : ""),
                "<gray>Размещено: <white>" + plugin.jobs().vacanciesOf(plot.id).size()),
                e -> new StaffMenu(plugin, player, plot, back, Tab.VACANCIES).open());
        set(48, Text.icon(Material.PAPER, "<aqua>Отклики" + (tab == Tab.APPLICATIONS ? " ◄" : ""),
                "<gray>Ожидают: <white>" + plugin.jobs().applicationsFor(plot.id).size()),
                e -> new StaffMenu(plugin, player, plot, back, Tab.APPLICATIONS).open());
        set(49, Text.icon(Material.PLAYER_HEAD, "<aqua>Работники" + (tab == Tab.EMPLOYEES ? " ◄" : ""),
                "<gray>Работают: <white>" + plugin.jobs().employeesOf(plot.id).size()),
                e -> new StaffMenu(plugin, player, plot, back, Tab.EMPLOYEES).open());
        set(53, Text.icon(Material.ARROW, "<yellow>К участку"), e -> new PlotMenu(plugin, player, plot, back).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private void reopen(Tab tab) {
        new StaffMenu(plugin, player, plot, back, tab).open();
    }

    private void vacancies() {
        int slot = 0;
        for (Vacancy v : plugin.jobs().vacanciesOf(plot.id)) {
            if (slot >= 45) {
                break;
            }
            set(slot++, Text.icon(v.role.icon(), "<gold>" + v.role.title() + " <gray>№" + v.id,
                    "<gray>Зарплата: <white>" + Text.esc(plugin.jobs().describePay(v)),
                    "<gray>Мест: <white>" + plugin.jobs().freeSlots(v) + " свободно из " + v.slots,
                    "<gray>Доступ: <white>" + (v.perms.isEmpty() ? "нет"
                            : String.join(", ", v.perms.stream().map(Plot.Perm::title).toList())),
                    v.open ? "<green>Набор открыт" : "<red>Набор закрыт",
                    "<yellow>ЛКМ - открыть/закрыть набор", "<yellow>ПКМ - удалить вакансию"), e -> {
                String error = e.isRightClick() ? plugin.jobs().delete(player, v) : plugin.jobs().setOpen(player, v, !v.open);
                if (error != null) {
                    Text.err(player, error);
                }
                reopen(Tab.VACANCIES);
            });
        }
        if (slot == 0) {
            set(22, Text.icon(Material.BARRIER, "<gray>Вакансий нет", "<gray>Нажмите «Новая вакансия»"));
        }
    }

    private void applications() {
        int slot = 0;
        for (JobApplication a : plugin.jobs().applicationsFor(plot.id)) {
            if (slot >= 45) {
                break;
            }
            Vacancy v = plugin.jobs().vacancy(a.vacancyId);
            set(slot++, Text.icon(Material.PLAYER_HEAD, "<gold>" + Text.esc(a.applicantName),
                    "<gray>Вакансия: <white>" + (v == null ? "снята" : v.role.title() + " №" + v.id),
                    "<yellow>ЛКМ - принять", "<yellow>ПКМ - отклонить"), e -> {
                String error = e.isRightClick() ? plugin.jobs().decline(player, a) : plugin.jobs().accept(player, a);
                if (error != null) {
                    Text.err(player, error);
                }
                reopen(Tab.APPLICATIONS);
            });
        }
        if (slot == 0) {
            set(22, Text.icon(Material.BARRIER, "<gray>Откликов нет"));
        }
    }

    private void employees() {
        int slot = 0;
        for (Employee em : plugin.jobs().employeesOf(plot.id)) {
            if (slot >= 45) {
                break;
            }
            Vacancy v = plugin.jobs().vacancy(em.vacancyId);
            set(slot++, Text.icon(v == null ? Material.PLAYER_HEAD : v.role.icon(), "<gold>" + Text.esc(em.name),
                    "<gray>Должность: <white>" + (v == null ? "-" : v.role.title()),
                    "<gray>К выплате: <white>" + plugin.economy().format(em.accrued),
                    "<gray>Выплачено всего: <white>" + plugin.economy().format(em.totalPaid),
                    "<gray>Сегодня отработал: <white>" + em.todayMinutes + " мин.",
                    "<yellow>Нажмите, чтобы уволить"), e -> new ConfirmMenu(plugin, player,
                    "Уволить " + em.name + "?", new String[] {"<gray>Невыплаченное будет рассчитано:", "<gray>со счёта предприятия, затем с вашего кошелька."},
                    () -> {
                        String error = plugin.jobs().fire(player, plot, em.player);
                        if (error != null) {
                            Text.err(player, error);
                        }
                        reopen(Tab.EMPLOYEES);
                    }, () -> reopen(Tab.EMPLOYEES)).open());
        }
        if (slot == 0) {
            set(22, Text.icon(Material.BARRIER, "<gray>Работников нет"));
        }
    }

    /** Выбор должности, затем ввод числа мест, ставки и процента в чате. */
    private void roles() {
        int slot = 10;
        for (JobRole role : JobRole.values()) {
            set(slot++, Text.icon(role.icon(), "<gold>" + role.title(),
                    "<gray>Доступ по умолчанию: <white>" + (role.defaultPerms().isEmpty() ? "нет"
                            : String.join(", ", role.defaultPerms().stream().map(Plot.Perm::title).toList())),
                    "<yellow>Нажмите, чтобы выбрать"), e -> askSlots(role));
        }
    }

    private void askSlots(JobRole role) {
        plugin.chatPrompt().ask(player, "Сколько мест (1-" + plugin.jobs().maxSlots() + ")?", text -> {
            Integer slots = parseInt(text);
            if (slots == null) {
                reopen(Tab.VACANCIES);
                return;
            }
            plugin.chatPrompt().ask(player, "Ставка за каждые " + plugin.jobs().blockMinutes()
                    + " мин. работы на участке (до " + plugin.economy().format(plugin.jobs().maxRate()) + ", 0 - без ставки):", rateText -> {
                Double rate = parseNumber(rateText);
                if (rate == null) {
                    reopen(Tab.VACANCIES);
                    return;
                }
                if (!plugin.jobs().hasPercentIncome(plot)) {
                    finish(role, slots, rate, 0);
                    return;
                }
                plugin.chatPrompt().ask(player, "Процент от дохода (до " + plugin.jobs().maxPercent() + ", 0 - без процента):",
                        percentText -> {
                            Double percent = parseNumber(percentText);
                            if (percent == null) {
                                reopen(Tab.VACANCIES);
                                return;
                            }
                            finish(role, slots, rate, percent);
                        });
            });
        });
    }

    private void finish(JobRole role, int slots, double rate, double percent) {
        String error = plugin.jobs().post(player, plot, role, slots, rate, percent, null);
        if (error != null) {
            Text.err(player, error);
        } else {
            Text.ok(player, "Вакансия размещена на доске объявлений.");
        }
        reopen(Tab.VACANCIES);
    }

    private Integer parseInt(String text) {
        if (text.equalsIgnoreCase("отмена")) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            Text.err(player, "Нужно целое число.");
            return null;
        }
    }

    private Double parseNumber(String text) {
        if (text.equalsIgnoreCase("отмена")) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            Text.err(player, "Нужно число.");
            return null;
        }
    }
}
