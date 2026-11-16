package ru.buildmaster.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.gui.JournalMenu;
import ru.buildmaster.gui.MainMenu;
import ru.buildmaster.gui.ModerationMenu;
import ru.buildmaster.gui.StorageMenu;
import ru.buildmaster.gui.WarningsMenu;
import ru.buildmaster.model.Station;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Request;
import ru.buildmaster.model.StorageEntry;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.PlotService;
import ru.buildmaster.service.RentRules;
import ru.buildmaster.listener.SelectionListener;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

public final class DistrictCommand implements CommandExecutor, TabCompleter {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final Pattern ZONE = Pattern.compile("^[\\p{L}\\p{N}_-]{1,24}$");
    private static final int MAX_ZONES = 18;
    private static final int MAX_TRUSTED = 10;

    private static final List<String> PLAYER_SUBS = List.of("menu", "list", "info", "buy", "rent", "extend",
            "autorenew", "autoreset", "release", "trust", "untrust", "perm", "account", "power", "warnings", "tp", "replace",
            "zones", "storage", "claim", "journal", "name", "desc", "help");
    private static final List<String> ADMIN_SUBS = List.of("wand", "pos1", "pos2", "create", "delete", "evict",
            "setprice", "setrent", "setspawn", "zone", "resnapshot", "reload", "seize", "reset", "holo", "category",
            "moderation", "arrest", "treasury");

    private final BuildMasterPlugin plugin;

    public DistrictCommand(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        if (ADMIN_SUBS.contains(sub) && !sender.hasPermission("buildmaster.admin")) {
            Text.err(sender, "Недостаточно прав.");
            return true;
        }
        if (PLAYER_SUBS.contains(sub) && !sender.hasPermission("buildmaster.use")) {
            Text.err(sender, "Недостаточно прав.");
            return true;
        }
        try {
            switch (sub) {
                case "menu" -> withPlayer(sender, p -> new MainMenu(plugin, p).open());
                case "help" -> help(sender);
                case "list" -> list(sender);
                case "info" -> withPlayer(sender, p -> info(p, plotOrHere(p, rest.length > 0 ? rest[0] : null)));
                case "buy" -> withPlayer(sender, p -> {
                    Plot plot = plotOrHere(p, rest.length > 0 ? rest[0] : null);
                    if (plot != null) {
                        plugin.service().buy(p, plot);
                    }
                });
                case "rent" -> withPlayer(sender, p -> rent(p, rest, false));
                case "extend" -> withPlayer(sender, p -> rent(p, rest, true));
                case "autorenew" -> withPlayer(sender, p -> autoRenew(p, rest));
                case "release" -> withPlayer(sender, p -> release(p, rest));
                case "trust" -> withPlayer(sender, p -> trust(p, rest, true));
                case "untrust" -> withPlayer(sender, p -> trust(p, rest, false));
                case "tp" -> withPlayer(sender, p -> teleport(p, rest));
                case "replace" -> withPlayer(sender, p -> replace(p, rest));
                case "zones" -> withPlayer(sender, p -> zones(p, rest.length > 0 ? rest[0] : null));
                case "storage", "claim" -> withPlayer(sender, p -> new StorageMenu(plugin, p, 0).open());
                case "journal" -> withPlayer(sender, p -> new JournalMenu(plugin, p, 0).open());
                case "name" -> withPlayer(sender, p -> submitText(p, rest, Request.Type.NAME));
                case "desc" -> withPlayer(sender, p -> submitText(p, rest, Request.Type.DESCRIPTION));
                case "perm" -> withPlayer(sender, p -> perm(p, rest));
                case "account" -> account(sender, rest);
                case "power" -> power(sender, rest);
                case "warnings" -> withPlayer(sender, p -> new WarningsMenu(plugin, p, 0).open());
                case "autoreset" -> withPlayer(sender, p -> autoReset(p, rest));
                case "moderation" -> withPlayer(sender, p -> new ModerationMenu(plugin, p, 0).open());
                case "seize" -> adminDecision(sender, rest, Journal.Action.SEIZED);
                case "reset" -> adminDecision(sender, rest, Journal.Action.RESET);
                case "holo" -> withPlayer(sender, p -> holo(p, rest));
                case "category" -> category(sender, rest);
                case "arrest" -> arrest(sender, rest);
                case "treasury" -> treasury(sender, rest);
                case "wand" -> withPlayer(sender, p -> {
                    p.getInventory().addItem(plugin.selection().createWand());
                    Text.ok(p, "Выдан топор выделения: ЛКМ - точка 1, ПКМ - точка 2.");
                });
                case "pos1", "pos2" -> withPlayer(sender, p -> {
                    Location l = p.getLocation();
                    plugin.selection().setPoint(p, sub.equals("pos1") ? 1 : 2, l.getBlockX(), l.getBlockY(), l.getBlockZ());
                });
                case "create" -> withPlayer(sender, p -> create(p, rest));
                case "delete" -> delete(sender, rest);
                case "evict" -> adminDecision(sender, rest, Journal.Action.EVICTED);
                case "setprice" -> setPrice(sender, rest, true);
                case "setrent" -> setPrice(sender, rest, false);
                case "setspawn" -> withPlayer(sender, p -> setSpawn(p, rest));
                case "zone" -> withPlayer(sender, p -> zone(p, rest));
                case "resnapshot" -> withPlayer(sender, p -> resnapshot(p, rest));
                case "reload" -> {
                    plugin.reload();
                    plugin.plots().load();
                    Text.ok(sender, "Конфигурация и участки перезагружены.");
                }
                default -> {
                    Text.err(sender, "Неизвестная подкоманда. Список: /district help");
                }
            }
        } catch (CommandError e) {
            Text.err(sender, e.getMessage());
        }
        return true;
    }

    // ---------------------------------------------------------------- вспомогательное

    private static final class CommandError extends RuntimeException {
        CommandError(String message) {
            super(message, null, false, false);
        }
    }

    private void withPlayer(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (sender instanceof Player p) {
            action.accept(p);
        } else {
            throw new CommandError("Эту команду можно выполнить только игроку.");
        }
    }

    /** Участок по id либо участок, в котором стоит игрок. */
    private Plot plotOrHere(Player p, String id) {
        if (id != null) {
            Plot plot = plugin.plots().get(id);
            if (plot == null) {
                throw new CommandError("Участок «" + id + "» не найден.");
            }
            return plot;
        }
        Plot plot = plugin.plots().at(p.getLocation());
        if (plot == null) {
            throw new CommandError("Вы не стоите на участке. Укажите его номер.");
        }
        return plot;
    }

