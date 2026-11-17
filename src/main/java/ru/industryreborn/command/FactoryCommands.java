package ru.industryreborn.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.gui.FactoryMenu;
import ru.industryreborn.gui.MachineMenu;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.ProductionService;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.service.StationService;
import ru.industryreborn.util.Text;

/** Команды производства: /district factory, /district machine, /district chest. */
final class FactoryCommands {

    private final IndustryRebornPlugin plugin;

    FactoryCommands(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        throw new CommandError("Эту команду можно выполнить только игроку.");
    }

    private Plot workshop(CommandSender sender, String id) {
        Plot plot;
        if (id != null) {
            plot = plugin.plots().get(id);
            if (plot == null) {
                throw new CommandError("Участок «" + id + "» не найден.");
            }
        } else {
            Player p = player(sender);
            plot = plugin.plots().at(p.getLocation());
            if (plot == null || plot.category != Category.WORKSHOP || !plot.isOwner(p.getUniqueId())) {
                List<Plot> mine = plugin.plots().ownedBy(p.getUniqueId()).stream()
                        .filter(x -> x.category == Category.WORKSHOP).toList();
                if (mine.size() == 1) {
                    plot = mine.get(0);
                } else {
                    throw new CommandError("Укажите мастерскую: /district factory <подкоманда> <мастерская> или встаньте в неё.");
                }
            }
        }
        if (plot.category != Category.WORKSHOP) {
            throw new CommandError("«" + plot.id + "» не мастерская.");
        }
        return plot;
    }

    private static void check(String error) {
        if (error != null) {
            throw new CommandError(error);
        }
    }

    // ------------------------------------------------------------ factory

