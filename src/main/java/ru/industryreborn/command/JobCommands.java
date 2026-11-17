package ru.industryreborn.command;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.gui.JobBoardMenu;
import ru.industryreborn.model.Employee;
import ru.industryreborn.model.JobApplication;
import ru.industryreborn.model.JobRole;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Vacancy;
import ru.industryreborn.util.Text;

/** Команды работников: /district job ... */
final class JobCommands {

    private final IndustryRebornPlugin plugin;

    JobCommands(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        throw new CommandError("Эту команду можно выполнить только игроку.");
    }

    private Plot plot(String id) {
        Plot p = plugin.plots().get(id);
        if (p == null) {
            throw new CommandError("Участок «" + id + "» не найден.");
        }
        return p;
    }

    private static int integer(String s, String what) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new CommandError(what + " должно быть целым числом.");
        }
    }

    private static double number(String s, String what) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new CommandError(what + " должна быть числом.");
        }
    }

    private Vacancy vacancy(String s) {
        Vacancy v = plugin.jobs().vacancy(integer(s, "Номер вакансии"));
        if (v == null) {
            throw new CommandError("Вакансия №" + s + " не найдена.");
        }
        return v;
    }

    private static void fail(String error) {
        if (error != null) {
            throw new CommandError(error);
        }
    }

    void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "board" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "board" -> {
                Player p = player(sender);
                if (rest.length > 0 && rest[0].equalsIgnoreCase("classic") || !plugin.htmlMenus().open(p, ru.industryreborn.html.HtmlMenus.Kind.JOBS)) {
                    new JobBoardMenu(plugin, p, 0).open();
                }
            }
            case "list" -> list(sender);
            case "apply" -> {
                need(rest, 1, "/district job apply <№ вакансии>");
                fail(plugin.jobs().apply(player(sender), vacancy(rest[0])));
                Text.ok(sender, "Отклик отправлен. Ответ придёт в журнал предупреждений.");
            }
            case "withdraw" -> {
                need(rest, 1, "/district job withdraw <№ отклика>");
                JobApplication a = plugin.jobs().application(integer(rest[0], "Номер отклика"));
                if (a == null) {
                    throw new CommandError("Отклик не найден.");
                }
                fail(plugin.jobs().withdraw(player(sender), a));
                Text.ok(sender, "Отклик отозван.");
            }
            case "quit" -> {
                need(rest, 1, "/district job quit <участок>");
                fail(plugin.jobs().quit(player(sender), plot(rest[0])));
            }
            case "post" -> post(sender, rest);
            case "open", "close" -> {
                need(rest, 1, "/district job " + sub + " <№ вакансии>");
                fail(plugin.jobs().setOpen(player(sender), vacancy(rest[0]), sub.equals("open")));
                Text.ok(sender, sub.equals("open") ? "Набор открыт." : "Набор закрыт.");
            }
            case "delete" -> {
                need(rest, 1, "/district job delete <№ вакансии>");
                fail(plugin.jobs().delete(player(sender), vacancy(rest[0])));
                Text.ok(sender, "Вакансия удалена.");
            }
            case "applications" -> {
                need(rest, 1, "/district job applications <участок>");
                for (JobApplication a : plugin.jobs().applicationsFor(plot(rest[0]).id)) {
                    Vacancy v = plugin.jobs().vacancy(a.vacancyId);
                    Text.send(sender, "<white>№" + a.id + "</white> " + Text.esc(a.applicantName) + " <gray>-> "
                            + (v == null ? "?" : v.role.title() + " (вакансия №" + v.id + ")"));
                }
            }
            case "accept", "decline" -> {
                need(rest, 1, "/district job " + sub + " <№ отклика>");
                JobApplication a = plugin.jobs().application(integer(rest[0], "Номер отклика"));
                if (a == null) {
                    throw new CommandError("Отклик не найден.");
                }
                fail(sub.equals("accept") ? plugin.jobs().accept(player(sender), a) : plugin.jobs().decline(player(sender), a));
                Text.ok(sender, sub.equals("accept") ? "Работник принят." : "Отклик отклонён.");
            }
            case "staff" -> {
                need(rest, 1, "/district job staff <участок>");
                Plot p = plot(rest[0]);
                for (Employee e : plugin.jobs().employeesOf(p.id)) {
                    Vacancy v = plugin.jobs().vacancy(e.vacancyId);
                    Text.send(sender, "<white>" + Text.esc(e.name) + "</white> <gray>" + (v == null ? "" : v.role.title())
                            + ", к выплате " + plugin.economy().format(e.accrued) + ", выплачено "
                            + plugin.economy().format(e.totalPaid));
                }
                for (Vacancy v : plugin.jobs().vacanciesOf(p.id)) {
                    Text.send(sender, "<aqua>Вакансия №" + v.id + "</aqua> " + v.role.title() + ", мест "
                            + plugin.jobs().freeSlots(v) + "/" + v.slots + ", " + plugin.jobs().describePay(v)
                            + (v.open ? "" : " <red>(набор закрыт)"));
                }
            }
            case "fire" -> {
                need(rest, 2, "/district job fire <участок> <игрок>");
                OfflinePlayer target = Bukkit.getOfflinePlayer(rest[1]);
                fail(plugin.jobs().fire(player(sender), plot(rest[0]), target.getUniqueId()));
            }
            default -> throw new CommandError("Подкоманды: board, list, apply, withdraw, quit, post, open, close, delete, "
                    + "applications, accept, decline, staff, fire");
        }
    }

    private static void need(String[] args, int n, String usage) {
        if (args.length < n) {
            throw new CommandError("Использование: " + usage);
        }
    }

    private void post(CommandSender sender, String[] rest) {
        need(rest, 4, "/district job post <участок> <должность> <мест> <ставка за блок> [процент] [containers,stations]");
        Plot plot = plot(rest[0]);
        JobRole role = JobRole.parse(rest[1]);
        if (role == null) {
            throw new CommandError("Неизвестная должность. Доступны: " + plugin.jobs().roleNames());
        }
        double percent = rest.length > 4 ? number(rest[4], "Процент") : 0;
        EnumSet<Plot.Perm> perms = null;
        if (rest.length > 5) {
            perms = EnumSet.noneOf(Plot.Perm.class);
            for (String s : rest[5].split(",")) {
                if (s.equalsIgnoreCase("none") || s.isBlank()) {
                    continue;
                }
                try {
                    perms.add(Plot.Perm.valueOf(s.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new CommandError("Права: containers, stations или none.");
                }
            }
        }
        fail(plugin.jobs().post(player(sender), plot, role, integer(rest[2], "Число мест"),
                number(rest[3], "Ставка"), percent, perms));
        Text.ok(sender, "Вакансия размещена на доске объявлений.");
    }

    private void list(CommandSender sender) {
        Player p = player(sender);
        for (Employee e : plugin.jobs().jobsOf(p.getUniqueId())) {
            Vacancy v = plugin.jobs().vacancy(e.vacancyId);
            Text.send(p, "<white>" + Text.esc(e.plotId) + "</white> <gray>" + (v == null ? "" : v.role.title())
                    + ", к выплате " + plugin.economy().format(e.accrued) + ", выплачено " + plugin.economy().format(e.totalPaid));
        }
        for (JobApplication a : plugin.jobs().applicationsBy(p.getUniqueId())) {
            Text.send(p, "<yellow>Отклик №" + a.id + "</yellow> на вакансию №" + a.vacancyId + " (" + Text.esc(a.plotId) + ")");
        }
    }

    List<String> complete(CommandSender sender, String[] args) {
        int n = args.length; // args[0] = "job"
        if (n == 2) {
            return List.of("board", "list", "apply", "withdraw", "quit", "post", "open", "close", "delete",
                    "applications", "accept", "decline", "staff", "fire");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (n == 3 && List.of("post", "applications", "staff", "fire", "quit").contains(sub)) {
            return plugin.plots().all().stream().filter(p -> !p.isFree()).map(p -> p.id).toList();
        }
        if (n == 4 && sub.equals("post")) {
            return Arrays.stream(JobRole.values()).map(JobRole::key).toList();
        }
        if (n == 4 && sub.equals("fire")) {
            Plot p = plugin.plots().get(args[2]);
            return p == null ? List.of() : plugin.jobs().employeesOf(p.id).stream().map(e -> e.name).toList();
        }
        if (n == 7 && sub.equals("post")) {
            return List.of("containers", "stations", "containers,stations", "none");
        }
        return List.of();
    }
}
