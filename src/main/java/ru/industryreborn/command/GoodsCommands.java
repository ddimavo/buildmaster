package ru.industryreborn.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.gui.GoodsMenu;
import ru.industryreborn.gui.StorefrontMenu;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/** Команды торговли и связей: /district shop, goods, link, unlink, storefront. */
final class GoodsCommands {

    private final IndustryRebornPlugin plugin;

    GoodsCommands(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        throw new CommandError("Эту команду можно выполнить только игроку.");
    }

    private Plot byId(String id) {
        Plot p = plugin.plots().get(id);
        if (p == null) {
            throw new CommandError("Участок «" + id + "» не найден.");
        }
        return p;
    }

    /** Предприятие игрока с товарами: участок под ногами либо единственное своё. */
    private Plot mine(CommandSender sender, String id) {
        if (id != null) {
            return byId(id);
        }
        Player p = player(sender);
        Plot here = plugin.plots().at(p.getLocation());
        if (here != null && plugin.goods().holdsGoods(here) && here.isOwner(p.getUniqueId())) {
            return here;
        }
        List<Plot> own = plugin.plots().ownedBy(p.getUniqueId()).stream().filter(x -> plugin.goods().holdsGoods(x)).toList();
        if (own.size() == 1) {
            return own.get(0);
        }
        throw new CommandError("Укажите участок: встаньте на нём или добавьте его номер в конце команды.");
    }

    private static void check(String error) {
        if (error != null) {
            throw new CommandError(error);
        }
    }

    private static Material material(String s) {
        Material m = Material.matchMaterial(s);
        if (m == null || m.isAir() || !m.isItem()) {
            throw new CommandError("Неизвестный предмет «" + s + "».");
        }
        return m;
    }

