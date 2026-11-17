package ru.buildmaster.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.gui.BankMenu;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.Journal;
import ru.buildmaster.util.Text;

/** Команды банка: /district bank ... (настройки и проверка счетов - только с правом buildmaster.bank). */
final class BankCommands {

    private final BuildMasterPlugin plugin;

    BankCommands(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    private void requireBank(CommandSender sender) {
        if (!sender.hasPermission("buildmaster.bank")) {
            throw new CommandError("Недостаточно прав.");
        }
    }

    private Plot plot(String id) {
        Plot p = plugin.plots().get(id);
        if (p == null) {
            throw new CommandError("Участок «" + id + "» не найден.");
        }
        return p;
    }

    private static Double percent(String s) {
        if (s.equalsIgnoreCase("reset")) {
            return null;
        }
        try {
            double v = Double.parseDouble(s.replace(',', '.').replace("%", ""));
            if (v < 0 || v > 100 || Double.isNaN(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new CommandError("Нужен процент от 0 до 100 (или reset).");
        }
    }

    void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "menu" -> {
                if (!(sender instanceof Player p)) {
                    throw new CommandError("Меню банка доступно только игроку. Условия: /district bank info");
                }
                if (!plugin.htmlMenus().open(p, ru.buildmaster.html.HtmlMenus.Kind.BANK)) {
                    new BankMenu(plugin, p).open();
                }
            }
            case "classic" -> {
                if (!(sender instanceof Player p)) {
                    throw new CommandError("Меню банка доступно только игроку.");
                }
                new BankMenu(plugin, p).open();
            }
            case "info" -> info(sender);
            case "statement" -> statement(sender, rest.length > 0 ? rest[0] : null);
            case "accounts" -> accounts(sender);
            case "account" -> {
                requireBank(sender);
                if (rest.length < 1) {
                    throw new CommandError("Использование: /district bank account <номер>");
                }
                account(sender, plot(rest[0]));
            }
            case "admin" -> {
                requireBank(sender);
                admin(sender, rest);
            }
            default -> throw new CommandError("Использование: /district bank [info|statement|accounts|account|admin]");
        }
    }

    // ------------------------------------------------------------ для всех

    private void info(CommandSender sender) {
        var bank = plugin.bank();
        Text.send(sender, "<yellow>Условия банка:");
        sender.sendMessage(Text.mm("<gray> комиссия за пополнение: <white>" + bank.depositCommission() + "%"));
        sender.sendMessage(Text.mm("<gray> комиссия за снятие: <white>" + bank.withdrawCommission() + "%"));
        sender.sendMessage(Text.mm("<gray> штраф при изъятии участка: <white>" + bank.seizurePenalty() + "% остатка счёта"));
        sender.sendMessage(Text.mm("<gray> налог на доход от сдачи в аренду: <white>" + bank.rentTax() + "%"));
        sender.sendMessage(Text.mm("<gray> операции только в банке: <white>" + (bank.onlyInBank() ? "да" : "нет")));
        StringBuilder sb = new StringBuilder();
        for (Category c : Category.values()) {
            if (!c.isAdminOnly() && bank.propertyTax(c) > 0) {
                sb.append(sb.length() == 0 ? "" : ", ").append(c.title()).append(' ')
                        .append(plugin.economy().format(bank.propertyTax(c)));
            }
        }
        sender.sendMessage(Text.mm("<gray> налог на имущество (за "
                + plugin.getConfig().getInt("bank.property-tax-period-days", 7) + " сут.): <white>"
                + (sb.length() == 0 ? "не взимается" : Text.esc(sb.toString()))));
    }

    /** Выписка по счёту: свой участок, а администратор банка - любой. */
    void statement(CommandSender sender, String plotId) {
        Plot plot;
        if (plotId != null) {
            plot = plot(plotId);
        } else if (sender instanceof Player p) {
            plot = plugin.plots().at(p.getLocation());
            if (plot == null) {
                throw new CommandError("Вы не стоите на участке. Укажите его номер.");
            }
        } else {
            throw new CommandError("Укажите номер участка.");
        }
        boolean mine = sender instanceof Player p && plot.isOwner(p.getUniqueId());
        if (!mine && !sender.hasPermission("buildmaster.bank")) {
            throw new CommandError("Выписку можно посмотреть только по своему счёту.");
        }
        printStatement(sender, plot);
    }

    private void printStatement(CommandSender sender, Plot plot) {
        Text.send(sender, "<yellow>Выписка по счёту участка <white>" + Text.esc(plot.id) + "</white>: "
                + plugin.economy().format(plugin.power().balance(plot))
                + (plot.taxDebt > 1e-9 ? " <red>(долг по налогу " + plugin.economy().format(plot.taxDebt) + ")" : ""));
        List<Journal.Entry> list = plugin.bank().statement(plot.id, 10);
        if (list.isEmpty()) {
            sender.sendMessage(Text.mm("<gray> операций пока нет"));
        }
        for (Journal.Entry e : list) {
            sender.sendMessage(Text.mm("<gray> " + Text.date(e.time()) + " <white>" + e.action().title()
                    + " <gray>" + Text.esc(e.reason())));
        }
    }

    // ------------------------------------------------------------ для банка

    private void accounts(CommandSender sender) {
        requireBank(sender);
        List<Plot> list = plugin.bank().accounts();
        Text.send(sender, "<yellow>Счета предприятий (" + list.size() + "), по убыванию баланса:");
        int shown = 0;
        for (Plot p : list) {
            if (shown++ >= 15) {
                break;
            }
            sender.sendMessage(Text.mm("<gray> " + Text.esc(p.id) + " (" + p.category.title() + ", "
                    + Text.esc(String.valueOf(p.ownerName)) + "): <white>" + plugin.economy().format(p.account.rawBalance())
                    + (p.taxDebt > 1e-9 ? " <red>долг " + plugin.economy().format(p.taxDebt) : "")));
        }
    }

    private void account(CommandSender sender, Plot plot) {
        if (plot.isFree()) {
            throw new CommandError("У свободного участка нет счёта.");
        }
        plugin.power().touch(plot);
        printStatement(sender, plot);
    }

    private void admin(CommandSender sender, String[] args) {
        if (args.length == 0) {
            throw new CommandError("Использование: /district bank admin <commission|penalty|tax|only-in-bank|forgive> ...");
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "commission" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district bank admin commission <deposit|withdraw> <процент|reset>");
                }
                Double v = percent(args[2]);
                String which = args[1].toLowerCase(Locale.ROOT);
                String done;
                switch (which) {
                    case "deposit" -> done = plugin.bank().setDepositCommission(v, sender.getName());
                    case "withdraw" -> done = plugin.bank().setWithdrawCommission(v, sender.getName());
                    default -> throw new CommandError("Укажите deposit или withdraw.");
                }
                Text.ok(sender, "Готово: " + done + ".");
            }
            case "penalty" -> {
                if (args.length < 2) {
                    throw new CommandError("Использование: /district bank admin penalty <процент|reset>");
                }
                Double v = percent(args[1]);
                Text.ok(sender, "Готово: " + plugin.bank().setSeizurePenalty(v, sender.getName()) + ".");
            }
            case "tax" -> tax(sender, Arrays.copyOfRange(args, 1, args.length));
            case "only-in-bank" -> {
                if (args.length < 2) {
                    throw new CommandError("Использование: /district bank admin only-in-bank <on|off|reset>");
                }
                Boolean v = switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "on", "вкл", "true" -> true;
                    case "off", "выкл", "false" -> false;
                    case "reset" -> null;
                    default -> throw new CommandError("Укажите on, off или reset.");
                };
                Text.ok(sender, "Готово: " + plugin.bank().setOnlyInBank(v, sender.getName()) + ".");
            }
            case "forgive" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district bank admin forgive <номер> <причина>");
                }
                String error = plugin.bank().forgiveDebt(plot(args[1]), sender.getName(),
                        String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Долг по налогу списан.");
            }
            default -> throw new CommandError("Неизвестная подкоманда. Использование: /district bank admin ...");
        }
    }

    /** tax rent <процент|reset> | tax property <тип> <сумма|reset> */
    private void tax(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("rent")) {
            Double v = percent(args[1]);
            Text.ok(sender, "Готово: " + plugin.bank().setRentTax(v, sender.getName()) + ".");
            return;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("property")) {
            Category c = Category.parse(args[1]);
            if (c == null || c.isAdminOnly() || c.isRentOnly()) {
                throw new CommandError("Укажите тип: house, shop, workshop, restaurant или warehouse.");
            }
            Double v = null;
            if (!args[2].equalsIgnoreCase("reset")) {
                try {
                    v = Double.parseDouble(args[2].replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new CommandError("Сумма должна быть числом (или reset).");
                }
                if (v < 0 || v.isNaN() || v.isInfinite()) {
                    throw new CommandError("Сумма не может быть отрицательной.");
                }
            }
            Text.ok(sender, "Готово: " + plugin.bank().setPropertyTax(c, v, sender.getName()) + ".");
            return;
        }
        throw new CommandError("Использование: /district bank admin tax rent <процент|reset> | tax property <тип> <сумма|reset>");
    }

    // ------------------------------------------------------------ автодополнение

    List<String> complete(CommandSender sender, String[] args) {
        int n = args.length; // args[0] = "bank"
        if (n == 2) {
            return sender.hasPermission("buildmaster.bank")
                    ? List.of("menu", "info", "statement", "accounts", "account", "admin")
                    : List.of("menu", "info", "statement");
        }
        if (n == 3 && args[1].equalsIgnoreCase("admin")) {
            return List.of("commission", "penalty", "tax", "only-in-bank", "forgive");
        }
        if (n == 4 && args[1].equalsIgnoreCase("admin")) {
            switch (args[2].toLowerCase(Locale.ROOT)) {
                case "commission" -> {
                    return List.of("deposit", "withdraw");
                }
                case "tax" -> {
                    return List.of("rent", "property");
                }
                case "only-in-bank" -> {
                    return List.of("on", "off", "reset");
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (n == 5 && args[1].equalsIgnoreCase("admin") && args[2].equalsIgnoreCase("tax")
                && args[3].equalsIgnoreCase("property")) {
            return List.of("house", "shop", "workshop", "restaurant", "warehouse");
        }
        return List.of();
    }
}
