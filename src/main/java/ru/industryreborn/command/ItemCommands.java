package ru.industryreborn.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.items.CustomItem;
import ru.industryreborn.items.ItemRef;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.util.Text;

/** /district item (предметы плагина, только администратор) и /district recipe (рецепты администратора: просмотр и изучение). */
final class ItemCommands {

    private final IndustryRebornPlugin plugin;

    ItemCommands(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    private static void requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("industryreborn.admin")) {
            throw new CommandError("Недостаточно прав.");
        }
    }

    private static void usage(String text) {
        throw new CommandError("Использование: /district " + text);
    }

    // ------------------------------------------------------------ item

    void item(CommandSender sender, String[] args) {
        requireAdmin(sender);
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        var items = plugin.customItems();
        switch (sub) {
            case "list" -> {
                Text.send(sender, "<yellow>Предметы плагина: <white>" + items.all().size() + "</white>"
                        + (items.external().available() ? "<gray> (Nexo подключён)" : "<gray> (Nexo не найден)"));
                for (CustomItem d : items.all()) {
                    double price = plugin.itemShop().price(d.id());
                    Text.send(sender, "<gray> <white>" + Text.esc(d.id()) + "</white> [" + d.kind().title() + "] - " + Text.esc(items.displayName(d.ref())) + "<gray> ("
                            + d.material().name() + (d.nexoId() != null ? ", Nexo: " + Text.esc(d.nexoId()) : "")
                            + (d.kind() == ru.industryreborn.items.ItemKind.SKIN ? ", на: " + String.join("/", d.skinFor()) : "")
                            + (price > 0 ? ", цена " + plugin.economy().format(price) : "") + ")");
                }
            }
            case "info" -> {
                if (rest.length < 1) {
                    usage("item info <id>");
                }
                CustomItem d = items.get(rest[0]);
                if (d == null) {
                    throw new CommandError("Предмет «" + rest[0] + "» не найден: /district item list");
                }
                Text.send(sender, "<yellow>" + Text.esc(d.id()) + "<gray> - материал " + d.material().name()
                        + (d.model() != null ? ", custom_model_data " + d.model() : "")
                        + (d.itemModel() != null ? ", item_model " + Text.esc(d.itemModel()) : "")
                        + (d.nexoId() != null ? ", Nexo: " + Text.esc(d.nexoId()) + (items.external().exists(d.nexoId()) ? " (найден)" : " (не найден)") : ""));
            }
            case "give" -> {
                if (rest.length < 2) {
                    usage("item give <игрок> <id|ir:id|nexo:id|МАТЕРИАЛ> [количество]");
                }
                Player target = org.bukkit.Bukkit.getPlayerExact(rest[0]);
                if (target == null) {
                    throw new CommandError("Игрок «" + rest[0] + "» не в сети.");
                }
                ItemRef ref = ItemRef.parse(rest[1].contains(":") || items.get(rest[1]) == null ? rest[1] : "ir:" + rest[1]);
                if (ref == null) {
                    throw new CommandError("Неизвестный предмет «" + rest[1] + "».");
                }
                int amount = 1;
                if (rest.length > 2) {
                    try {
                        amount = Integer.parseInt(rest[2]);
                    } catch (NumberFormatException e) {
                        throw new CommandError("Количество - целое число.");
                    }
                }
                if (amount < 1 || amount > 2304) {
                    throw new CommandError("Количество от 1 до 2304.");
                }
                ItemStack stack = items.build(ref, 1);
                if (stack == null) {
                    throw new CommandError("Предмет «" + rest[1] + "» сейчас недоступен (не определён или Nexo ещё не загрузил предметы).");
                }
                int left = amount;
                while (left > 0) {
                    int n = Math.min(left, Math.max(1, stack.getMaxStackSize()));
                    ItemStack part = stack.clone();
                    part.setAmount(n);
                    var rejected = target.getInventory().addItem(part);
                    rejected.values().forEach(r -> target.getWorld().dropItemNaturally(target.getLocation(), r));
                    left -= n;
                }
                Text.ok(sender, "Выдано " + target.getName() + ": " + amount + " × " + Text.esc(items.displayName(ref)) + ".");
            }
            case "price" -> {
                if (rest.length < 2) {
                    usage("item price <id> <цена|0>");
                }
                double price;
                try {
                    price = Double.parseDouble(rest[1].replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new CommandError("Цена - число.");
                }
                String error = plugin.itemShop().setPrice(rest[0], price);
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, price <= 0 ? "Предмет снят с продажи." : "Цена предмета «" + rest[0] + "»: " + plugin.economy().format(price) + ".");
            }
            case "reload" -> {
                List<String> errors = plugin.reloadCustomContent();
                Text.ok(sender, "Перечитано: предметов " + items.all().size() + ", рецептов " + plugin.recipes().customRecipes().size()
                        + (errors.isEmpty() ? "." : ", ошибок " + errors.size() + " (в консоли)."));
                errors.stream().limit(5).forEach(e -> Text.err(sender, e));
            }
            case "export-nexo" -> {
                try {
                    Text.ok(sender, "Заготовка для Nexo записана: " + items.exportNexo().getPath());
                } catch (java.io.IOException e) {
                    throw new CommandError("Не удалось записать файл: " + e.getMessage());
                }
            }
            default -> usage("item [list|info <id>|give <игрок> <id> [кол-во]|price <id> <цена>|reload|export-nexo]");
        }
    }

    // ------------------------------------------------------------ recipe

    void recipe(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "list" -> {
                List<RecipeBook.Recipe> list = plugin.recipes().customRecipes();
                if (list.isEmpty()) {
                    Text.send(sender, "<gray>Рецептов администратора нет.");
                }
                for (RecipeBook.Recipe r : list) {
                    Text.send(sender, "<gray> <white>" + Text.esc(r.id().substring(7)) + "</white> - " + Text.esc(plugin.recipes().outputName(r)) + " × "
                            + r.outputAmount() + "<gray> (" + RecipeBook.familyTitle(r.family()) + ", " + (r.free() ? "свободный" : "нужен чертёж") + ")");
                }
            }
            case "info" -> {
                if (rest.length < 1) {
                    usage("recipe info <рецепт>");
                }
                RecipeBook.Recipe r = recipeArg(rest[0]);
                Text.send(sender, "<yellow>" + Text.esc(r.id().substring(7)) + "<gray> - " + RecipeBook.familyTitle(r.family()) + ", " + r.seconds() + " с"
                        + (r.free() ? ", свободный" : ", нужен чертёж"));
                for (RecipeBook.Ingredient ing : r.inputs()) {
                    Text.send(sender, "<gray>  вход: <white>" + ing.count() + " × " + Text.esc(String.join(" / ", plugin.factory().ingredientNames(ing))));
                }
                Text.send(sender, "<gray>  выход: <white>" + r.outputAmount() + " × " + Text.esc(plugin.recipes().outputName(r))
                        + (plugin.recipes().available(r) ? "" : " <red>(предмет сейчас недоступен)"));
            }
            case "request" -> request(sender, rest);
            case "requests" -> {
                Player p = player(sender);
                boolean any = false;
                for (var r : plugin.moderation().forPlayer(p.getUniqueId())) {
                    if (r.type != ru.industryreborn.model.Request.Type.RECIPE) {
                        continue;
                    }
                    any = true;
                    String state = switch (r.status) {
                        case PENDING -> "<yellow>ждёт решения";
                        case APPROVED -> "<green>одобрена";
                        case REJECTED -> "<red>отклонена: " + Text.esc(String.valueOf(r.reason));
                    };
                    Text.send(p, "<gray> <white>" + ru.industryreborn.service.ModerationService.shortId(r) + "</white> " + Text.esc(r.plotId) + ": "
                            + Text.esc(plugin.moderation().describe(r)) + " - " + state);
                }
                if (!any) {
                    Text.send(p, "<gray>Заявок на рецепты нет. Подать: /district recipe request");
                }
            }
            case "cancel" -> {
                if (rest.length < 1) {
                    usage("recipe cancel <№ заявки>");
                }
                var r = plugin.moderation().findPending(rest[0]);
                if (r == null || r.type != ru.industryreborn.model.Request.Type.RECIPE) {
                    throw new CommandError("Заявка «" + rest[0] + "» не найдена среди ожидающих (номера: /district recipe requests).");
                }
                String error = plugin.moderation().cancel(player(sender), r);
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Заявка отозвана.");
            }
            case "price" -> {
                requireAdmin(sender);
                if (rest.length < 2) {
                    usage("recipe price <рецепт> <цена чертежа>");
                }
                double price;
                try {
                    price = Double.parseDouble(rest[1].replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new CommandError("Цена - число.");
                }
                String error = plugin.recipes().setPlayerBlueprintPrice(recipeArg(rest[0]).id(), price);
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Цена чертежа «" + rest[0] + "»: " + plugin.economy().format(price) + (price <= 0 ? " (не продаётся)." : "."));
            }
            case "remove" -> {
                requireAdmin(sender);
                if (rest.length < 1) {
                    usage("recipe remove <рецепт игрока>");
                }
                String error = plugin.recipes().removePlayerRecipe(recipeArg(rest[0]).id());
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Рецепт убран из каталога. Станки с этим заданием остановятся.");
            }
            case "learn", "forget" -> {
                requireAdmin(sender);
                if (rest.length < 2) {
                    usage("recipe " + sub + " <участок> <рецепт>");
                }
                Plot plot = plugin.plots().get(rest[0]);
                if (plot == null) {
                    throw new CommandError("Участок «" + rest[0] + "» не найден.");
                }
                RecipeBook.Recipe r = recipeArg(rest[1]);
                if (sub.equals("learn")) {
                    plot.learnedRecipes.add(r.id());
                } else if (!plot.learnedRecipes.remove(r.id())) {
                    throw new CommandError("Предприятие этот рецепт не изучало.");
                }
                plugin.plots().save();
                Text.ok(sender, sub.equals("learn") ? "Рецепт «" + r.id().substring(7) + "» изучен предприятием " + plot.id + "."
                        : "Рецепт «" + r.id().substring(7) + "» у предприятия " + plot.id + " снят.");
            }
            default -> usage("recipe [list|info <рецепт>|request ...|requests|cancel <№>|learn|forget <участок> <рецепт>|price <рецепт> <цена>|remove <рецепт>]");
        }
    }

    /**
     * /district recipe request - меню-конструктор; /district recipe request <станок> <секунды> <вход[*кол]>... -> <результат[*кол]> [участок=<id>].
     * Предметы: МАТЕРИАЛ, ir:id, nexo:id.
     */
    private void request(CommandSender sender, String[] args) {
        Player p = player(sender);
        String plotArg = null;
        List<String> tokens = new ArrayList<>();
        for (String a : args) {
            if (a.toLowerCase(Locale.ROOT).startsWith("plot=") || a.toLowerCase(Locale.ROOT).startsWith("участок=")) {
                plotArg = a.substring(a.indexOf('=') + 1);
            } else {
                tokens.add(a);
            }
        }
        Plot plot = plugin.blueprints().resolvePlot(p, plotArg);
        if (plot == null) {
            throw new CommandError("Укажите мастерскую: участок=<номер> в конце команды или встаньте в неё.");
        }
        if (!plugin.recipeRequests().mayRequest(p, plot) && !p.hasPermission("industryreborn.admin")) {
            throw new CommandError("Заявку подаёт управляющий или работник мастерской.");
        }
        if (tokens.isEmpty()) {
            if (!plugin.recipeRequests().enabled()) {
                throw new CommandError("Заявки на рецепты сейчас отключены.");
            }
            new ru.industryreborn.gui.RecipeRequestMenu(plugin, p, plot, null, null).open();
            return;
        }
        int arrow = tokens.indexOf("->");
        if (tokens.size() < 5 || arrow < 3 || arrow != tokens.size() - 2) {
            usage("recipe request <станок> <секунды> <вход[*кол]>... -> <результат[*кол]> [участок=<id>]  (станки: furnace, blast, smoker, craft)");
        }
        String station = ru.industryreborn.items.RecipeRequests.normalizeStation(tokens.get(0));
        double seconds;
        try {
            seconds = Double.parseDouble(tokens.get(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new CommandError("Время партии - число секунд.");
        }
        List<ru.industryreborn.items.RecipeRequests.Part> inputs = new ArrayList<>();
        for (String t : tokens.subList(2, arrow)) {
            var part = ru.industryreborn.items.RecipeRequests.parsePart(t);
            if (part == null) {
                throw new CommandError("Не понял предмет «" + t + "»: формат МАТЕРИАЛ*кол, ir:id*кол или nexo:id*кол.");
            }
            inputs.add(part);
        }
        var output = ru.industryreborn.items.RecipeRequests.parsePart(tokens.get(tokens.size() - 1));
        if (output == null) {
            throw new CommandError("Не понял результат «" + tokens.get(tokens.size() - 1) + "».");
        }
        var spec = new ru.industryreborn.items.RecipeRequests.Spec(station, seconds, inputs, output);
        String result = plugin.recipeRequests().submit(p, plot, spec);
        if (result == null) {
            Text.ok(sender, "Заявка отправлена администратору: " + plugin.recipeRequests().describe(spec) + ". Статус: /district recipe requests");
        } else if (result.isEmpty()) {
            Text.ok(sender, "Рецепт создан и изучен мастерской " + plot.id + " (администратору одобрение не нужно).");
        } else {
            throw new CommandError(result);
        }
    }

    // ------------------------------------------------------------ itemshop и skin

    void itemshop(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu" -> {
                if (!plugin.itemShop().enabled()) {
                    throw new CommandError("Магазин предметов сейчас отключён.");
                }
                new ru.industryreborn.gui.ItemShopMenu(plugin, player(sender), null, 0).open();
            }
            case "list" -> {
                var list = plugin.itemShop().listed();
                if (list.isEmpty()) {
                    Text.send(sender, "<gray>В магазине предметов пока ничего нет.");
                }
                for (CustomItem d : list) {
                    Text.send(sender, "<gray> <white>" + Text.esc(d.id()) + "</white> [" + d.kind().title() + "] " + Text.esc(plugin.customItems().displayName(d.ref()))
                            + " - " + plugin.economy().format(plugin.itemShop().price(d.id())));
                }
            }
            case "buy" -> {
                if (args.length < 2) {
                    usage("itemshop buy <id> [количество]");
                }
                int amount = 1;
                if (args.length > 2) {
                    try {
                        amount = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        throw new CommandError("Количество - целое число.");
                    }
                }
                String error = plugin.itemShop().buy(player(sender), args[1], amount);
                if (error != null) {
                    throw new CommandError(error);
                }
            }
            default -> usage("itemshop [menu|list|buy <id> [кол-во]]");
        }
    }

    void skin(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "apply" : args[0].toLowerCase(Locale.ROOT);
        Player p = player(sender);
        switch (sub) {
            case "apply" -> {
                if (!plugin.skins().enabled()) {
                    throw new CommandError("Скины сейчас отключены.");
                }
                if (plugin.skins().skinOf(p.getInventory().getItemInMainHand()) == null) {
                    throw new CommandError("Возьмите скин в основную руку.");
                }
                new ru.industryreborn.gui.SkinApplyMenu(plugin, p).open();
            }
            case "remove" -> {
                String error = plugin.skins().remove(p);
                if (error != null) {
                    throw new CommandError(error);
                }
            }
            case "info" -> {
                var hand = p.getInventory().getItemInMainHand();
                CustomItem skin = plugin.skins().skinOf(hand);
                String applied = plugin.skins().appliedSkin(hand);
                if (skin != null) {
                    Text.send(sender, "<yellow>Скин <white>" + Text.esc(plugin.customItems().displayName(skin.ref())) + "<gray>, ставится на: " + String.join(", ", skin.skinFor())
                            + ". Применить: /district skin");
                } else if (applied != null) {
                    Text.send(sender, "<yellow>На предмете скин <white>" + Text.esc(applied) + "<gray>. Снять: /district skin remove");
                } else {
                    throw new CommandError("В руке нет ни скина, ни предмета со скином.");
                }
            }
            default -> usage("skin [apply|remove|info]");
        }
    }

    // ------------------------------------------------------------ blueprint

    void blueprint(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "use" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        var bp = plugin.blueprints();
        switch (sub) {
            case "use" -> {
                Player p = player(sender);
                String error = bp.learnFromHand(p, bp.resolvePlot(p, rest.length > 0 ? rest[0] : null));
                if (error != null) {
                    throw new CommandError(error);
                }
            }
            case "info" -> {
                Player p = player(sender);
                var info = bp.read(p.getInventory().getItemInMainHand());
                if (info == null) {
                    throw new CommandError("В руке нет чертежа.");
                }
                RecipeBook.Recipe r = plugin.recipes().get(info.recipeId());
                Text.send(sender, "<yellow>Чертёж: <white>" + Text.esc(r == null ? info.recipeId() : plugin.recipes().outputName(r))
                        + "<gray> (рецепт " + Text.esc(info.recipeId().replace("custom:", "")) + ")"
                        + (bp.used(info.serial()) ? " <red>уже использован" : " <green>не использован"));
            }
            case "shop" -> {
                if (!bp.enabled()) {
                    throw new CommandError("Чертежи сейчас отключены.");
                }
                new ru.industryreborn.gui.BlueprintShopMenu(plugin, player(sender), null, 0).open();
            }
            case "buy" -> {
                if (rest.length < 1) {
                    usage("blueprint buy <рецепт>");
                }
                String error = bp.buy(player(sender), rest[0]);
                if (error != null) {
                    throw new CommandError(error);
                }
            }
            case "give" -> {
                requireAdmin(sender);
                if (rest.length < 2) {
                    usage("blueprint give <игрок> <рецепт> [количество]");
                }
                Player target = org.bukkit.Bukkit.getPlayerExact(rest[0]);
                if (target == null) {
                    throw new CommandError("Игрок «" + rest[0] + "» не в сети.");
                }
                int amount = 1;
                if (rest.length > 2) {
                    try {
                        amount = Integer.parseInt(rest[2]);
                    } catch (NumberFormatException e) {
                        throw new CommandError("Количество - целое число.");
                    }
                }
                String error = bp.give(target, rest[1], amount);
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Выдано " + target.getName() + ": чертёж «" + rest[1] + "» × " + amount + ".");
            }
            case "vault" -> {
                if (!bp.mayUseVault(sender)) {
                    throw new CommandError("Недостаточно прав: нужно право industryreborn.vault.");
                }
                Player p = player(sender);
                String what = rest.length == 0 ? "open" : rest[0].toLowerCase(Locale.ROOT);
                if (what.equals("put")) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand.getType().isAir()) {
                        throw new CommandError("Возьмите в руку предмет, который нужно положить в хранилище.");
                    }
                    bp.deposit(hand.clone(), "положил " + p.getName());
                    p.getInventory().setItemInMainHand(null);
                    Text.ok(sender, "Предмет положен в хранилище администрации.");
                } else {
                    new ru.industryreborn.gui.BlueprintVaultMenu(plugin, p, 0).open();
                }
            }
            default -> usage("blueprint [use [участок]|info|shop|buy <рецепт>|give <игрок> <рецепт> [кол-во]|vault [put]]");
        }
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        throw new CommandError("Эту команду можно выполнить только игроку.");
    }

    private RecipeBook.Recipe recipeArg(String id) {
        RecipeBook.Recipe r = plugin.recipes().get("custom:" + id.toLowerCase(Locale.ROOT));
        if (r == null) {
            throw new CommandError("Рецепт «" + id + "» не найден: /district recipe list");
        }
        return r;
    }

    List<String> complete(String sub, CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        int n = args.length;
        if (sub.equals("blueprint")) {
            if (n == 2) {
                return List.of("use", "info", "shop", "buy", "give", "vault");
            }
            if (n == 3 && args[1].equalsIgnoreCase("vault")) {
                return List.of("put");
            }
            if ((n == 3 && args[1].equalsIgnoreCase("buy")) || (n == 4 && args[1].equalsIgnoreCase("give"))) {
                plugin.recipes().customRecipes().forEach(r -> out.add(r.id().substring(7)));
            }
            if (n == 3 && args[1].equalsIgnoreCase("give")) {
                org.bukkit.Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
            }
            return out;
        }
        if (sub.equals("item")) {
            if (n == 2) {
                return List.of("list", "info", "give", "price", "reload", "export-nexo");
            }
            if (n == 3 && args[1].equalsIgnoreCase("give")) {
                org.bukkit.Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
            if ((n == 3 && List.of("info", "price").contains(args[1].toLowerCase(Locale.ROOT))) || (n == 4 && args[1].equalsIgnoreCase("give"))) {
                plugin.customItems().all().forEach(d -> out.add(d.id()));
            }
            return out;
        }
        if (sub.equals("itemshop")) {
            if (n == 2) {
                return List.of("menu", "list", "buy");
            }
            if (n == 3 && args[1].equalsIgnoreCase("buy")) {
                plugin.itemShop().listed().forEach(d -> out.add(d.id()));
            }
            return out;
        }
        if (sub.equals("skin")) {
            return n == 2 ? List.of("apply", "remove", "info") : out;
        }
        // recipe
        if (n == 2) {
            return List.of("list", "info", "request", "requests", "cancel", "learn", "forget", "price", "remove");
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("request")) {
            if (n == 3) {
                return ru.industryreborn.items.RecipeRequests.stations();
            }
            if (n >= 5) {
                plugin.customItems().all().forEach(d -> out.add("ir:" + d.id()));
                out.add("->");
            }
            return out;
        }
        if ((n == 3 && List.of("info", "price", "remove").contains(action)) || (n == 4 && List.of("learn", "forget").contains(action))) {
            plugin.recipes().customRecipes().forEach(r -> out.add(r.id().substring(7)));
        }
        if (n == 3 && List.of("learn", "forget").contains(action)) {
            plugin.plots().all().stream().filter(p -> p.category == ru.industryreborn.model.Category.WORKSHOP).forEach(p -> out.add(p.id));
        }
        return out;
    }
}