    /** Ключ товара из аргумента: материал, ir:предмет или nexo:предмет. */
    private String goodsKey(String s) {
        var ref = ru.industryreborn.items.ItemRef.parse(s);
        if (ref == null) {
            throw new CommandError("Неизвестный предмет «" + s + "».");
        }
        return ref.key();
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new CommandError("Цена должна быть числом.");
        }
    }

    // ------------------------------------------------------------ shop

    /** /district shop <участок>: витрина для покупателей. */
    void shop(CommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district shop <участок> - витрина магазина или мастерской");
        }
        Plot plot = byId(args[0]);
        if (!plugin.goods().sells(plot)) {
            throw new CommandError("Участок «" + plot.id + "» ничего не продаёт.");
        }
        new StorefrontMenu(plugin, player(sender), plot, null, 0).open();
    }

    // ------------------------------------------------------------ goods

    void goods(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "menu" -> new GoodsMenu(plugin, player(sender), mine(sender, rest.length > 0 ? rest[0] : null), null, 0).open();
            case "deposit" -> {
                Material only = rest.length > 0 && Material.matchMaterial(rest[0]) != null ? material(rest[0]) : null;
                String plotId = rest.length > 0 && only == null ? rest[0] : rest.length > 1 ? rest[1] : null;
                check(plugin.goods().deposit(player(sender), mine(sender, plotId), only, 0));
            }
            case "take" -> {
                if (rest.length < 2) {
                    throw new CommandError("Использование: /district goods take <предмет> <количество|all> [участок]");
                }
                int amount = rest[1].equalsIgnoreCase("all") ? 0 : (int) number(rest[1]);
                check(plugin.goods().withdrawKey(player(sender), mine(sender, rest.length > 2 ? rest[2] : null), goodsKey(rest[0]), amount));
            }
            case "price" -> {
                if (rest.length < 2) {
                    throw new CommandError("Использование: /district goods price <предмет> <цена за штуку|off> [участок]");
                }
                Double price = rest[1].equalsIgnoreCase("off") ? null : number(rest[1]);
                check(plugin.goods().setPriceKey(player(sender), mine(sender, rest.length > 2 ? rest[2] : null), goodsKey(rest[0]), price));
                Text.ok(sender, price == null ? "Товар снят с продажи." : "Цена назначена.");
            }
            case "list" -> {
                Plot plot = mine(sender, rest.length > 0 ? rest[0] : null);
                Text.send(sender, "<yellow>Товары участка <white>" + Text.esc(plot.id) + "</white>: <white>" + plugin.goods().total(plot)
                        + "/" + plugin.goods().capacity(plot));
                for (String key : plugin.goods().keys(plot)) {
                    double price = plugin.goods().priceKey(plot, key);
                    sender.sendMessage(Text.mm("<gray> " + Text.esc(plugin.goods().nameOf(key)) + " × <white>" + plugin.goods().stockKey(plot, key) + "<gray> - "
                            + (price > 0 ? "<white>" + plugin.economy().format(price) : "<dark_gray>цена не задана")));
                }
            }
            default -> throw new CommandError("Использование: /district goods [menu|list|deposit|take|price] ...");
        }
    }

    // ------------------------------------------------------------ storefront

    void storefront(CommandSender sender, String[] args) {
        if (args.length < 1 || !List.of("on", "off").contains(args[0].toLowerCase(Locale.ROOT))) {
            throw new CommandError("Использование: /district storefront <on|off> [мастерская] - продукция мастерской идёт на её витрину");
        }
        Plot plot = mine(sender, args.length > 1 ? args[1] : null);
        Player p = player(sender);
        if (plot.category != Category.WORKSHOP) {
            throw new CommandError("Собственная витрина с продукцией есть у мастерских.");
        }
        if (!plot.isOwner(p.getUniqueId()) && !p.hasPermission("industryreborn.admin")) {
            throw new CommandError("Это настройка управляющего мастерской.");
        }
        plot.storefront = args[0].equalsIgnoreCase("on");
        plugin.plots().save();
        Text.ok(sender, plot.storefront ? "Продукция мастерской теперь выкладывается на её витрину (если нет связи с магазином или складом)."
                : "Продукция снова выкладывается в сундук продукции.");
    }

    // ------------------------------------------------------------ link

    void link(CommandSender sender, String[] args) {
        Player p = player(sender);
        if (args.length >= 3 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("decline"))) {
            Plot target = byId(args[2]);
            if (args[0].equalsIgnoreCase("accept")) {
                check(plugin.links().accept(p, target, args[1]));
                Text.ok(sender, "Связь подтверждена: продукция мастерской " + args[1] + " будет поступать на " + target.id + ".");
            } else {
                check(plugin.links().decline(p, target, args[1]));
                Text.ok(sender, "Запрос отклонён.");
            }
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            list(sender);
            return;
        }
        if (args.length < 2) {
            throw new CommandError("Использование: /district link <мастерская> <магазин или склад> | accept|decline <мастерская> <участок> | list");
        }
        check(plugin.links().request(p, byId(args[0]), byId(args[1])));
    }

    private void list(CommandSender sender) {
        Player p = player(sender);
        boolean any = false;
        for (Plot plot : plugin.plots().all()) {
            boolean mine = plot.isOwner(p.getUniqueId());
            if (mine && plot.linkedTo != null) {
                Text.send(p, "<gray>Мастерская <white>" + Text.esc(plot.id) + "</white> -> <white>" + Text.esc(plot.linkedTo));
                any = true;
            }
            if (mine && !plot.linkRequests.isEmpty()) {
                Text.send(p, "<yellow>Запросы к участку <white>" + Text.esc(plot.id) + "</white>: "
                        + Text.esc(String.join(", ", plot.linkRequests)) + " <gray>(/district link accept|decline <мастерская> " + Text.esc(plot.id) + ")");
                any = true;
            }
        }
        if (!any) {
            Text.send(p, "<gray>Связей и запросов нет. Создать: /district link <мастерская> <магазин или склад>");
        }
    }

    void unlink(CommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district unlink <мастерская>");
        }
        check(plugin.links().unlink(player(sender), byId(args[0])));
    }

    // ------------------------------------------------------------ подсказки

    List<String> complete(String sub, CommandSender sender, String[] args) {
        int n = args.length;
        switch (sub) {
            case "shop" -> {
                if (n == 2) {
                    return plugin.plots().all().stream().filter(plugin.goods()::sells).map(x -> x.id).toList();
                }
            }
            case "goods" -> {
                if (n == 2) {
                    return List.of("menu", "list", "deposit", "take", "price");
                }
            }
            case "storefront" -> {
                if (n == 2) {
                    return List.of("on", "off");
                }
            }
            case "link" -> {
                if (n == 2) {
                    List<String> l = new ArrayList<>(List.of("accept", "decline", "list"));
                    plugin.plots().all().stream().filter(x -> x.category == Category.WORKSHOP).forEach(x -> l.add(x.id));
                    return l;
                }
                if (n == 3) {
                    return plugin.plots().all().stream().filter(x -> x.category == Category.SHOP || x.category == Category.WAREHOUSE
                            || x.category == Category.WORKSHOP).map(x -> x.id).toList();
                }
            }
            case "unlink" -> {
                if (n == 2) {
                    return plugin.plots().all().stream().filter(x -> x.linkedTo != null).map(x -> x.id).toList();
                }
            }
            default -> { }
        }
        return List.of();
    }
}
