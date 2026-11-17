package ru.buildmaster.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.gui.MyOrdersMenu;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.ServiceOrder;
import ru.buildmaster.service.ServiceCatalog;
import ru.buildmaster.util.Text;

/** Команды услуг мастерских: /district service ... */
final class ServiceCommands {

    private final BuildMasterPlugin plugin;

    ServiceCommands(BuildMasterPlugin plugin) {
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
            throw new CommandError("Услуги есть только у мастерских.");
        }
        return plot;
    }

    private ServiceOrder order(String id) {
        ServiceOrder o = plugin.orders().find(id);
        if (o == null) {
            throw new CommandError("Заказ «" + id + "» не найден (или номер неоднозначен).");
        }
        return o;
    }

    void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "orders" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "orders", "menu" -> {
                Player p = player(sender);
                if (rest.length > 0 && rest[0].equalsIgnoreCase("classic") || !plugin.htmlMenus().open(p, ru.buildmaster.html.HtmlMenus.Kind.ORDERS)) {
                    new MyOrdersMenu(plugin, p, 0).open();
                }
            }
            case "list" -> list(player(sender));
            case "offers" -> offers(sender, rest.length > 0 ? rest[0] : null);
            case "order" -> {
                if (rest.length < 2) {
                    throw new CommandError("Использование: /district service order <мастерская> <услуга> [all]  "
                            + "(предметы возьмите в руку; all - все такие же из инвентаря)");
                }
                String error = plugin.orders().create(player(sender), workshop(sender, rest[0]), rest[1],
                        rest.length > 2 && rest[2].equalsIgnoreCase("all"));
                if (error != null) {
                    throw new CommandError(error);
                }
            }
            case "collect" -> {
                if (rest.length < 1) {
                    throw new CommandError("Использование: /district service collect <номер заказа>");
                }
                String error = plugin.orders().collect(player(sender), order(rest[0]).id);
                if (error != null) {
                    throw new CommandError(error);
                }
            }
            case "cancel" -> {
                if (rest.length < 1) {
                    throw new CommandError("Использование: /district service cancel <номер заказа>");
                }
                String error = plugin.orders().cancel(player(sender), order(rest[0]).id);
                if (error != null) {
                    throw new CommandError(error);
                }
            }
            case "price" -> {
                if (rest.length < 3) {
                    throw new CommandError("Использование: /district service price <мастерская> <услуга> <цена за единицу>");
                }
                double price;
                try {
                    price = Double.parseDouble(rest[2].replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new CommandError("Цена должна быть числом.");
                }
                String error = plugin.orders().configureOffer(player(sender), workshop(sender, rest[0]), rest[1], price, null);
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Цена установлена: " + plugin.economy().format(price) + " за единицу.");
            }
            case "toggle" -> {
                if (rest.length < 3) {
                    throw new CommandError("Использование: /district service toggle <мастерская> <услуга> <on|off>");
                }
                boolean on = switch (rest[2].toLowerCase(Locale.ROOT)) {
                    case "on", "вкл", "true" -> true;
                    case "off", "выкл", "false" -> false;
                    default -> throw new CommandError("Укажите on или off.");
                };
                String error = plugin.orders().configureOffer(player(sender), workshop(sender, rest[0]), rest[1], null, on);
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Услуга " + (on ? "включена" : "выключена") + ".");
            }
            case "admin" -> {
                if (!sender.hasPermission("buildmaster.admin")) {
                    throw new CommandError("Недостаточно прав.");
                }
                admin(sender, rest);
            }
            default -> throw new CommandError("Использование: /district service [orders|list|offers|order|collect|cancel|price|toggle]");
        }
    }

    private void list(Player p) {
        List<ServiceOrder> mine = plugin.orders().ofClient(p.getUniqueId());
        if (mine.isEmpty()) {
            Text.send(p, "<gray>У вас нет заказов.");
            return;
        }
        Text.send(p, "<yellow>Ваши заказы:");
        for (ServiceOrder o : mine) {
            ServiceCatalog.Offer def = plugin.catalog().get(o.offerKey);
            p.sendMessage(Text.mm("<gray> <white>" + o.shortId() + "</white> " + Text.esc(def == null ? o.offerKey : def.title())
                    + " × " + o.units + ", " + Text.esc(o.plotId) + ": " + stateText(o)));
        }
    }

    private String stateText(ServiceOrder o) {
        return switch (o.state) {
            case PENDING -> "<gray>в очереди, примерно " + plugin.orders().eta(o);
            case ACTIVE -> "<yellow>выполняется " + o.unitsDone + "/" + o.units + ", осталось " + plugin.orders().eta(o);
            case READY -> "<green>готов, заберите: /district service collect " + o.shortId();
        };
    }

    private void offers(CommandSender sender, String plotId) {
        Plot plot = workshop(sender, plotId);
        Text.send(sender, "<yellow>Услуги мастерской <white>" + Text.esc(plot.id) + "</white>:");
        for (ServiceCatalog.Offer def : plugin.catalog().offers()) {
            Plot.Offer o = plugin.orders().offerOf(plot, def);
            int stations = plugin.stations().countByTariffKey(plot, def.stationKey());
            sender.sendMessage(Text.mm("<gray> <white>" + Text.esc(def.key()) + "</white> - " + Text.esc(def.title()) + ": "
                    + (o.enabled ? "<green>включено" : "<red>выключено") + "<gray>, " + plugin.economy().format(o.price)
                    + " за единицу, станков: " + stations));
        }
    }

    private void admin(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                List<ServiceOrder> list = args.length > 1 ? plugin.orders().ofPlot(args[1]) : plugin.orders().all();
                Text.send(sender, "<yellow>Заказов: " + list.size());
                int shown = 0;
                for (ServiceOrder o : list) {
                    if (shown++ >= 20) {
                        break;
                    }
                    sender.sendMessage(Text.mm("<gray> <white>" + o.shortId() + "</white> " + Text.esc(o.plotId) + ", "
                            + Text.esc(o.clientName) + ", " + Text.esc(o.offerKey) + " × " + o.units + ": " + o.state));
                }
            }
            case "cancel" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district service admin cancel <номер заказа> <причина>");
                }
                String error = plugin.orders().adminCancel(sender.getName(), order(args[1]),
                        String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Заказ отменён, клиенту возвращено.");
            }
            default -> throw new CommandError("Использование: /district service admin <list [мастерская]|cancel <номер> <причина>>");
        }
    }

    List<String> complete(CommandSender sender, String[] args) {
        int n = args.length; // args[0] = "service"
        if (n == 2) {
            return List.of("orders", "list", "offers", "order", "collect", "cancel", "price", "toggle", "admin");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        List<String> workshops = plugin.plots().all().stream().filter(p -> p.category == Category.WORKSHOP).map(p -> p.id).toList();
        List<String> keys = plugin.catalog().offers().stream().map(ServiceCatalog.Offer::key).toList();
        if (n == 3 && List.of("offers", "order", "price", "toggle").contains(sub)) {
            return workshops;
        }
        if (n == 4 && List.of("order", "price", "toggle").contains(sub)) {
            return keys;
        }
        if (n == 5 && sub.equals("order")) {
            return List.of("all");
        }
        if (n == 5 && sub.equals("toggle")) {
            return List.of("on", "off");
        }
        if (n == 3 && List.of("collect", "cancel").contains(sub) && sender instanceof Player p) {
            return plugin.orders().ofClient(p.getUniqueId()).stream().map(ServiceOrder::shortId).toList();
        }
        if (n == 3 && sub.equals("admin")) {
            return List.of("list", "cancel");
        }
        return List.of();
    }
}
