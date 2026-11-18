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
                    Text.send(sender, "<gray> <white>" + Text.esc(d.id()) + "</white> - " + Text.esc(items.displayName(d.ref())) + "<gray> ("
                            + d.material().name() + (d.nexoId() != null ? ", Nexo: " + Text.esc(d.nexoId()) : "") + ")");
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
            default -> usage("item [list|info <id>|give <игрок> <id> [кол-во]|reload|export-nexo]");
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
            default -> usage("recipe [list|info <рецепт>|learn <участок> <рецепт>|forget <участок> <рецепт>]");
        }
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
        if (sub.equals("item")) {
            if (n == 2) {
                return List.of("list", "info", "give", "reload", "export-nexo");
            }
            if (n == 3 && args[1].equalsIgnoreCase("give")) {
                org.bukkit.Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
            if ((n == 3 && args[1].equalsIgnoreCase("info")) || (n == 4 && args[1].equalsIgnoreCase("give"))) {
                plugin.customItems().all().forEach(d -> out.add(d.id()));
            }
            return out;
        }
        if (n == 2) {
            return List.of("list", "info", "learn", "forget");
        }
        if ((n == 3 && args[1].equalsIgnoreCase("info")) || (n == 4 && List.of("learn", "forget").contains(args[1].toLowerCase(Locale.ROOT)))) {
            plugin.recipes().customRecipes().forEach(r -> out.add(r.id().substring(7)));
        }
        if (n == 3 && List.of("learn", "forget").contains(args[1].toLowerCase(Locale.ROOT))) {
            plugin.plots().all().stream().filter(p -> p.category == ru.industryreborn.model.Category.WORKSHOP).forEach(p -> out.add(p.id));
        }
        return out;
    }
}
