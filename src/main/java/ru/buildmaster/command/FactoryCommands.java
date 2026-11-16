package ru.buildmaster.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.gui.FactoryMenu;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.ProductionCatalog;
import ru.buildmaster.service.UpgradeCatalog;
import ru.buildmaster.util.Text;

/** Команды производства и улучшений: /district factory ... */
final class FactoryCommands {

    private final BuildMasterPlugin plugin;

    FactoryCommands(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        throw new CommandError("Эту команду можно выполнить только игроку.");
    }

    private Plot workshop(CommandSender sender, String id) {
        Plot plot = id != null ? plugin.plots().get(id) : sender instanceof Player p ? plugin.plots().at(p.getLocation()) : null;
        if (plot == null) {
            throw new CommandError(id == null ? "Вы не стоите на участке. Укажите номер мастерской." : "Участок «" + id + "» не найден.");
        }
        if (plot.category != Category.WORKSHOP) {
            throw new CommandError("Производство есть только у мастерских.");
        }
        return plot;
    }

    private static void check(String error) {
        if (error != null) {
            throw new CommandError(error);
        }
    }

    void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "menu" -> new FactoryMenu(plugin, player(sender), workshop(sender, rest.length > 0 ? rest[0] : null), null).open();
            case "info" -> info(sender, workshop(sender, rest.length > 0 ? rest[0] : null));
            case "levels" -> levels(sender);
            case "install" -> {
                need(rest, 2, "install <мастерская> <линия>");
                check(plugin.factory().install(player(sender), workshop(sender, rest[0]), rest[1]));
            }
            case "remove" -> {
                need(rest, 2, "remove <мастерская> <линия>");
                check(plugin.factory().remove(player(sender), workshop(sender, rest[0]), rest[1]));
            }
            case "load" -> {
                need(rest, 2, "load <мастерская> <линия> [количество]");
                int amount = 0;
                if (rest.length > 2 && !rest[2].equalsIgnoreCase("all")) {
                    try {
                        amount = Integer.parseInt(rest[2]);
                    } catch (NumberFormatException e) {
                        throw new CommandError("Количество - целое число или all.");
                    }
                    if (amount <= 0) {
                        throw new CommandError("Количество должно быть больше нуля.");
                    }
                }
                check(plugin.factory().load(player(sender), workshop(sender, rest[0]), rest[1], amount));
            }
            case "collect" -> {
                need(rest, 1, "collect <мастерская> [линия]");
                check(plugin.factory().collect(player(sender), workshop(sender, rest[0]), rest.length > 1 ? rest[1] : null));
            }
            case "upgrade" -> check(plugin.factory().upgrade(player(sender), workshop(sender, rest.length > 0 ? rest[0] : null)));
            default -> throw new CommandError("Использование: /district factory [menu|info|levels|install|remove|load|collect|upgrade]");
        }
    }

    private static void need(String[] rest, int n, String usage) {
        if (rest.length < n) {
            throw new CommandError("Использование: /district factory " + usage);
        }
    }

    private void info(CommandSender sender, Plot plot) {
        UpgradeCatalog.Level level = plugin.upgrades().of(plot);
        Text.send(sender, "<yellow>Производство мастерской <white>" + Text.esc(plot.id) + "</white>: уровень <white>" + plot.level
                + "</white>, линий " + plot.lines.size() + "/" + level.maxLines() + ", скорость ×" + level.speed()
                + ", склад ×" + level.stock() + ", буфер ×" + level.buffer());
        for (ProductionCatalog.Line def : plugin.production().lines()) {
            Plot.Line line = plot.lines.get(def.id());
            if (line == null) {
                sender.sendMessage(Text.mm("<gray> <white>" + Text.esc(def.id()) + "</white> - " + Text.esc(def.title()) + ": не установлена, "
                        + plugin.economy().format(def.installPrice()) + ", станок: " + def.station()));
            } else {
                sender.sendMessage(Text.mm("<gray> <white>" + Text.esc(def.id()) + "</white> - " + Text.esc(def.title())
                        + ": сырьё <white>" + line.input + "/" + plugin.factory().stockCapacity(plot) + "</white>, продукция <white>"
                        + line.output + "/" + plugin.factory().bufferCapacity(plot, def) + "</white>"));
            }
        }
    }

    private void levels(CommandSender sender) {
        Text.send(sender, "<yellow>Уровни улучшений:");
        for (UpgradeCatalog.Level l : plugin.upgrades().levels()) {
            sender.sendMessage(Text.mm("<gray> Уровень <white>" + l.level() + "</white>: " + plugin.economy().format(l.price())
                    + " - линий " + l.maxLines() + ", скорость ×" + l.speed() + ", склад ×" + l.stock() + ", буфер ×" + l.buffer()
                    + ", очередь заказов +" + l.orderQueue()));
        }
    }

    List<String> complete(CommandSender sender, String[] args) {
        int n = args.length; // args[0] = "factory"
        if (n == 2) {
            return List.of("menu", "info", "levels", "install", "remove", "load", "collect", "upgrade");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (n == 3 && List.of("menu", "info", "install", "remove", "load", "collect", "upgrade").contains(sub)) {
            return plugin.plots().all().stream().filter(p -> p.category == Category.WORKSHOP).map(p -> p.id).toList();
        }
        if (n == 4 && List.of("install", "remove", "load", "collect").contains(sub)) {
            return plugin.production().lines().stream().map(ProductionCatalog.Line::id).toList();
        }
        if (n == 5 && sub.equals("load")) {
            return List.of("all", "64", "256");
        }
        return List.of();
    }
}
