package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.JobApplication;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Vacancy;
import ru.industryreborn.util.Text;

/** Доска объявлений: открытые вакансии предприятий. Нажатие - откликнуться (повторное - отозвать отклик). */
public final class JobBoardMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private static final int FIRST = 9;

    public JobBoardMenu(IndustryRebornPlugin plugin, Player player, int page) {
        super(plugin, player, "<dark_gray>Район › Доска объявлений", 6);
        List<Vacancy> board = plugin.jobs().board();
        int pages = Math.max(1, (board.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= board.size()) {
                break;
            }
            Vacancy v = board.get(index);
            Plot plot = plugin.plots().get(v.plotId);
            JobApplication mine = null;
            for (JobApplication a : plugin.jobs().applicationsBy(player.getUniqueId())) {
                if (a.vacancyId == v.id) {
                    mine = a;
                }
            }
            boolean working = plugin.jobs().employee(v.plotId, player.getUniqueId()) != null;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Предприятие: <white>" + Text.esc(plot.displayName()) + " <gray>(" + plot.category.title() + ", № "
                    + Text.esc(plot.id) + ")");
            lore.add("<gray>Работодатель: <white>" + Text.esc(String.valueOf(plot.ownerName)));
            lore.add("<gray>Зарплата: <green>" + Text.esc(plugin.jobs().describePay(v)));
            lore.add("<gray>Мест: <white>" + plugin.jobs().freeSlots(v) + " из " + v.slots);
            if (!v.perms.isEmpty()) {
                lore.add("<gray>Доступ: <white>" + String.join(", ", v.perms.stream().map(Plot.Perm::title).toList()));
            }
            lore.add("<dark_gray>Вакансия №" + v.id);
            if (working) {
                lore.add("<green>Вы здесь работаете");
            } else if (mine != null) {
                lore.add("<yellow>Отклик отправлен. Нажмите, чтобы отозвать");
            } else {
                lore.add("<yellow>Нажмите, чтобы откликнуться");
            }
            JobApplication myApplication = mine;
            set(FIRST + i, Text.icon(v.role.icon(), "<gold>" + v.role.title(), lore.toArray(new String[0])), e -> {
                String error = working ? "Вы уже работаете на этом предприятии."
                        : myApplication != null ? plugin.jobs().withdraw(player, myApplication)
                        : plugin.jobs().apply(player, v);
                if (error != null) {
                    Text.err(player, error);
                } else if (myApplication == null && !working) {
                    Text.ok(player, "Отклик отправлен: ответ придёт в журнал предупреждений.");
                }
                new JobBoardMenu(plugin, player, current).open();
            });
        }
        if (board.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>Сейчас вакансий нет"));
        }
        set(47, Text.icon(Material.PLAYER_HEAD, "<aqua>Мои работы",
                "<gray>Работаю: <white>" + plugin.jobs().jobsOf(player.getUniqueId()).size(),
                "<gray>Откликов: <white>" + plugin.jobs().applicationsBy(player.getUniqueId()).size()),
                e -> new MyJobsMenu(plugin, player).open());
        set(4, Text.icon(Material.OAK_SIGN, "<green>Доска объявлений", "<gray>Вакансий: <white>" + board.size(),
                "<gray>Нажмите на вакансию, чтобы откликнуться"));
        navBar(null, null, current, pages, p -> new JobBoardMenu(plugin, player, p).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