    void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        String id = rest.length > 0 ? rest[0] : null;
        switch (sub) {
            case "menu" -> new FactoryMenu(plugin, player(sender), workshop(sender, id), null).open();
            case "info" -> info(sender, workshop(sender, id));
            case "levels" -> levels(sender);
            case "load" -> {
                int amount = 0;
                if (rest.length > 1 && !rest[1].equalsIgnoreCase("all")) {
                    try {
                        amount = Integer.parseInt(rest[1]);
                    } catch (NumberFormatException e) {
                        throw new CommandError("Количество - целое число или all.");
                    }
                    if (amount <= 0) {
                        throw new CommandError("Количество должно быть больше нуля.");
                    }
                }
                check(plugin.factory().load(player(sender), workshop(sender, id), amount));
            }
            case "collect" -> check(plugin.factory().collect(player(sender), workshop(sender, id)));
            case "upgrade" -> check(plugin.factory().upgrade(player(sender), workshop(sender, id)));
            default -> throw new CommandError("Использование: /district factory [menu|info|levels|load|collect|upgrade] [мастерская]");
        }
    }

    private void info(CommandSender sender, Plot plot) {
        var f = plugin.factory();
        Text.send(sender, "<yellow>Производство мастерской <white>" + Text.esc(plot.displayName()) + "</white>: уровень "
                + plot.level + ", станков с заданием <white>" + f.assignedCount(plot) + "/" + f.slots(plot)
                + "</white>, склад <white>" + f.stockTotal(plot) + "/" + f.stockCapacity(plot) + "</white>.");
        for (var e : plot.stock.entrySet()) {
            sender.sendMessage(Text.mm("<gray> " + e.getKey().name() + ": <white>" + e.getValue()));
        }
        for (ProductionService.View v : f.views(plot)) {
            sender.sendMessage(Text.mm("<gray> " + v.pos() + " " + v.block().name() + ": <white>"
                    + (v.recipe() == null ? "без задания" : v.recipe().id() + " - " + v.status().title()
                    + ", продукции " + v.output())));
        }
        sender.sendMessage(Text.mm("<gray> Сундуков сырья: <white>" + plot.rawChests.size() + "<gray>, продукции: <white>"
                + plot.productChests.size()));
    }

    private void levels(CommandSender sender) {
        Text.send(sender, "<yellow>Уровни мастерской:");
        for (var l : plugin.upgrades().levels()) {
            sender.sendMessage(Text.mm("<gray> Уровень " + l.level() + ": " + plugin.economy().format(l.price())
                    + " - станков с заданием " + l.maxLines() + ", скорость ×" + l.speed() + ", склад ×" + l.stock()
                    + ", буфер ×" + l.buffer() + ", очередь заказов +" + l.orderQueue()));
        }
    }

    // ------------------------------------------------------------ machine

    private record Looked(Plot plot, String pos, Block block) { }

    private Looked looked(CommandSender sender) {
        Player p = player(sender);
        Block target = ru.industryreborn.util.Targeting.of(p);
        if (target == null) {
            throw new CommandError("Наведите взгляд на станок мастерской (не дальше 6 блоков).");
        }
        Plot plot = plugin.plots().at(target.getLocation());
        String pos = StationService.posKey(target.getX(), target.getY(), target.getZ());
        if (plot == null || plot.category != Category.WORKSHOP || !plugin.stations().machines(plot).containsKey(pos)) {
            throw new CommandError("Это не рабочий станок мастерской.");
        }
        return new Looked(plot, pos, target);
    }

    /** machine at <мастерская> <x,y,z> set <рецепт>|clear|menu: то же без взгляда на станок (для HTML-страниц). */
    private void machineAt(CommandSender sender, String[] args) {
        if (args.length < 4) {
            throw new CommandError("Использование: /district machine at <мастерская> <x,y,z> set <рецепт>|clear|menu");
        }
        Player p = player(sender);
        Plot plot = workshop(sender, args[1]);
        String pos = args[2];
        if (!plugin.stations().machines(plot).containsKey(pos)) {
            throw new CommandError("В этой позиции нет рабочего станка.");
        }
        switch (args[3].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                if (args.length < 5) {
                    throw new CommandError("Укажите рецепт.");
                }
                check(plugin.factory().assign(p, plot, pos, args[4]));
                Text.ok(sender, "Станку назначен рецепт " + args[4] + ".");
            }
            case "clear" -> {
                check(plugin.factory().clear(p, plot, pos));
                Text.ok(sender, "Задание снято.");
            }
            case "menu" -> new MachineMenu(plugin, p, plot, pos, null, 0, "").open();
            default -> throw new CommandError("Использование: /district machine at <мастерская> <x,y,z> set <рецепт>|clear|menu");
        }
    }

    void machine(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("at")) {
            machineAt(sender, args);
            return;
        }
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        Looked l = looked(sender);
        switch (sub) {
            case "menu" -> new MachineMenu(plugin, player(sender), l.plot(), l.pos(), null, 0, "").open();
            case "info" -> {
                ProductionService.View v = plugin.factory().view(l.plot(), l.pos());
                Text.send(sender, "<yellow>Станок <white>" + l.block().getType().name() + "</white> " + l.pos() + ": "
                        + (v.recipe() == null ? "без задания (обслуживает заказы игроков)"
                        : "<white>" + v.recipe().id() + "</white>, " + v.status().title() + ", продукции " + v.output()));
            }
            case "set" -> {
                if (args.length < 2) {
                    throw new CommandError("Использование: /district machine set <рецепт> (например IRON_INGOT или furnace:IRON_INGOT)");
                }
                String family = RecipeBook.familyOf(l.block().getType());
                String id = args[1].contains(":") ? args[1].toLowerCase(Locale.ROOT).split(":", 2)[0] + ":" + args[1].split(":", 2)[1].toUpperCase(Locale.ROOT)
                        : (family == null ? args[1] : family + ":" + args[1].toUpperCase(Locale.ROOT));
                check(plugin.factory().assign(player(sender), l.plot(), l.pos(), id));
                Text.ok(sender, "Станку назначен рецепт " + id + ".");
            }
            case "clear" -> {
                check(plugin.factory().clear(player(sender), l.plot(), l.pos()));
                Text.ok(sender, "Задание снято.");
            }
            default -> throw new CommandError("Использование: /district machine [menu|info|set <рецепт>|clear] (смотрите на станок)");
        }
    }

    List<String> completeMachine(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return List.of("menu", "info", "set", "clear");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("set") && sender instanceof Player p) {
            Block target = ru.industryreborn.util.Targeting.of(p);
            if (target != null) {
                List<String> names = new ArrayList<>();
                for (RecipeBook.Recipe r : plugin.recipes().forBlock(target.getType())) {
                    names.add(r.output().name());
                }
                return names;
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------ chest

    void chest(CommandSender sender, String[] args) {
        Player p = player(sender);
        if (args.length < 1) {
            throw new CommandError("Использование: /district chest <raw|product|unmark> (смотрите на сундук в своей мастерской)");
        }
        Block target = ru.industryreborn.util.Targeting.of(p);
        if (target == null) {
            throw new CommandError("Наведите взгляд на сундук (не дальше 6 блоков).");
        }
        Plot plot = plugin.plots().at(target.getLocation());
        if (plot == null || plot.category != Category.WORKSHOP) {
            throw new CommandError("Этот сундук не в мастерской.");
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "raw" -> {
                check(plugin.factory().markChest(p, plot, target, true));
                Text.ok(sender, "Сундук помечен как сундук сырья: подходящее сырьё само уйдёт на склад мастерской.");
            }
            case "product" -> {
                check(plugin.factory().markChest(p, plot, target, false));
                Text.ok(sender, "Сундук помечен как сундук продукции: готовая продукция станков будет выкладываться в него.");
            }
            case "unmark" -> {
                check(plugin.factory().unmarkChest(p, plot, target));
                Text.ok(sender, "Пометка снята.");
            }
            default -> throw new CommandError("Использование: /district chest <raw|product|unmark>");
        }
    }

    List<String> complete(CommandSender sender, String[] args) {
        int n = args.length; // args[0] = "factory"
        if (n == 2) {
            return List.of("menu", "info", "levels", "load", "collect", "upgrade");
        }
        if (n == 3) {
            return plugin.plots().all().stream().filter(p -> p.category == Category.WORKSHOP).map(p -> p.id).toList();
        }
        return List.of();
    }
}
