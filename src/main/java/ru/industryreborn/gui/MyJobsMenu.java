package ru.industryreborn.gui;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Employee;
import ru.industryreborn.model.JobApplication;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Vacancy;
import ru.industryreborn.util.Text;

/** Мои работы: где я работаю (начислено, выплачено, уволиться) и мои отклики (можно отозвать). */
public final class MyJobsMenu extends Menu {

    public MyJobsMenu(IndustryRebornPlugin plugin, Player player) {
        super(plugin, player, "<dark_gray>Мои работы", 4);
        int slot = 0;
        for (Employee e : plugin.jobs().jobsOf(player.getUniqueId())) {
            if (slot >= 9) {
                break;
            }
            Vacancy v = plugin.jobs().vacancy(e.vacancyId);
            Plot plot = plugin.plots().get(e.plotId);
            String where = plot == null ? e.plotId : plot.displayName();
            set(slot++, Text.icon(v == null ? Material.PAPER : v.role.icon(),
                    "<gold>" + (v == null ? "Работа" : v.role.title()) + " · " + Text.esc(where),
                    "<gray>Зарплата: <white>" + (v == null ? "-" : Text.esc(plugin.jobs().describePay(v))),
                    "<gray>К выплате: <white>" + plugin.economy().format(e.accrued),
                    "<gray>Выплачено всего: <white>" + plugin.economy().format(e.totalPaid),
                    "<gray>Сегодня отработано: <white>" + e.todayMinutes + " мин.",
                    "<gray>Зарплата идёт, пока вы находитесь на участке",
                    "<yellow>Нажмите, чтобы уволиться"),
                    ev -> new ConfirmMenu(plugin, player, "Уволиться с участка " + e.plotId + "?",
                            new String[] {"<gray>Невыплаченное будет рассчитано."},
                            () -> {
                                String error = plot == null ? "Участок не найден." : plugin.jobs().quit(player, plot);
                                if (error != null) {
                                    Text.err(player, error);
                                }
                                new MyJobsMenu(plugin, player).open();
                            }, () -> new MyJobsMenu(plugin, player).open()).open());
        }
        List<JobApplication> apps = plugin.jobs().applicationsBy(player.getUniqueId());
        int appSlot = 9;
        for (JobApplication a : apps) {
            if (appSlot >= 18) {
                break;
            }
            Vacancy v = plugin.jobs().vacancy(a.vacancyId);
            set(appSlot++, Text.icon(Material.PAPER, "<yellow>Отклик · " + (v == null ? "вакансия снята" : v.role.title()),
                    "<gray>Участок: <white>" + Text.esc(a.plotId), "<yellow>Нажмите, чтобы отозвать"), ev -> {
                String error = plugin.jobs().withdraw(player, a);
                if (error != null) {
                    Text.err(player, error);
                }
                new MyJobsMenu(plugin, player).open();
            });
        }
        if (slot == 0 && apps.isEmpty()) {
            set(13, Text.icon(Material.BARRIER, "<gray>Вы нигде не работаете",
                    "<gray>Загляните на доску объявлений"));
        }
        set(31, Text.icon(Material.ARROW, "<yellow>К доске объявлений"), e -> new JobBoardMenu(plugin, player, 0).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