    private Plot ownPlot(Player p, String id) {
        Plot plot = plotOrHere(p, id);
        if (!plot.isOwner(p.getUniqueId())) {
            throw new CommandError("Это не ваш участок.");
        }
        return plot;
    }

    private static int parseInt(String s, String what) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new CommandError("Нужно целое число: " + what + ".");
        }
    }

    /** Цена: число >= 0 либо "-" (нет). */
    private static double parsePrice(String s) {
        if (s.equals("-") || s.equalsIgnoreCase("нет") || s.equalsIgnoreCase("no")) {
            return -1;
        }
        try {
            double v = Double.parseDouble(s.replace(',', '.'));
            if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new CommandError("Цена должна быть числом от 0 или «-» (нет).");
        }
    }

    private String money(double v) {
        return v < 0 ? "нет" : plugin.economy().format(v);
    }

    // ---------------------------------------------------------------- игрок

    private void help(CommandSender s) {
        Text.send(s, "<yellow>Команды района коммерции:");
        String[] lines = {
                "/district - главное меню",
                "/district list - список участков",
                "/district info [номер] - сведения об участке",
                "/district buy [номер] - купить участок",
                "/district rent <дней> [номер] - арендовать",
                "/district extend <дней> [номер] - продлить аренду",
                "/district autorenew <on|off> [номер] - автопродление",
                "/district release [номер] confirm - отказаться от участка",
                "/district trust|untrust <игрок> [номер] - доверенные игроки",
                "/district replace <зона|все> <из> <в> - заменить материал в здании",
                "/district zones [номер] - части здания",
                "/district tp <номер> - телепорт к участку",
                "/district storage - хранилище (вещи, возвращённые с участков)",
                "/district journal - журнал ваших участков и решений администрации",
                "/district name|desc [номер] <текст> - название и описание (после проверки администратором)",
                "/district autoreset <on|off> [номер] - сбрасывать ли здание при отказе",
                "/district perm <игрок|public> <build|containers|stations> <on|off> - права на участке",
                "/district account [deposit|withdraw <сумма>] - счёт предприятия",
                "/district power - электричество мастерской",
                "/district warnings - журнал предупреждений"};
        for (String l : lines) {
            s.sendMessage(Text.mm("<gray>" + Text.esc(l)));
        }
        if (s.hasPermission("buildmaster.admin")) {
            Text.send(s, "<yellow>Для администратора:");
            String[] admin = {
                    "/district wand | pos1 | pos2 - выделение области",
                    "/district create <номер> <тип> <цена|-> <аренда в сутки|-> - создать участок из выделения",
                    "/district category <номер> <тип> - сменить тип участка",
                    "/district holo [номер] - перенести голограмму в вашу позицию",
                    "/district moderation - заявки на названия и описания",
                    "/district evict|seize|reset <номер> <причина> - выселить, изъять (арест вещей), сбросить здание",
                    "/district arrest list | release|delete|auction <игрок> <причина> - решение по арестованным вещам",
                    "/district treasury [pay <игрок> <сумма> <причина>] - казна",
                    "/district power calc [smelter:2 anvil:1] - калькулятор тарифа",
                    "/district power admin info|base|progression|usage|mode|coef|discount|add|stats - тарифы и статистика",
                    "/district delete <номер> - удалить свободный участок",
                    "/district setprice|setrent <номер> <цена|-> - изменить цены",
                    "/district setspawn [номер] - точка телепорта",
                    "/district zone add <номер> <имя> [блок-иконка] | remove <номер> <имя> | list [номер]",
                    "/district resnapshot <номер> - запомнить текущий вид как исходный",
                    "/district reload - перезагрузка"};
            for (String l : admin) {
                s.sendMessage(Text.mm("<gray>" + Text.esc(l)));
            }
        }
    }

    private void list(CommandSender sender) {
        if (plugin.plots().all().isEmpty()) {
            Text.send(sender, "<gray>Участков пока нет.");
            return;
        }
        Text.send(sender, "<yellow>Участки:");
        for (Plot p : plugin.plots().all()) {
            String state = switch (p.status) {
                case FREE -> "<green>свободен</green>, цена " + money(p.price) + ", аренда " + money(p.rentPerDay) + "/сут.";
                case OWNED -> "<red>куплен</red> (" + Text.esc(String.valueOf(p.ownerName)) + ")";
                case RENTED -> "<yellow>арендован</yellow> (" + Text.esc(String.valueOf(p.ownerName)) + ")";
            };
            sender.sendMessage(Text.mm("<gray> • <white>" + Text.esc(p.id) + "</white> - " + state));
        }
    }

    private void info(Player p, Plot plot) {
        Text.send(p, "<yellow>Участок <white>" + Text.esc(plot.id) + "</white> (" + plot.box.describe() + ")");
        for (String line : plotInfoLines(plot)) {
            p.sendMessage(Text.mm(line));
        }
    }

    private List<String> plotInfoLines(Plot plot) {
        List<String> lines = new ArrayList<>();
        switch (plot.status) {
            case FREE -> {
                lines.add("<gray> Статус: <green>свободен");
                lines.add("<gray> Цена: <white>" + money(plot.price) + "<gray>, аренда: <white>" + money(plot.rentPerDay) + "/сут.");
            }
            case OWNED -> {
                lines.add("<gray> Статус: <red>куплен<gray>, владелец: <white>" + Text.esc(String.valueOf(plot.ownerName)));
            }
            case RENTED -> {
                lines.add("<gray> Статус: <yellow>арендован<gray>, арендатор: <white>" + Text.esc(String.valueOf(plot.ownerName)));
                lines.add("<gray> Осталось: <white>" + Text.duration(plot.rentedUntil - System.currentTimeMillis())
                        + "<gray>, автопродление: " + (plot.autoRenew ? "<green>вкл" : "<red>выкл"));
            }
        }
        if (!plot.zones.isEmpty()) {
            lines.add("<gray> Зоны: <white>" + Text.esc(String.join(", ", plot.zones.keySet())));
        }
        return lines;
    }

    /** rent <дней> [номер] и extend <дней> [номер]. */
    private void rent(Player p, String[] args, boolean extend) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district " + (extend ? "extend" : "rent") + " <дней> [номер]");
        }
        int days = parseInt(args[0], "количество суток");
        Plot plot = plotOrHere(p, args.length > 1 ? args[1] : null);
        if (extend) {
            plugin.service().extend(p, plot, days);
        } else {
            plugin.service().rent(p, plot, days);
        }
    }

    private void autoRenew(Player p, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district autorenew <on|off> [номер]");
        }
        boolean value = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "вкл", "true" -> true;
            case "off", "выкл", "false" -> false;
            default -> throw new CommandError("Укажите on или off.");
        };
        Plot plot = ownPlot(p, args.length > 1 ? args[1] : null);
        if (plot.status != Plot.Status.RENTED) {
            throw new CommandError("Автопродление есть только у арендованных участков.");
        }
        plugin.service().setAutoRenew(plot, value);
        Text.ok(p, "Автопродление " + (value ? "включено" : "выключено") + ".");
    }

    private void release(Player p, String[] args) {
        String id = null;
        boolean confirmed = false;
        for (String a : args) {
            if (a.equalsIgnoreCase("confirm") || a.equalsIgnoreCase("подтвердить")) {
                confirmed = true;
            } else {
                id = a;
            }
        }
        Plot plot = ownPlot(p, id);
        if (!confirmed) {
            Text.send(p, "<red>Вы отказываетесь от участка <white>" + plot.id + "</white>. Все ваши изменения будут "
                    + "удалены, а предметы вернутся вам.");
            Text.send(p, "<yellow>Для подтверждения введите: <white>/district release " + plot.id + " confirm");
            return;
        }
        plugin.service().release(p, plot);
    }

    private void trust(Player p, String[] args, boolean add) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district " + (add ? "trust" : "untrust") + " <игрок> [номер]");
        }
        Plot plot = ownPlot(p, args.length > 1 ? args[1] : null);
        OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(args[0]);
        }
        if (target == null) {
            throw new CommandError("Игрок «" + args[0] + "» не найден.");
        }
        UUID id = target.getUniqueId();
        if (add) {
            if (id.equals(p.getUniqueId())) {
                throw new CommandError("Вы и так владелец.");
            }
            if (!plot.grants.containsKey(id) && plot.grants.size() >= MAX_TRUSTED) {
                throw new CommandError("Нельзя выдать права больше чем " + MAX_TRUSTED + " игрокам.");
            }
            plot.grantAll(id);
            Text.ok(p, target.getName() + " получил все права на участке " + plot.id
                    + " (строительство, контейнеры, станки). Тонкая настройка: /district perm");
        } else {
            if (!plot.revokeAll(id)) {
                throw new CommandError(target.getName() + " не имеет прав на этом участке.");
            }
            Text.ok(p, target.getName() + " лишён всех прав на участке " + plot.id + ".");
        }
        plugin.journal().add(plot.id, id, p.getName(), Journal.Action.RIGHTS, add ? "все права" : "права сняты");
        plugin.plots().save();
    }

    /** perm <игрок|public> <право> <on|off> [номер] */
    private void perm(Player p, String[] args) {
        if (args.length < 3) {
            throw new CommandError("Использование: /district perm <игрок|public> <build|containers|stations> <on|off> [номер]");
        }
        Plot plot = ownPlot(p, args.length > 3 ? args[3] : null);
        Plot.Perm perm = Plot.Perm.parse(args[1]);
        if (perm == null) {
            throw new CommandError("Право: build, containers или stations.");
        }
        boolean value = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "on", "вкл", "true" -> true;
            case "off", "выкл", "false" -> false;
            default -> throw new CommandError("Укажите on или off.");
        };
        if (args[0].equalsIgnoreCase("public")) {
            String error = plugin.service().setPublic(plot, perm, value);
            if (error != null) {
                throw new CommandError(error);
            }
            Text.ok(p, "Публичный доступ «" + perm.title() + "»: " + (value ? "открыт для всех" : "закрыт") + ".");
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(args[0]);
        }
        if (target == null) {
            throw new CommandError("Игрок «" + args[0] + "» не найден.");
        }
        if (target.getUniqueId().equals(p.getUniqueId())) {
            throw new CommandError("Вы и так владелец.");
        }
        if (value) {
            if (!plot.grants.containsKey(target.getUniqueId()) && plot.grants.size() >= MAX_TRUSTED) {
                throw new CommandError("Нельзя выдать права больше чем " + MAX_TRUSTED + " игрокам.");
            }
            plot.grant(target.getUniqueId(), perm);
        } else {
            plot.revoke(target.getUniqueId(), perm);
        }
        plugin.plots().save();
        plugin.journal().add(plot.id, target.getUniqueId(), p.getName(), Journal.Action.RIGHTS,
                perm.title() + ": " + (value ? "выдано" : "снято"));
        Text.ok(p, target.getName() + ": «" + perm.title() + "» " + (value ? "выдано" : "снято") + ".");
    }

    /** account [deposit|withdraw <сумма> [номер]] - счёт предприятия. */
    private void account(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            withPlayer(sender, p -> accountInfo(p, plotOrHere(p, args.length > 1 ? args[1] : null)));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("deposit") && !sub.equals("withdraw")) {
            throw new CommandError("Использование: /district account [deposit|withdraw] <сумма> [номер]");
        }
        withPlayer(sender, p -> {
            if (args.length < 2) {
                throw new CommandError("Использование: /district account " + sub + " <сумма> [номер]");
            }
            double amount;
            try {
                amount = Double.parseDouble(args[1].replace(',', '.'));
            } catch (NumberFormatException e) {
                throw new CommandError("Сумма должна быть числом.");
            }
            Plot plot = plotOrHere(p, args.length > 2 ? args[2] : null);
            if (sub.equals("deposit")) {
                plugin.power().deposit(p, plot, amount);
            } else {
                plugin.power().withdraw(p, plot, amount);
            }
        });
    }

    private void accountInfo(Player p, Plot plot) {
        plugin.power().touch(plot);
        Text.send(p, "<yellow>Счёт участка <white>" + Text.esc(plot.id) + "</white>: <white>"
                + plugin.economy().format(plugin.power().balance(plot)));
        if (plugin.power().isBilled(plot)) {
            double rate = plot.account.ratePerDay();
            Text.send(p, "<gray> Тариф электричества: <white>" + plugin.economy().format(rate) + " / сутки");
            long left = plot.account.secondsLeft(plugin.power().nowSec());
            Text.send(p, "<gray> Хватит на: " + (left == Long.MAX_VALUE ? "<white>без ограничения"
                    : plugin.power().isPowered(plot) ? "<white>" + Text.duration(left * 1000L) : "<red>обесточено"));
        }
    }

    /** power [calc ...] | power deposit <сумма> [номер] */
    private void power(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("calc")) {
            if (!sender.hasPermission("buildmaster.admin")) {
                throw new CommandError("Недостаточно прав.");
            }
            powerCalc(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("buildmaster.admin")) {
                throw new CommandError("Недостаточно прав.");
            }
            powerAdmin(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("deposit")) {
            account(sender, args);
            return;
        }
        account(sender, new String[0]);
    }

    /** power admin base|progression|usage|mode|coef|discount|add|stats|info ... */
    private void powerAdmin(CommandSender sender, String[] args) {
        if (args.length == 0) {
            throw new CommandError("Использование: /district power admin <info|base|progression|usage|mode|coef|discount|add|stats> ...");
        }
        var power = plugin.power();
        var o = power.overrides();
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> {
                Text.send(sender, "<yellow>Электричество (значение / источник):");
                sender.sendMessage(Text.mm("<gray> режим: <white>" + power.mode() + source(o.mode() != null)));
                sender.sendMessage(Text.mm("<gray> база: <white>" + power.base() + source(o.base() != null)));
                sender.sendMessage(Text.mm("<gray> прогрессия: <white>" + power.progression() + source(o.progression() != null)));
                sender.sendMessage(Text.mm("<gray> цена операции (USAGE): <white>" + power.usageBase() + source(o.usageBase() != null)));
                for (String type : List.of("smelter", "anvil", "alchemy", "enchanting")) {
                    sender.sendMessage(Text.mm("<gray> " + type + ": <white>" + power.coefficient(type)
                            + source(o.coefficient(type) != null)));
                }
            }
            case "base", "progression", "usage" -> {
                if (args.length < 2) {
                    throw new CommandError("Использование: /district power admin " + sub + " <число|reset>");
                }
                Double value = args[1].equalsIgnoreCase("reset") ? null : parseNonNegative(args[1]);
                switch (sub) {
                    case "base" -> o.setBase(value);
                    case "progression" -> o.setProgression(value);
                    default -> o.setUsageBase(value);
                }
                power.saveOverrides();
                power.reloadTariffs(sender.getName(), sub + (value == null ? " сброшено к config.yml" : " = " + value));
                Text.ok(sender, "Готово: " + sub + (value == null ? " сброшено к значению из config.yml." : " = " + value + "."));
            }
            case "mode" -> {
                if (args.length < 2) {
                    throw new CommandError("Использование: /district power admin mode <DAILY|USAGE|reset>");
                }
                String v = args[1].toUpperCase(Locale.ROOT);
                if (v.equals("RESET")) {
                    o.setMode(null);
                } else if (v.equals("DAILY") || v.equals("USAGE")) {
                    o.setMode(v);
                } else {
                    throw new CommandError("Режим: DAILY (суточный тариф) или USAGE (по факту использования).");
                }
                power.saveOverrides();
                power.reloadTariffs(sender.getName(), "режим " + power.mode());
                Text.ok(sender, "Режим тарифа: " + power.mode() + ".");
            }
            case "coef" -> coef(sender, Arrays.copyOfRange(args, 1, args.length));
            case "discount" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district power admin discount <номер> <процент>");
                }
                Plot plot = requirePlot(args[1]);
                double percent;
                try {
                    percent = Double.parseDouble(args[2].replace(',', '.').replace("%", ""));
                } catch (NumberFormatException e) {
                    throw new CommandError("Скидка должна быть числом.");
                }
                String error = power.setDiscount(sender.getName(), plot, percent);
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Скидка участка " + plot.id + ": " + percent + "%.");
            }
            case "add" -> {
                if (args.length < 4) {
                    throw new CommandError("Использование: /district power admin add <номер> <сумма> <причина>");
                }
                Plot plot = requirePlot(args[1]);
                double amount;
                try {
                    amount = Double.parseDouble(args[2].replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new CommandError("Сумма должна быть числом.");
                }
                String error = power.gift(sender.getName(), plot, amount,
                        String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "На счёт участка " + plot.id + " зачислено " + plugin.economy().format(amount) + " из казны.");
            }
            case "stats" -> stats(sender, args.length > 1 ? args[1] : null);
            default -> throw new CommandError("Неизвестная подкоманда. /district power admin info");
        }
    }

    private static String source(boolean overridden) {
        return overridden ? " <gold>(команда админа)" : " <dark_gray>(config.yml)";
    }

    private static Double parseNonNegative(String s) {
        try {
            double v = Double.parseDouble(s.replace(',', '.'));
            if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new CommandError("Нужно число не меньше нуля или reset.");
        }
    }

    /** coef list | set <тип> <число> | reset <тип> */
    private void coef(CommandSender sender, String[] args) {
        var power = plugin.power();
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                for (String type : List.of("smelter", "anvil", "alchemy", "enchanting")) {
                    sender.sendMessage(Text.mm("<gray> " + type + ": <white>" + power.coefficient(type)
                            + source(power.overrides().coefficient(type) != null)));
                }
            }
            case "set" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district power admin coef set <тип> <число>");
                }
                String type = args[1].toLowerCase(Locale.ROOT);
                if (!power.knownType(type)) {
                    throw new CommandError("Неизвестный станок «" + type + "». Доступно: smelter, anvil, alchemy, enchanting.");
                }
                double value = parseNonNegative(args[2]);
                power.overrides().setCoefficient(type, value);
                power.saveOverrides();
                power.reloadTariffs(sender.getName(), "коэффициент " + type + " = " + value);
                Text.ok(sender, "Коэффициент «" + type + "»: " + value + ".");
            }
            case "reset" -> {
                if (args.length < 2) {
                    throw new CommandError("Использование: /district power admin coef reset <тип>");
                }
                String type = args[1].toLowerCase(Locale.ROOT);
                if (!power.overrides().resetCoefficient(type)) {
                    throw new CommandError("Для «" + type + "» нет переопределения.");
                }
                power.saveOverrides();
                power.reloadTariffs(sender.getName(), "коэффициент " + type + " сброшен к config.yml");
                Text.ok(sender, "Коэффициент «" + type + "» снова из config.yml: " + power.coefficient(type) + ".");
            }
            default -> throw new CommandError("Использование: /district power admin coef <list|set|reset> ...");
        }
    }

    /** stats [номер]: использование станков. */
    private void stats(CommandSender sender, String plotId) {
        var stats = plugin.power().stats();
        if (plotId != null) {
            Plot plot = requirePlot(plotId);
            var byType = stats.byType(plot.id);
            if (byType.isEmpty()) {
                Text.send(sender, "<gray>По участку " + Text.esc(plot.id) + " операций пока нет.");
                return;
            }
            Text.send(sender, "<yellow>Станки участка <white>" + Text.esc(plot.id) + "</white>:");
            for (var e : byType.entrySet()) {
                sender.sendMessage(Text.mm("<gray> " + e.getKey() + ": <white>" + e.getValue().operations()
                        + " опер., израсходовано " + plugin.economy().format(e.getValue().spent())));
            }
            return;
        }
        var totals = stats.totalsByType();
        if (totals.isEmpty()) {
            Text.send(sender, "<gray>Операций на станках пока не было.");
            return;
        }
        Text.send(sender, "<yellow>Использование станков (все мастерские):");
        for (var e : totals.entrySet()) {
            sender.sendMessage(Text.mm("<gray> " + e.getKey() + ": <white>" + e.getValue().operations()
                    + " опер., " + plugin.economy().format(e.getValue().spent())));
        }
        Text.send(sender, "<yellow>Самые загруженные мастерские:");
        for (var e : stats.top(5)) {
            sender.sendMessage(Text.mm("<gray> " + Text.esc(e.getKey()) + ": <white>" + e.getValue().operations() + " опер."));
        }
    }

    /** power calc smelter:2 anvil:1 - тариф для набора станков (без аргументов - станки участка, где стоит админ). */
    private void powerCalc(CommandSender sender, String[] args) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                throw new CommandError("Использование: /district power calc smelter:2 anvil:1 ...");
            }
            Plot plot = plotOrHere(p, null);
            counts.putAll(plugin.stations().tariffCounts(plot));
        } else {
            for (String a : args) {
                String[] kv = a.split(":");
                String type = kv[0].toLowerCase(Locale.ROOT);
                if (!plugin.power().knownType(type)) {
                    throw new CommandError("Неизвестный станок «" + type + "». Доступно: smelter, anvil, alchemy, enchanting.");
                }
                int n = 1;
                if (kv.length > 1) {
                    n = parseInt(kv[1], "количество станков");
                }
                counts.merge(type, Math.max(0, n), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            Text.send(sender, "<gray>Станков нет: тариф 0.");
            return;
        }
        Text.send(sender, "<yellow>Расчёт тарифа (база "
                + plugin.economy().format(plugin.power().base())
                + ", прогрессия " + plugin.power().progression() + "):");
        for (var e : plugin.power().breakdown(counts).entrySet()) {
            sender.sendMessage(Text.mm("<gray> " + Text.esc(e.getKey()) + " × " + counts.get(e.getKey())
                    + " (коэф. " + plugin.power().coefficient(e.getKey()) + "): <white>"
                    + plugin.economy().format(e.getValue()) + " / сутки"));
        }
        double total = plugin.power().dailyRate(counts, 0);
        sender.sendMessage(Text.mm("<yellow>Итого: <white>" + plugin.economy().format(total) + " / сутки, "
                + plugin.economy().format(total * 30) + " / 30 суток"));
    }

    private void teleport(Player p, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district tp <номер>");
        }
        Plot plot = plotOrHere(p, args[0]);
        if (!plugin.getConfig().getBoolean("allow-visit-teleport", true) && !plot.isMember(p.getUniqueId())
                && !p.hasPermission("buildmaster.admin")) {
            throw new CommandError("Телепорт доступен только к своим участкам.");
        }
        plugin.service().teleport(p, plot);
    }

    private void replace(Player p, String[] args) {
        if (args.length < 3) {
            throw new CommandError("Использование: /district replace <зона|все> <из> <в>  (например: /district replace фасад oak_planks bricks)");
        }
        Plot plot = plugin.plots().at(p.getLocation());
        if (plot == null) {
            throw new CommandError("Встаньте внутрь своего участка.");
        }
        if (!plot.isOwner(p.getUniqueId())) {
            throw new CommandError("Менять здание может только владелец участка.");
        }
        Box area;
        if (args[0].equalsIgnoreCase("все") || args[0].equalsIgnoreCase("all")) {
            area = plot.box;
        } else {
            Plot.Zone zone = findZone(plot, args[0]);
            if (zone == null) {
                throw new CommandError("Зона «" + args[0] + "» не найдена. Доступно: все"
                        + (plot.zones.isEmpty() ? "" : ", " + String.join(", ", plot.zones.keySet())));
            }
            area = zone.box();
        }
        Material from = Material.matchMaterial(args[1]);
        Material to = Material.matchMaterial(args[2]);
        if (from == null || !from.isBlock()) {
            throw new CommandError("Неизвестный блок: " + args[1]);
        }
        if (to == null || !to.isBlock()) {
            throw new CommandError("Неизвестный блок: " + args[2]);
        }
        plugin.build().replace(p, plot, area, from, to);
    }

    private static Plot.Zone findZone(Plot plot, String name) {
        for (Plot.Zone z : plot.zones.values()) {
            if (z.name().equalsIgnoreCase(name)) {
                return z;
            }
        }
        return null;
    }

    private void zones(Player p, String id) {
        Plot plot = plotOrHere(p, id);
        if (plot.zones.isEmpty()) {
            Text.send(p, "<gray>У участка нет отдельных зон. Можно заменять во всём здании: /district replace все ...");
            return;
        }
        Text.send(p, "<yellow>Зоны участка " + plot.id + ": <white>все, " + Text.esc(String.join(", ", plot.zones.keySet())));
    }

    /** name|desc [номер] <текст...>: текст уходит на проверку администратору. */
    private void submitText(Player p, String[] args, Request.Type type) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district " + (type == Request.Type.NAME ? "name" : "desc") + " [номер] <текст>");
        }
        Plot plot;
        String text;
        Plot byId = args.length > 1 ? plugin.plots().get(args[0]) : null;
        if (byId != null) {
            plot = byId;
            text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        } else {
            plot = plotOrHere(p, null);
            text = String.join(" ", args);
        }
        if (!plot.isOwner(p.getUniqueId()) && !p.hasPermission("buildmaster.admin")) {
            throw new CommandError("Это не ваш участок.");
        }
        String error = plugin.moderation().submit(p, plot, type, text);
        if (error != null) {
            throw new CommandError(error);
        }
    }

    private void autoReset(Player p, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district autoreset <on|off> [номер]");
        }
        boolean value = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "вкл", "true" -> true;
            case "off", "выкл", "false" -> false;
            default -> throw new CommandError("Укажите on или off.");
        };
        Plot plot = ownPlot(p, args.length > 1 ? args[1] : null);
        plugin.service().setResetOnRelease(plot, value);
        Text.ok(p, "Сброс здания при отказе от участка: " + (value ? "включён" : "выключен") + ".");
    }

    // ---------------------------------------------------------------- администратор

    private void create(Player p, String[] args) {
        if (args.length < 4) {
            throw new CommandError("Использование: /district create <номер> <тип> <цена|-> <аренда в сутки|->  (типы: "
                    + categoryNames() + ")");
        }
        String id = args[0];
        Category category = Category.parse(args[1]);
        if (category == null) {
            throw new CommandError("Неизвестный тип «" + args[1] + "». Доступно: " + categoryNames());
        }
        if (!ID.matcher(id).matches()) {
            throw new CommandError("Номер участка: латиница, цифры, _ и -, до 32 символов.");
        }
        if (plugin.plots().get(id) != null || plugin.service().isCreating(id)) {
            throw new CommandError("Участок с таким номером уже есть.");
        }
        double price = parsePrice(args[2]);
        double rent = parsePrice(args[3]);
        if (price < 0 && rent < 0) {
            throw new CommandError("Нужна хотя бы цена покупки или аренды.");
        }
        SelectionListener.Selection sel = plugin.selection().get(p);
        Box box = sel == null ? null : sel.box();
        if (box == null) {
            throw new CommandError("Сначала выделите область: /district wand (или pos1 и pos2).");
        }
        if (!p.getWorld().getName().equals(sel.world)) {
            throw new CommandError("Выделение сделано в другом мире.");
        }
        long max = plugin.getConfig().getLong("max-plot-volume", 2_000_000);
        if (box.volume() > max) {
            throw new CommandError("Слишком большая область: " + box.volume() + " блоков (максимум " + max + ").");
        }
        Plot overlap = plugin.plots().overlapping(sel.world, box);
        if (overlap != null) {
            throw new CommandError("Область пересекается с участком " + overlap.id + ".");
        }
        plugin.service().create(p, id, category, box, price, rent);
    }

    private Plot requirePlot(String id) {
        Plot plot = plugin.plots().get(id);
        if (plot == null) {
            throw new CommandError("Участок «" + id + "» не найден.");
        }
        return plot;
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district delete <номер>");
        }
        Plot plot = requirePlot(args[0]);
        if (!plot.isFree() || plugin.service().isLocked(plot)) {
            throw new CommandError("Удалять можно только свободный участок. Сначала: /district evict " + plot.id);
        }
        plugin.plots().remove(plot);
        plugin.snapshots().delete(plugin.plots().snapshotDir(plot.id));
        plugin.ledgers().delete(plot.id);
        plugin.holograms().remove(plot);
        plugin.plots().save();
        plugin.journal().add(plot.id, null, sender.getName(), Journal.Action.EVICTED, "Участок удалён");
        Text.ok(sender, "Участок " + plot.id + " удалён.");
    }

    /** evict | seize | reset <номер> <причина...>: любое решение администратора требует причину. */
    private void adminDecision(CommandSender sender, String[] args, Journal.Action action) {
        String name = action == Journal.Action.EVICTED ? "evict" : action == Journal.Action.SEIZED ? "seize" : "reset";
        if (args.length < 2) {
            throw new CommandError("Использование: /district " + name + " <номер> <причина>  (причина обязательна)");
        }
        Plot plot = requirePlot(args[0]);
        if (plugin.service().isLocked(plot)) {
            throw new CommandError("Участок сейчас обрабатывается.");
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (action == Journal.Action.RESET) {
            plugin.service().reset(plot, sender.getName(), reason);
            Text.ok(sender, "Здание участка " + plot.id + " сбрасывается к исходному виду.");
            return;
        }
        if (plot.isFree()) {
            throw new CommandError("Участок свободен. Для сброса здания: /district reset " + plot.id + " <причина>");
        }
        plugin.service().evict(plot, new PlotService.Release(sender.getName(), reason, action,
                action == Journal.Action.SEIZED, Plot.RestoreMode.FULL));
        Text.ok(sender, "Участок " + plot.id + (action == Journal.Action.SEIZED
                ? " изъят, вещи арестованы." : " освобождён, вещи отправлены в хранилище владельца."));
    }

    private void holo(Player p, String[] args) {
        Plot plot = plotOrHere(p, args.length > 0 ? args[0] : null);
        Location l = p.getEyeLocation();
        plot.hasHolo = true;
        plot.holoX = l.getX();
        plot.holoY = l.getY();
        plot.holoZ = l.getZ();
        plugin.plots().save();
        plugin.holograms().update(plot);
        Text.ok(p, "Голограмма участка " + plot.id + " перенесена в вашу позицию.");
    }

    private void category(CommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new CommandError("Использование: /district category <номер> <тип>  (" + categoryNames() + ")");
        }
        Plot plot = requirePlot(args[0]);
        Category c = Category.parse(args[1]);
        if (c == null) {
            throw new CommandError("Неизвестный тип. Доступно: " + categoryNames());
        }
        plot.category = c;
        plugin.plots().save();
        plugin.holograms().update(plot);
        Text.ok(sender, "Тип участка " + plot.id + ": " + c.title());
    }

    private static String categoryNames() {
        StringBuilder sb = new StringBuilder();
        for (Category c : Category.values()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(c.key());
        }
        return sb.toString();
    }

    /** arrest list | release|delete|auction <игрок> <причина...> */
    private void arrest(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            List<UUID> players = plugin.storage().playersWithState(StorageEntry.State.ARRESTED);
            if (players.isEmpty()) {
                Text.send(sender, "<gray>Арестованных вещей нет.");
            }
            for (UUID id : players) {
                Text.send(sender, "<yellow>" + Text.esc(String.valueOf(Bukkit.getOfflinePlayer(id).getName()))
                        + ": " + plugin.storage().countItems(id, StorageEntry.State.ARRESTED) + " шт.");
            }
            return;
        }
        if (args.length < 3) {
            throw new CommandError("Использование: /district arrest list | <release|delete|auction> <игрок> <причина>");
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            throw new CommandError("Игрок «" + args[1] + "» не найден.");
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        Journal.Action action;
        int n;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "release" -> {
                n = plugin.storage().decideArrested(target.getUniqueId(), StorageEntry.State.AVAILABLE);
                action = Journal.Action.STORAGE_RELEASED;
            }
            case "delete" -> {
                n = plugin.storage().decideArrested(target.getUniqueId(), null);
                action = Journal.Action.STORAGE_DELETED;
            }
            case "auction" -> {
                n = plugin.storage().decideArrested(target.getUniqueId(), StorageEntry.State.AUCTION);
                action = Journal.Action.STORAGE_AUCTION;
            }
            default -> throw new CommandError("Действие: release, delete или auction.");
        }
        if (n == 0) {
            throw new CommandError("У игрока нет арестованных вещей.");
        }
        plugin.journal().add("-", target.getUniqueId(), sender.getName(), action, reason);
        Player online = target.getPlayer();
        if (online != null) {
            Text.send(online, "<yellow>" + action.title() + ". Причина: " + Text.esc(reason));
        }
        Text.ok(sender, action.title() + ": записей " + n + ".");
    }

    /** treasury | treasury pay <игрок> <сумма> <причина...> */
    private void treasury(CommandSender sender, String[] args) {
        if (args.length == 0) {
            Text.send(sender, "<yellow>Казна: <white>" + plugin.economy().format(plugin.treasury().balance()));
            var log = plugin.treasury().log();
            for (int i = Math.max(0, log.size() - 8); i < log.size(); i++) {
                var tx = log.get(i);
                sender.sendMessage(Text.mm("<gray> " + Text.date(tx.time()) + " <white>" + String.format("%+.2f", tx.amount())
                        + " <gray>" + Text.esc(tx.reason())));
            }
            return;
        }
        if (!args[0].equalsIgnoreCase("pay") || args.length < 4) {
            throw new CommandError("Использование: /district treasury | treasury pay <игрок> <сумма> <причина>");
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            throw new CommandError("Игрок «" + args[1] + "» не найден.");
        }
        double amount;
        try {
            amount = Double.parseDouble(args[2].replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new CommandError("Сумма должна быть числом.");
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        if (amount <= 0 || !plugin.treasury().withdraw(amount, sender.getName(), "Выплата " + target.getName() + ": " + reason)) {
            throw new CommandError("В казне недостаточно средств или сумма неверна.");
        }
        if (!plugin.economy().deposit(target, amount)) {
            plugin.treasury().deposit(amount, "Возврат: выплата не прошла");
            plugin.saveTreasury();
            throw new CommandError("Не удалось зачислить деньги игроку.");
        }
        plugin.saveTreasury();
        plugin.journal().add("-", target.getUniqueId(), sender.getName(), Journal.Action.TREASURY,
                plugin.economy().format(amount) + ": " + reason);
        Text.ok(sender, "Выплачено " + plugin.economy().format(amount) + " игроку " + target.getName() + " из казны.");
    }

    private void setPrice(CommandSender sender, String[] args, boolean buy) {
        if (args.length < 2) {
            throw new CommandError("Использование: /district " + (buy ? "setprice" : "setrent") + " <номер> <цена|->");
        }
        Plot plot = requirePlot(args[0]);
        double value = parsePrice(args[1]);
        if (buy) {
            plot.price = value;
        } else {
            plot.rentPerDay = value;
        }
        plugin.plots().save();
        Text.ok(sender, (buy ? "Цена покупки" : "Аренда в сутки") + " участка " + plot.id + ": " + money(value));
    }

    private void setSpawn(Player p, String[] args) {
        Plot plot = plotOrHere(p, args.length > 0 ? args[0] : null);
        Location l = p.getLocation();
        if (!plot.world.equals(l.getWorld().getName()) || !plot.box.contains(l.getBlockX(), l.getBlockY(), l.getBlockZ())) {
            throw new CommandError("Точка телепорта должна быть внутри участка.");
        }
        plot.hasSpawn = true;
        plot.spawnX = l.getX();
        plot.spawnY = l.getY();
        plot.spawnZ = l.getZ();
        plot.spawnYaw = l.getYaw();
        plot.spawnPitch = l.getPitch();
        plugin.plots().save();
        Text.ok(p, "Точка телепорта участка " + plot.id + " установлена.");
    }

    private void resnapshot(Player p, String[] args) {
        Plot plot = plotOrHere(p, args.length > 0 ? args[0] : null);
        if (!plot.isFree() || plugin.service().isLocked(plot)) {
            throw new CommandError("Обновлять исходный вид можно только у свободного участка.");
        }
        plugin.service().resnapshot(p, plot);
    }

    private void zone(Player p, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district zone <add|remove|list> ...");
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district zone add <номер> <имя> [блок-иконка]");
                }
                Plot plot = requirePlot(args[1]);
                String name = args[2].toLowerCase(Locale.ROOT);
                if (!ZONE.matcher(name).matches()) {
                    throw new CommandError("Имя зоны: буквы, цифры, _ и -, до 24 символов.");
                }
                if (name.equals("все") || name.equals("all")) {
                    throw new CommandError("Это имя зарезервировано.");
                }
                if (plot.zones.size() >= MAX_ZONES && !plot.zones.containsKey(name)) {
                    throw new CommandError("Не больше " + MAX_ZONES + " зон на участок.");
                }
                Material icon = Material.BRICKS;
                if (args.length > 3) {
                    icon = Material.matchMaterial(args[3]);
                    if (icon == null || !icon.isItem()) {
                        throw new CommandError("Неизвестный предмет для иконки: " + args[3]);
                    }
                }
                SelectionListener.Selection sel = plugin.selection().get(p);
                Box box = sel == null ? null : sel.box();
                if (box == null || !plot.world.equals(sel.world)) {
                    throw new CommandError("Сначала выделите область зоны внутри участка.");
                }
                if (!plot.box.contains(box)) {
                    throw new CommandError("Зона должна целиком находиться внутри участка " + plot.id + ".");
                }
                plot.zones.put(name, new Plot.Zone(name, box, icon.name()));
                plugin.plots().save();
                Text.ok(p, "Зона «" + name + "» (" + box.describe() + ") добавлена к участку " + plot.id + ".");
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district zone remove <номер> <имя>");
                }
                Plot plot = requirePlot(args[1]);
                if (plot.zones.remove(args[2].toLowerCase(Locale.ROOT)) == null) {
                    throw new CommandError("Такой зоны нет.");
                }
                plugin.plots().save();
                Text.ok(p, "Зона удалена.");
            }
            case "list" -> zones(p, args.length > 1 ? args[1] : null);
            default -> throw new CommandError("Использование: /district zone <add|remove|list> ...");
        }
    }

    // ---------------------------------------------------------------- автодополнение

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        boolean admin = sender.hasPermission("buildmaster.admin");
        if (args.length == 1) {
            result.addAll(PLAYER_SUBS);
            if (admin) {
                result.addAll(ADMIN_SUBS);
            }
            return filter(result, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        int n = args.length;
        String current = args[n - 1];
        Plot here = sender instanceof Player p ? plugin.plots().at(p.getLocation()) : null;
        switch (sub) {
            case "info", "buy", "tp", "zones", "resnapshot", "setspawn", "delete", "evict", "seize", "reset", "holo" -> {
                if (n == 2) {
                    result.addAll(plotIds(sub.equals("buy") ? Plot.Status.FREE : null));
                }
            }
            case "setprice", "setrent" -> {
                if (n == 2) {
                    result.addAll(plotIds(null));
                } else if (n == 3) {
                    result.add("-");
                }
            }
            case "rent", "extend" -> {
                if (n == 2) {
                    result.addAll(List.of("1", "7", "14", "30"));
                } else if (n == 3) {
                    result.addAll(plotIds(null));
                }
            }
            case "autorenew" -> {
                if (n == 2) {
                    result.addAll(List.of("on", "off"));
                } else if (n == 3) {
                    result.addAll(plotIds(null));
                }
            }
            case "release" -> {
                result.addAll(plotIds(null));
                result.add("confirm");
            }
            case "trust", "untrust" -> {
                if (n == 2) {
                    Bukkit.getOnlinePlayers().forEach(pl -> result.add(pl.getName()));
                } else if (n == 3) {
                    result.addAll(plotIds(null));
                }
            }
            case "replace" -> {
                if (n == 2) {
                    result.add("все");
                    if (here != null) {
                        result.addAll(here.zones.keySet());
                    }
                } else if (n == 3) {
                    addMaterials(result, false);
                } else if (n == 4) {
                    addMaterials(result, true);
                }
            }
            case "create" -> {
                if (n == 3) {
                    for (Category c : Category.values()) {
                        result.add(c.key());
                    }
                } else if (n == 4 || n == 5) {
                    result.add("-");
                }
            }
            case "category" -> {
                if (n == 2) {
                    result.addAll(plotIds(null));
                } else if (n == 3) {
                    for (Category c : Category.values()) {
                        result.add(c.key());
                    }
                }
            }
            case "perm" -> {
                if (n == 2) {
                    result.add("public");
                    Bukkit.getOnlinePlayers().forEach(pl -> result.add(pl.getName()));
                } else if (n == 3) {
                    result.addAll(List.of("build", "containers", "stations"));
                } else if (n == 4) {
                    result.addAll(List.of("on", "off"));
                } else if (n == 5) {
                    result.addAll(plotIds(null));
                }
            }
            case "account" -> {
                if (n == 2) {
                    result.addAll(List.of("info", "deposit", "withdraw"));
                }
            }
            case "power" -> {
                if (n == 2) {
                    result.addAll(List.of("deposit", "calc", "admin"));
                } else if (n >= 3 && args[1].equalsIgnoreCase("calc")) {
                    result.addAll(List.of("smelter:", "anvil:", "alchemy:", "enchanting:"));
                } else if (args[1].equalsIgnoreCase("admin")) {
                    if (n == 3) {
                        result.addAll(List.of("info", "base", "progression", "usage", "mode", "coef", "discount", "add", "stats"));
                    } else if (n == 4 && args[2].equalsIgnoreCase("mode")) {
                        result.addAll(List.of("DAILY", "USAGE", "reset"));
                    } else if (n == 4 && args[2].equalsIgnoreCase("coef")) {
                        result.addAll(List.of("list", "set", "reset"));
                    } else if (n == 4 && List.of("discount", "add", "stats").contains(args[2].toLowerCase(Locale.ROOT))) {
                        result.addAll(plotIds(null));
                    } else if (n == 5 && args[2].equalsIgnoreCase("coef")) {
                        result.addAll(List.of("smelter", "anvil", "alchemy", "enchanting"));
                    }
                }
            }
            case "autoreset" -> {
                if (n == 2) {
                    result.addAll(List.of("on", "off"));
                }
            }
            case "arrest" -> {
                if (n == 2) {
                    result.addAll(List.of("list", "release", "delete", "auction"));
                } else if (n == 3) {
                    Bukkit.getOnlinePlayers().forEach(pl -> result.add(pl.getName()));
                }
            }
            case "treasury" -> {
                if (n == 2) {
                    result.add("pay");
                } else if (n == 3) {
                    Bukkit.getOnlinePlayers().forEach(pl -> result.add(pl.getName()));
                }
            }
            case "zone" -> {
                if (n == 2) {
                    result.addAll(List.of("add", "remove", "list"));
                } else if (n == 3) {
                    result.addAll(plotIds(null));
                } else if (n == 4 && args[1].equalsIgnoreCase("remove")) {
                    Plot plot = plugin.plots().get(args[2]);
                    if (plot != null) {
                        result.addAll(plot.zones.keySet());
                    }
                } else if (n == 5 && args[1].equalsIgnoreCase("add")) {
                    addMaterials(result, false);
                }
            }
            default -> { }
        }
        return filter(result, current);
    }

    private List<String> plotIds(Plot.Status only) {
        List<String> ids = new ArrayList<>();
        for (Plot p : plugin.plots().all()) {
            if (only == null || p.status == only) {
                ids.add(p.id);
            }
        }
        return ids;
    }

    private void addMaterials(List<String> out, boolean targets) {
        if (targets) {
            for (Material m : plugin.rules().replaceTargets()) {
                out.add(m.getKey().getKey());
            }
        } else {
            for (Material m : Material.values()) {
                if (!m.isLegacy() && m.isBlock() && !m.isAir()) {
                    out.add(m.getKey().getKey());
                }
            }
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                result.add(o);
            }
        }
        return result;
    }
}
