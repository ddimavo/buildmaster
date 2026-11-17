package ru.industryreborn.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/** Команды участков игроков: /district region ... */
final class RegionCommands {

    private final IndustryRebornPlugin plugin;

    RegionCommands(IndustryRebornPlugin plugin) {
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
        if (p == null || !p.isPlayerPlot()) {
            throw new CommandError("Участок игрока «" + id + "» не найден.");
        }
        return p;
    }

    /** Цена: число не меньше нуля либо off (снять с продажи или аренды). */
    private static double price(String s) {
        if (s.equalsIgnoreCase("off") || s.equalsIgnoreCase("нет")) {
            return -1;
        }
        try {
            double v = Double.parseDouble(s.replace(',', '.'));
            if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new CommandError("Цена должна быть числом не меньше нуля или off.");
        }
    }

    private static void fail(String error) {
        if (error != null) {
            throw new CommandError(error);
        }
    }

    void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "bind" -> {
                if (rest.length < 2) {
                    throw new CommandError("Использование: /district region bind <регион WorldGuard> <тип: "
                            + plugin.playerPlots().allowedTypes().stream().map(Category::key).toList() + ">");
                }
                Category type = Category.parse(rest[1]);
                fail(plugin.playerPlots().bind(player(sender), rest[0], type));
            }
            case "list" -> list(sender);
            case "consent" -> {
                if (rest.length < 1) {
                    throw new CommandError("Использование: /district region consent <участок>");
                }
                fail(plugin.playerPlots().consent(player(sender), plot(rest[0])));
            }
            case "sale" -> {
                if (rest.length < 2) {
                    throw new CommandError("Использование: /district region sale <участок> <цена|off>");
                }
                fail(plugin.playerPlots().setListing(player(sender), plot(rest[0]), price(rest[1]), null));
                Text.ok(sender, "Продажа обновлена.");
            }
            case "rent" -> {
                if (rest.length < 2) {
                    throw new CommandError("Использование: /district region rent <участок> <цена за сутки|off>");
                }
                fail(plugin.playerPlots().setListing(player(sender), plot(rest[0]), null, price(rest[1])));
                Text.ok(sender, "Аренда обновлена.");
            }
            case "access" -> {
                if (rest.length < 2 || !List.of("none", "shared").contains(rest[1].toLowerCase(Locale.ROOT))) {
                    throw new CommandError("Использование: /district region access <участок> <none|shared>");
                }
                fail(plugin.playerPlots().setSharedAccess(player(sender), plot(rest[0]), rest[1].equalsIgnoreCase("shared")));
                Text.ok(sender, "Условие аренды обновлено.");
            }
            case "unbind" -> {
                if (rest.length < 1) {
                    throw new CommandError("Использование: /district region unbind <участок>");
                }
                fail(plugin.playerPlots().unbind(player(sender), plot(rest[0])));
                Text.ok(sender, "Регион отвязан.");
            }
            default -> throw new CommandError("Подкоманды: bind, list, consent, sale, rent, access, unbind");
        }
    }

    private void list(CommandSender sender) {
        Player p = player(sender);
        var mine = plugin.plots().all().stream()
                .filter(x -> x.isPlayerPlot() && (x.isLandlord(p.getUniqueId()) || x.requiredConsents.contains(p.getUniqueId())))
                .toList();
        if (mine.isEmpty()) {
            Text.send(p, "<gray>Привязанных регионов нет. /district region bind <регион> <тип>");
            return;
        }
        for (Plot x : mine) {
            String state = x.approval == Plot.Approval.PENDING ? "<yellow>ждёт одобрения" : x.isFree() ? "<green>свободен"
                    : "<aqua>сдан: " + Text.esc(String.valueOf(x.ownerName));
            Text.send(p, "<white>" + Text.esc(x.id) + "</white> " + x.category.title() + ", " + state
                    + "<gray>, продажа " + (x.price < 0 ? "нет" : plugin.economy().format(x.price))
                    + ", аренда " + (x.rentPerDay < 0 ? "нет" : plugin.economy().format(x.rentPerDay))
                    + (x.consentsComplete() ? "" : ", <yellow>нет согласий: " + Text.esc(plugin.playerPlots().missingConsents(x))));
        }
    }

    List<String> complete(CommandSender sender, String[] args) {
        int n = args.length; // args[0] = "region"
        if (n == 2) {
            return List.of("bind", "list", "consent", "sale", "rent", "access", "unbind");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (n == 3 && List.of("consent", "sale", "rent", "access", "unbind").contains(sub)) {
            return plugin.plots().all().stream().filter(Plot::isPlayerPlot).map(x -> x.id).toList();
        }
        if (n == 4 && sub.equals("bind")) {
            return plugin.playerPlots().allowedTypes().stream().map(Category::key).toList();
        }
        if (n == 4 && sub.equals("access")) {
            return List.of("none", "shared");
        }
        if (n == 4 && List.of("sale", "rent").contains(sub)) {
            return List.of("off");
        }
        return List.of();
    }
}
