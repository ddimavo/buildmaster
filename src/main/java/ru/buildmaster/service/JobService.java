package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Employee;
import ru.buildmaster.model.JobApplication;
import ru.buildmaster.model.JobRole;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Vacancy;
import ru.buildmaster.util.Text;

/**
 * Работники и зарплаты. Управляющий предприятия размещает вакансии (должность, число мест, ставка за блок времени и процент
 * от дохода), игроки откликаются на доске объявлений, управляющий принимает отклики. Зарплата начисляется за время,
 * проведённое на участке, и за процент от дохода (аренда номеров отеля, продажи магазина через ChestShop) и выплачивается
 * со счёта предприятия.
 */
public final class JobService {

    private final BuildMasterPlugin plugin;
    private final File file;
    private final Map<Integer, Vacancy> vacancies = new LinkedHashMap<>();
    private final Map<Integer, JobApplication> applications = new LinkedHashMap<>();
    private final List<Employee> employees = new ArrayList<>();
    private int nextId = 1;
    private LongSupplier clock = System::currentTimeMillis;

    public JobService(BuildMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "jobs.yml");
    }

    /** Только для тестов: подмена часов. */
    public void setClock(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    // ------------------------------------------------------------ настройки

    public boolean enabled() {
        return plugin.getConfig().getBoolean("jobs.enabled", true);
    }

    public boolean categoryAllowed(Category c) {
        for (String s : plugin.getConfig().getStringList("jobs.categories")) {
            if (s.equalsIgnoreCase(c.key())) {
                return true;
            }
        }
        return false;
    }

    public int blockMinutes() {
        return Math.max(1, plugin.getConfig().getInt("jobs.work-block-minutes", 30));
    }

    public double maxRate() {
        return plugin.getConfig().getDouble("jobs.max-rate", 500);
    }

    public double maxPercent() {
        return plugin.getConfig().getDouble("jobs.max-percent", 30);
    }

    public double maxTotalPercent() {
        return plugin.getConfig().getDouble("jobs.max-total-percent", 50);
    }

    public int maxSlots() {
        return plugin.getConfig().getInt("jobs.max-slots-per-vacancy", 10);
    }

    /** Доход, с которого можно платить процент: аренда номеров отеля и продажи магазина. */
    public boolean hasPercentIncome(Plot plot) {
        return plot.category == Category.HOTEL || plot.category == Category.SHOP;
    }

    // ------------------------------------------------------------ запросы

    public Vacancy vacancy(int id) {
        return vacancies.get(id);
    }

    public JobApplication application(int id) {
        return applications.get(id);
    }

    public List<Vacancy> vacanciesOf(String plotId) {
        List<Vacancy> list = new ArrayList<>();
        for (Vacancy v : vacancies.values()) {
            if (v.plotId.equals(plotId)) {
                list.add(v);
            }
        }
        return list;
    }

    public List<Employee> employeesOf(String plotId) {
        List<Employee> list = new ArrayList<>();
        for (Employee e : employees) {
            if (e.plotId.equals(plotId)) {
                list.add(e);
            }
        }
        return list;
    }

    public List<Employee> employeesOf(Vacancy v) {
        List<Employee> list = new ArrayList<>();
        for (Employee e : employees) {
            if (e.vacancyId == v.id) {
                list.add(e);
            }
        }
        return list;
    }

    public List<Employee> jobsOf(UUID player) {
        List<Employee> list = new ArrayList<>();
        for (Employee e : employees) {
            if (e.player.equals(player)) {
                list.add(e);
            }
        }
        return list;
    }

    public Employee employee(String plotId, UUID player) {
        for (Employee e : employees) {
            if (e.plotId.equals(plotId) && e.player.equals(player)) {
                return e;
            }
        }
        return null;
    }

    public List<JobApplication> applicationsFor(String plotId) {
        List<JobApplication> list = new ArrayList<>();
        for (JobApplication a : applications.values()) {
            if (a.plotId.equals(plotId)) {
                list.add(a);
            }
        }
        return list;
    }

    public List<JobApplication> applicationsBy(UUID player) {
        List<JobApplication> list = new ArrayList<>();
        for (JobApplication a : applications.values()) {
            if (a.applicant.equals(player)) {
                list.add(a);
            }
        }
        return list;
    }

    public int freeSlots(Vacancy v) {
        return Math.max(0, v.slots - employeesOf(v).size());
    }

    /** Может ли вакансия принимать отклики: открыта, есть места, предприятие работает. */
    public boolean accepting(Vacancy v) {
        Plot plot = plugin.plots().get(v.plotId);
        return v.open && freeSlots(v) > 0 && plot != null && !plot.isFree() && !plot.restorePending && plot.owner != null;
    }

    /** Доска объявлений: открытые вакансии со свободными местами, самые высокооплачиваемые первыми. */
    public List<Vacancy> board() {
        List<Vacancy> list = new ArrayList<>();
        for (Vacancy v : vacancies.values()) {
            if (accepting(v)) {
                list.add(v);
            }
        }
        list.sort(Comparator.comparingDouble((Vacancy v) -> -v.rate).thenComparingInt(v -> v.id));
        return list;
    }

    public double totalPercent(Plot plot) {
        double sum = 0;
        for (Vacancy v : vacanciesOf(plot.id)) {
            sum += v.percent * v.slots;
        }
        return sum;
    }

    private String name(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() == null ? id.toString().substring(0, 8) : op.getName();
    }

    private void notify(UUID uuid, String plotId, String message) {
        plugin.service().warn(uuid, plotId, message);
    }

    // ------------------------------------------------------------ вакансии

    private String checkManager(Player who, Plot plot) {
        if (!enabled()) {
            return "Работа отключена на этом сервере.";
        }
        if (plot.isFree() || (!plot.isOwner(who.getUniqueId()) && !who.hasPermission("buildmaster.admin"))) {
            return "Вакансиями управляет управляющий участка.";
        }
        if (plot.owner == null || plot.restorePending || plugin.service().isLocked(plot)) {
            return "Участок сейчас обрабатывается.";
        }
        return null;
    }

    /** Размещает вакансию. perms == null - права по умолчанию для должности. */
    public String post(Player who, Plot plot, JobRole role, int slots, double rate, double percent,
                       EnumSet<Plot.Perm> perms) {
        String error = checkManager(who, plot);
        if (error != null) {
            return error;
        }
        if (!categoryAllowed(plot.category)) {
            return "На участках этого типа работников нанимать нельзя.";
        }
        if (plot.isFrozen()) {
            return "Участок заморожен за неуплату: наём недоступен.";
        }
        if (role == null) {
            return "Неизвестная должность. Доступны: " + roleNames();
        }
        if (slots < 1 || slots > maxSlots()) {
            return "Число мест: от 1 до " + maxSlots() + ".";
        }
        if (Double.isNaN(rate) || Double.isNaN(percent) || rate < 0 || percent < 0) {
            return "Ставка и процент не могут быть отрицательными.";
        }
        if (rate > maxRate()) {
            return "Ставка за " + blockMinutes() + " мин. не может быть выше " + plugin.economy().format(maxRate()) + ".";
        }
        if (percent > 0 && !hasPercentIncome(plot)) {
            return "Процент от дохода доступен только в отелях (аренда номеров) и магазинах (продажи).";
        }
        if (percent > maxPercent()) {
            return "Процент от дохода не может быть выше " + trim(maxPercent()) + "%.";
        }
        if (totalPercent(plot) + percent * slots > maxTotalPercent() + 1e-9) {
            return "Суммарный процент работников участка не может быть выше " + trim(maxTotalPercent()) + "%.";
        }
        if (rate <= 0 && percent <= 0) {
            return "Укажите зарплату: ставку или процент от дохода.";
        }
        if (vacanciesOf(plot.id).size() >= plugin.getConfig().getInt("jobs.max-vacancies-per-plot", 5)) {
            return "На участке уже максимум вакансий.";
        }
        EnumSet<Plot.Perm> granted = perms == null ? role.defaultPerms() : EnumSet.copyOf(perms);
        granted.remove(Plot.Perm.BUILD);
        Vacancy v = new Vacancy(nextId++, plot.id, role, slots, rate, percent, granted, clock.getAsLong());
        vacancies.put(v.id, v);
        save();
        plugin.journal().add(plot.id, who.getUniqueId(), who.getName(), Journal.Action.JOB,
                "Вакансия №" + v.id + ": " + role.title() + ", мест " + slots + ", " + describePay(v));
        return null;
    }

    public String describePay(Vacancy v) {
        List<String> parts = new ArrayList<>();
        if (v.rate > 0) {
            parts.add(plugin.economy().format(v.rate) + " за " + blockMinutes() + " мин. работы");
        }
        if (v.percent > 0) {
            parts.add(trim(v.percent) + "% от дохода");
        }
        return String.join(" + ", parts);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public String roleNames() {
        List<String> names = new ArrayList<>();
        for (JobRole r : JobRole.values()) {
            names.add(r.key());
        }
        return String.join(", ", names);
    }

    public String setOpen(Player who, Vacancy v, boolean open) {
        Plot plot = plugin.plots().get(v.plotId);
        String error = plot == null ? "Участок не найден." : checkManager(who, plot);
        if (error != null) {
            return error;
        }
        v.open = open;
        save();
        return null;
    }

    /** Удаляет вакансию без работников; отклики на неё отклоняются. */
    public String delete(Player who, Vacancy v) {
        Plot plot = plugin.plots().get(v.plotId);
        String error = plot == null ? "Участок не найден." : checkManager(who, plot);
        if (error != null) {
            return error;
        }
        if (!employeesOf(v).isEmpty()) {
            return "На этой вакансии есть работники: сначала увольте их или закройте набор.";
        }
        for (JobApplication a : new ArrayList<>(applicationsFor(v.plotId))) {
            if (a.vacancyId == v.id) {
                applications.remove(a.id);
                notify(a.applicant, v.plotId, "<yellow>Вакансия «" + v.role.title() + "» на участке <white>"
                        + Text.esc(v.plotId) + "</white> снята, ваш отклик отменён.");
            }
        }
        vacancies.remove(v.id);
        save();
        return null;
    }

    // ------------------------------------------------------------ отклики и наём

    public String apply(Player who, Vacancy v) {
        if (!enabled()) {
            return "Работа отключена на этом сервере.";
        }
        Plot plot = plugin.plots().get(v.plotId);
        if (plot == null || !accepting(v)) {
            return "Эта вакансия сейчас не принимает отклики.";
        }
        UUID id = who.getUniqueId();
        if (plot.involves(id)) {
            return "Нельзя работать на собственном участке.";
        }
        if (employee(plot.id, id) != null) {
            return "Вы уже работаете на этом предприятии.";
        }
        for (JobApplication a : applicationsBy(id)) {
            if (a.vacancyId == v.id) {
                return "Вы уже откликнулись на эту вакансию.";
            }
        }
        if (jobsOf(id).size() >= plugin.getConfig().getInt("jobs.max-jobs-per-player", 2)) {
            return "Вы уже работаете на максимальном числе предприятий ("
                    + plugin.getConfig().getInt("jobs.max-jobs-per-player", 2) + ").";
        }
        if (applicationsBy(id).size() >= plugin.getConfig().getInt("jobs.max-applications-per-player", 3)) {
            return "У вас слишком много активных откликов. Отзовите лишние в меню «Мои работы».";
        }
        JobApplication a = new JobApplication(nextId++, v.id, plot.id, id, who.getName(), clock.getAsLong());
        applications.put(a.id, a);
        save();
        notify(plot.owner, plot.id, "<aqua>" + Text.esc(who.getName()) + " откликнулся на вакансию «" + v.role.title()
                + "» на участке <white>" + Text.esc(plot.id) + "</white>. Откройте меню участка, раздел «Работники».");
        return null;
    }

    public String withdraw(Player who, JobApplication a) {
        if (!a.applicant.equals(who.getUniqueId())) {
            return "Это не ваш отклик.";
        }
        applications.remove(a.id);
        save();
        return null;
    }

    public String decline(Player who, JobApplication a) {
        Plot plot = plugin.plots().get(a.plotId);
        String error = plot == null ? "Участок не найден." : checkManager(who, plot);
        if (error != null) {
            return error;
        }
        applications.remove(a.id);
        save();
        notify(a.applicant, a.plotId, "<red>Ваш отклик на участок <white>" + Text.esc(a.plotId) + "</white> отклонён.");
        return null;
    }

    public String accept(Player who, JobApplication a) {
        Plot plot = plugin.plots().get(a.plotId);
        String error = plot == null ? "Участок не найден." : checkManager(who, plot);
        if (error != null) {
            return error;
        }
        Vacancy v = vacancies.get(a.vacancyId);
        if (v == null || freeSlots(v) <= 0) {
            applications.remove(a.id);
            save();
            return "На вакансии нет свободных мест.";
        }
        if (plot.involves(a.applicant) || employee(plot.id, a.applicant) != null) {
            applications.remove(a.id);
            save();
            return "Игрок уже связан с этим участком.";
        }
        if (jobsOf(a.applicant).size() >= plugin.getConfig().getInt("jobs.max-jobs-per-player", 2)) {
            applications.remove(a.id);
            save();
            return "Игрок уже работает на максимальном числе предприятий.";
        }
        Employee e = new Employee(a.applicant, a.applicantName, plot.id, v.id, clock.getAsLong());
        e.day = clock.getAsLong() / 86_400_000L;
        employees.add(e);
        applications.remove(a.id);
        if (!v.perms.isEmpty()) {
            plot.grant(a.applicant, v.perms.toArray(new Plot.Perm[0]));
        }
        plugin.plots().save();
        // остальные отклики этого игрока на тот же участок теряют смысл
        for (JobApplication other : applicationsBy(a.applicant)) {
            if (other.plotId.equals(plot.id)) {
                applications.remove(other.id);
            }
        }
        save();
        plugin.journal().add(plot.id, a.applicant, who.getName(), Journal.Action.JOB,
                a.applicantName + " принят: " + v.role.title() + ", " + describePay(v));
        notify(a.applicant, plot.id, "<green>Вас приняли на участок <white>" + Text.esc(plot.id) + "</white>: "
                + v.role.title() + ", " + Text.esc(describePay(v)) + ". Зарплата идёт за время на участке.");
        return null;
    }

    // ------------------------------------------------------------ увольнение

    /** Увольнение по инициативе управляющего (или администратора) с окончательным расчётом. */
    public String fire(Player who, Plot plot, UUID worker) {
        String error = checkManager(who, plot);
        if (error != null) {
            return error;
        }
        Employee e = employee(plot.id, worker);
        if (e == null) {
            return "Этот игрок у вас не работает.";
        }
        remove(plot, e, plot.owner, "уволен управляющим " + who.getName());
        return null;
    }

    public String quit(Player who, Plot plot) {
        Employee e = employee(plot.id, who.getUniqueId());
        if (e == null) {
            return "Вы не работаете на этом участке.";
        }
        remove(plot, e, plot.owner, "уволился по собственному желанию");
        return null;
    }

    /** Расчёт: со счёта предприятия, при нехватке - с кошелька управляющего; остаток прощается. */
    private void remove(Plot plot, Employee e, UUID employer, String why) {
        double owed = e.accrued;
        double paid = payFromAccount(plot, e);
        double rest = e.accrued;
        if (rest > 1e-9 && employer != null && plugin.economy().available()) {
            OfflinePlayer boss = Bukkit.getOfflinePlayer(employer);
            double fromWallet = rest;
            if (plugin.economy().withdraw(boss, fromWallet)) {
                if (plugin.economy().deposit(Bukkit.getOfflinePlayer(e.player), fromWallet)) {
                    paid += fromWallet;
                    e.accrued -= fromWallet;
                    e.totalPaid += fromWallet;
                } else {
                    plugin.economy().deposit(boss, fromWallet);
                }
            }
        }
        double lost = Math.max(0, e.accrued);
        employees.remove(e);
        Vacancy v = vacancies.get(e.vacancyId);
        if (v != null) {
            for (Plot.Perm p : v.perms) {
                plot.revoke(e.player, p);
            }
        } else {
            plot.revokeAll(e.player);
        }
        plugin.plots().save();
        save();
        plugin.journal().add(plot.id, e.player, "система", Journal.Action.JOB,
                e.name + ": " + why + ", расчёт " + plugin.economy().format(paid) + " из " + plugin.economy().format(owed)
                        + (lost > 1e-9 ? ", не выплачено " + plugin.economy().format(lost) : ""));
        notify(e.player, plot.id, "<yellow>Вы больше не работаете на участке <white>" + Text.esc(plot.id) + "</white> ("
                + Text.esc(why) + "). Расчёт: " + plugin.economy().format(paid)
                + (lost > 1e-9 ? ", не выплачено: " + plugin.economy().format(lost) : "") + ".");
        if (plot.owner != null && !plot.owner.equals(e.player)) {
            notify(plot.owner, plot.id, "<yellow>" + Text.esc(e.name) + ": " + Text.esc(why) + ".");
        }
    }

    /** Освобождение участка: все работники рассчитываются (счёт, затем кошелёк прежнего управляющего), вакансии закрываются. */
    public void closePlot(Plot plot, UUID previousOwner) {
        for (Employee e : employeesOf(plot.id)) {
            remove(plot, e, previousOwner, "участок освобождён");
        }
        for (JobApplication a : applicationsFor(plot.id)) {
            applications.remove(a.id);
            notify(a.applicant, plot.id, "<yellow>Участок <white>" + Text.esc(plot.id)
                    + "</white> больше не нанимает: отклик отменён.");
        }
        vacancies.values().removeIf(v -> v.plotId.equals(plot.id));
        save();
    }

    // ------------------------------------------------------------ начисление и выплаты

    /** Раз в минуту: время на участке, начисление ставки, выплата. */
    public void minuteTick() {
        if (!enabled() || employees.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        long day = now / 86_400_000L;
        int dailyCap = plugin.getConfig().getInt("jobs.max-work-minutes-per-day", 480);
        boolean changed = false;
        for (Employee e : new ArrayList<>(employees)) {
            Plot plot = plugin.plots().get(e.plotId);
            Vacancy v = vacancies.get(e.vacancyId);
            if (plot == null || v == null || plot.isFree()) {
                continue;
            }
            if (e.day != day) {
                e.day = day;
                e.todayMinutes = 0;
            }
            Player p = Bukkit.getPlayer(e.player);
            if (p != null && v.rate > 0 && e.todayMinutes < dailyCap && !plot.isFrozen() && !plot.restorePending
                    && onPlot(p, plot)) {
                e.todayMinutes++;
                e.blockMinutes++;
                if (e.blockMinutes >= blockMinutes()) {
                    e.blockMinutes -= blockMinutes();
                    e.accrued += v.rate;
                }
                changed = true;
            }
            if (e.accrued > 1e-9) {
                double paid = payFromAccount(plot, e);
                if (paid > 1e-9) {
                    changed = true;
                    Player online = Bukkit.getPlayer(e.player);
                    if (online != null) {
                        Text.send(online, "<green>Зарплата, участок " + Text.esc(plot.id) + ": +"
                                + plugin.economy().format(paid));
                    }
                }
            }
        }
        if (changed) {
            save();
        }
    }

    private static boolean onPlot(Player p, Plot plot) {
        Location l = p.getLocation();
        return l.getWorld() != null && l.getWorld().getName().equals(plot.world)
                && plot.box.contains(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    /** Выплата начисленного со счёта предприятия, сколько там есть. Возвращает выплаченное. */
    private double payFromAccount(Plot plot, Employee e) {
        if (e.accrued <= 1e-9 || !plugin.economy().available()) {
            return 0;
        }
        plugin.power().touch(plot);
        double amount = Math.min(e.accrued, plot.account.rawBalance());
        if (amount <= 1e-9 || !plot.account.withdraw(amount)) {
            return 0;
        }
        if (!plugin.economy().deposit(Bukkit.getOfflinePlayer(e.player), amount)) {
            plot.account.deposit(amount);
            return 0;
        }
        e.accrued -= amount;
        e.totalPaid += amount;
        plugin.plots().save();
        return amount;
    }

    /** Доход предприятия: работникам с процентом начисляется их доля (выплата - со счёта предприятия). */
    public void onIncome(Plot plot, double amount) {
        if (amount <= 0 || !enabled()) {
            return;
        }
        boolean changed = false;
        for (Employee e : employeesOf(plot.id)) {
            Vacancy v = vacancies.get(e.vacancyId);
            if (v != null && v.percent > 0) {
                e.accrued += amount * v.percent / 100.0;
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    /** Продажа в магазине (ChestShop): доход магазина для процента работников. */
    public void onSale(Plot plot, double amount) {
        if (plot != null && plot.category == Category.SHOP && !plot.isFree()) {
            onIncome(plot, amount);
        }
    }

    // ------------------------------------------------------------ хранение

    public void load() {
        vacancies.clear();
        applications.clear();
        employees.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        nextId = yml.getInt("next-id", 1);
        ConfigurationSection vs = yml.getConfigurationSection("vacancies");
        if (vs != null) {
            for (String k : vs.getKeys(false)) {
                ConfigurationSection s = vs.getConfigurationSection(k);
                EnumSet<Plot.Perm> perms = EnumSet.noneOf(Plot.Perm.class);
                for (String p : s.getStringList("perms")) {
                    perms.add(Plot.Perm.valueOf(p));
                }
                Vacancy v = new Vacancy(Integer.parseInt(k), s.getString("plot"), JobRole.valueOf(s.getString("role")),
                        s.getInt("slots"), s.getDouble("rate"), s.getDouble("percent"), perms, s.getLong("created"));
                v.open = s.getBoolean("open", true);
                vacancies.put(v.id, v);
            }
        }
        ConfigurationSection as = yml.getConfigurationSection("applications");
        if (as != null) {
            for (String k : as.getKeys(false)) {
                ConfigurationSection s = as.getConfigurationSection(k);
                JobApplication a = new JobApplication(Integer.parseInt(k), s.getInt("vacancy"), s.getString("plot"),
                        UUID.fromString(s.getString("player")), s.getString("name"), s.getLong("created"));
                applications.put(a.id, a);
            }
        }
        ConfigurationSection es = yml.getConfigurationSection("employees");
        if (es != null) {
            for (String k : es.getKeys(false)) {
                ConfigurationSection s = es.getConfigurationSection(k);
                Employee e = new Employee(UUID.fromString(s.getString("player")), s.getString("name"), s.getString("plot"),
                        s.getInt("vacancy"), s.getLong("hired"));
                e.accrued = s.getDouble("accrued");
                e.totalPaid = s.getDouble("paid");
                e.blockMinutes = s.getInt("block-minutes");
                e.todayMinutes = s.getInt("today-minutes");
                e.day = s.getLong("day");
                employees.add(e);
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-id", nextId);
        for (Vacancy v : vacancies.values()) {
            String k = "vacancies." + v.id + ".";
            yml.set(k + "plot", v.plotId);
            yml.set(k + "role", v.role.name());
            yml.set(k + "slots", v.slots);
            yml.set(k + "rate", v.rate);
            yml.set(k + "percent", v.percent);
            yml.set(k + "perms", v.perms.stream().map(Enum::name).toList());
            yml.set(k + "open", v.open);
            yml.set(k + "created", v.createdAt);
        }
        for (JobApplication a : applications.values()) {
            String k = "applications." + a.id + ".";
            yml.set(k + "vacancy", a.vacancyId);
            yml.set(k + "plot", a.plotId);
            yml.set(k + "player", a.applicant.toString());
            yml.set(k + "name", a.applicantName);
            yml.set(k + "created", a.createdAt);
        }
        int i = 0;
        for (Employee e : employees) {
            String k = "employees." + (i++) + ".";
            yml.set(k + "player", e.player.toString());
            yml.set(k + "name", e.name);
            yml.set(k + "plot", e.plotId);
            yml.set(k + "vacancy", e.vacancyId);
            yml.set(k + "hired", e.hiredAt);
            yml.set(k + "accrued", e.accrued);
            yml.set(k + "paid", e.totalPaid);
            yml.set(k + "block-minutes", e.blockMinutes);
            yml.set(k + "today-minutes", e.todayMinutes);
            yml.set(k + "day", e.day);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить jobs.yml", ex);
        }
    }
}
