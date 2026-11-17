package ru.industryreborn.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.util.Text;

/** /district enchant: зачарования для стола зачарований мастерской (предлагает управляющий, одобряет администратор). */
final class EnchantCommands {

    private final IndustryRebornPlugin plugin;

    EnchantCommands(IndustryRebornPlugin plugin) {
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
        } else {
            Player p = player(sender);
            Plot here = plugin.plots().at(p.getLocation());
            if (here != null && here.category == Category.WORKSHOP && here.isOwner(p.getUniqueId())) {
                plot = here;
            } else {
                List<Plot> mine = plugin.plots().ownedBy(p.getUniqueId()).stream().filter(x -> x.category == Category.WORKSHOP).toList();
                plot = mine.size() == 1 ? mine.get(0) : null;
                if (plot == null) {
                    throw new CommandError("Укажите мастерскую в конце команды или встаньте в неё.");
                }
            }
        }
        if (plot == null || plot.category != Category.WORKSHOP) {
            throw new CommandError("Мастерская не найдена.");
        }
        return plot;
    }

    private static void check(String error) {
        if (error != null) {
            throw new CommandError(error);
        }
    }

    void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "list" -> list(sender, workshop(sender, rest.length > 0 ? rest[0] : null));
            case "propose", "allow" -> {
                if (rest.length < 3) {
                    throw new CommandError("Использование: /district enchant " + sub + " <предмет> <зачарование> <уровень> [мастерская]");
                }
                Material item = Material.matchMaterial(rest[0]);
                if (item == null) {
                    throw new CommandError("Неизвестный предмет «" + rest[0] + "».");
                }
                int level;
                try {
                    level = Integer.parseInt(rest[2]);
                } catch (NumberFormatException e) {
                    throw new CommandError("Уровень должен быть целым числом.");
                }
                Plot plot = workshop(sender, rest.length > 3 ? rest[3] : null);
                if (sub.equals("allow") && !sender.hasPermission("industryreborn.admin")) {
                    throw new CommandError("Недостаточно прав.");
                }
                check(plugin.enchants().propose(player(sender), plot, item, rest[1], level));
                Text.ok(sender, sender.hasPermission("industryreborn.admin") ? "Зачарование одобрено."
                        : "Заявка отправлена администратору. Решение придёт в журнал предупреждений.");
            }
            case "remove" -> {
                if (rest.length < 1) {
                    throw new CommandError("Использование: /district enchant remove <идентификатор> [мастерская]");
                }
                check(plugin.enchants().remove(player(sender), workshop(sender, rest.length > 1 ? rest[1] : null), rest[0]));
                Text.ok(sender, "Зачарование удалено.");
            }
            case "types" -> {
                Material item = rest.length > 0 ? Material.matchMaterial(rest[0]) : null;
                Text.send(sender, "<yellow>Зачарования" + (item == null ? "" : " для " + item.name()) + ": <white>"
                        + String.join(", ", plugin.enchants().applicable(item)));
            }
            default -> throw new CommandError("Использование: /district enchant [list|propose|remove|types] ...");
        }
    }

    private void list(CommandSender sender, Plot plot) {
        Text.send(sender, "<yellow>Зачарования мастерской <white>" + Text.esc(plot.id) + "</white>: одобрено "
                + plot.approvedEnchants.size() + "/" + plugin.enchants().maxRecipes());
        for (String id : plot.approvedEnchants) {
            RecipeBook.Recipe r = plugin.recipes().get(id);
            sender.sendMessage(Text.mm("<gray> " + Text.esc(id) + (r == null ? " <red>(недействительно)" : " - лазурит × " + lapis(r))));
        }
        for (var req : plugin.moderation().pendingEnchants(plot.id)) {
            sender.sendMessage(Text.mm("<gray> " + Text.esc(req.text) + " <yellow>(ждёт одобрения)"));
        }
    }

    private static int lapis(RecipeBook.Recipe r) {
        return r.inputs().size() > 1 ? r.inputs().get(1).count() : 0;
    }

    List<String> complete(CommandSender sender, String[] args) {
        int n = args.length; // args[0] = "enchant"
        if (n == 2) {
            return List.of("list", "propose", "remove", "types");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (n == 4 && (sub.equals("propose") || sub.equals("allow"))) {
            return plugin.enchants().applicable(Material.matchMaterial(args[2]));
        }
        if (n == 3 && sender instanceof Player p && sub.equals("remove")) {
            Plot here = plugin.plots().at(p.getLocation());
            return here == null ? new ArrayList<>() : new ArrayList<>(here.approvedEnchants);
        }
        return List.of();
    }
}
