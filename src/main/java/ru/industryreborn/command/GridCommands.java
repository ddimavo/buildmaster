package ru.industryreborn.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.gui.GridMenu;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.District;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.GridService;
import ru.industryreborn.util.Text;

/** Команды электросети и районов: /district grid ..., /district area ... (право industryreborn.grid). */
final class GridCommands {

    private final IndustryRebornPlugin plugin;

    GridCommands(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    private Plot plot(String id) {
        Plot p = plugin.plots().get(id);
        if (p == null) {
            throw new CommandError("Участок «" + id + "» не найден.");
        }
        return p;
    }

    private Plot plant(String id) {
        Plot p = plot(id);
        if (p.category != Category.POWER_PLANT) {
            throw new CommandError("«" + id + "» не электростанция.");
        }
        return p;
    }

    private District district(String name) {
        District d = plugin.districts().get(name);
        if (d == null) {
            throw new CommandError("Район «" + name + "» не найден.");
        }
        return d;
    }

    /** Участок или район (area:название) -> список предприятий. */
    private List<Plot> targets(String arg) {
        if (arg.toLowerCase(Locale.ROOT).startsWith("area:")) {
            List<Plot> list = plugin.grid().plotsOf(district(arg.substring(5)));
            if (list.isEmpty()) {
                throw new CommandError("В районе нет участков.");
            }
            return list;
        }
        return List.of(plot(arg));
    }

    private static String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    // ------------------------------------------------------------ grid

    void grid(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        GridService grid = plugin.grid();
        switch (sub) {
            case "menu" -> {
                if (!(sender instanceof Player p)) {
                    info(sender);
                } else if (args.length > 1 && args[1].equalsIgnoreCase("classic") || !plugin.htmlMenus().open(p, ru.industryreborn.html.HtmlMenus.Kind.GRID)) {
                    new GridMenu(plugin, p, 0).open();
                }
            }
            case "info" -> info(sender);
            case "capacity" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district grid capacity <станция> <V в сутки>");
                }
                double v;
                try {
                    v = Double.parseDouble(args[2].replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new CommandError("Мощность должна быть числом.");
                }
                String error = grid.setCapacity(plant(args[1]), v, sender.getName());
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Мощность станции " + args[1] + ": " + v + " V/сут.");
            }
            case "price" -> {
                if (args.length < 2) {
                    throw new CommandError("Использование: /district grid price <цена за 1 V|reset>");
                }
                // цена электроэнергии - это база тарифа
                forwardPower(sender, new String[] {"base", args[1]});
            }
            case "connect" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district grid connect <участок|area:район> <станция>");
                }
                Plot plant = plant(args[2]);
                int ok = 0;
                String last = null;
                for (Plot p : targets(args[1])) {
                    String error = grid.connect(p, plant, sender.getName());
                    if (error == null) {
                        ok++;
                    } else {
                        last = error;
                    }
                }
                if (ok == 0 && last != null) {
                    throw new CommandError(last);
                }
                Text.ok(sender, "Подключено предприятий к станции " + plant.id + ": " + ok + ".");
            }
            case "disconnect" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district grid disconnect <участок|area:район> <причина>");
                }
                String reason = join(args, 2);
                int ok = 0;
                String last = null;
                for (Plot p : targets(args[1])) {
                    String error = grid.disconnect(p, reason, sender.getName());
                    if (error == null) {
                        ok++;
                    } else {
                        last = error;
                    }
                }
                if (ok == 0 && last != null) {
                    throw new CommandError(last);
                }
                Text.ok(sender, "Отключено предприятий: " + ok + ".");
            }
            case "restore" -> {
                if (args.length < 2) {
                    throw new CommandError("Использование: /district grid restore <участок|area:район>");
                }
                int ok = 0;
                String last = null;
                for (Plot p : targets(args[1])) {
                    String error = grid.restore(p, sender.getName());
                    if (error == null) {
                        ok++;
                    } else {
                        last = error;
                    }
                }
                if (ok == 0 && last != null) {
                    throw new CommandError(last);
                }
                Text.ok(sender, "Подключено предприятий: " + ok + ".");
            }
            case "autoconnect" -> Text.ok(sender, "Подключено к наименее загруженным станциям: "
                    + grid.autoConnectAll(sender.getName()) + ".");
            case "report" -> report(sender, args.length > 1 ? args[1] : null);
            default -> throw new CommandError("Использование: /district grid [info|capacity|price|connect|disconnect|restore|autoconnect|report]");
        }
    }

    /** Цена электроэнергии = база тарифа: используем те же правила, что и power admin. */
    private void forwardPower(CommandSender sender, String[] args) {
        var o = plugin.power().overrides();
        Double value;
        if (args[1].equalsIgnoreCase("reset")) {
            value = null;
        } else {
            try {
                value = Double.parseDouble(args[1].replace(',', '.'));
            } catch (NumberFormatException e) {
                throw new CommandError("Цена должна быть числом (или reset).");
            }
            if (value < 0 || value.isNaN() || value.isInfinite()) {
                throw new CommandError("Цена не может быть отрицательной.");
            }
        }
        o.setBase(value);
        plugin.power().saveOverrides();
        plugin.power().reloadTariffs(sender.getName(), "цена за 1 V " + (value == null ? "сброшена к config.yml" : "= " + value));
        Text.ok(sender, "Цена электроэнергии за 1 V: " + plugin.power().base() + ".");
    }

    private void info(CommandSender sender) {
        GridService grid = plugin.grid();
        List<Plot> plants = grid.plants();
        Text.send(sender, "<yellow>Электросеть: цена <white>" + plugin.power().base() + "</white> за 1 V, станций: "
                + plants.size() + (plants.isEmpty() ? " (сеть не действует, мастерские работают без подключения)" : ""));
        for (Plot plant : plants) {
            double demand = grid.demand(plant);
            double load = grid.load(plant);
            sender.sendMessage(Text.mm("<gray> " + Text.esc(plant.id) + ": мощность <white>" + plant.plantCapacity
                    + " V/сут</white>, потребление <white>" + round(demand) + "</white> ("
                    + (Double.isInfinite(load) ? "перегрузка" : round(load * 100) + "%")
                    + "), тариф ×<white>" + plant.plantMultiplier + "</white>, предприятий: " + grid.connected(plant).size()));
        }
        if (!plants.isEmpty()) {
            long loose = plugin.plots().all().stream()
                    .filter(p -> p.category == Category.WORKSHOP && p.plantId == null).count();
            if (loose > 0) {
                sender.sendMessage(Text.mm("<red> Не подключено к станциям мастерских: " + loose
                        + ". Подключить: /district grid autoconnect"));
            }
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private void report(CommandSender sender, String arg) {
        GridService grid = plugin.grid();
        if (arg != null) {
            List<Plot> plots = targets(arg);
            GridService.Report r = grid.report(plots);
            Text.send(sender, "<yellow>Потребление: <white>" + Text.esc(arg));
            line(sender, "итого", r);
            for (Plot p : plots) {
                line(sender, p.id, grid.report(List.of(p)));
            }
            return;
        }
        Text.send(sender, "<yellow>Потребление электроэнергии по районам:");
        List<Plot> assigned = new ArrayList<>();
        for (District d : plugin.districts().all()) {
            List<Plot> plots = grid.plotsOf(d);
            assigned.addAll(plots);
            line(sender, d.name, grid.report(plots));
        }
        List<Plot> loose = new ArrayList<>(plugin.plots().all());
        loose.removeAll(assigned);
        line(sender, "без района", grid.report(loose));
    }

    private void line(CommandSender sender, String name, GridService.Report r) {
        sender.sendMessage(Text.mm("<gray> " + Text.esc(name) + ": предприятий <white>" + r.plots()
                + "</white>, нагрузка <white>" + round(r.demand()) + " V/сут</white>, за сутки <white>"
                + round(r.day().units()) + " V</white> (" + plugin.economy().format(r.day().money())
                + "), за 7 суток <white>" + round(r.week().units()) + " V</white> ("
                + plugin.economy().format(r.week().money()) + ")"));
    }

    // ------------------------------------------------------------ area

    void area(CommandSender sender, String[] args) {
        if (args.length == 0) {
            throw new CommandError("Использование: /district area <create|delete|add|remove|list|info> ...");
        }
        var districts = plugin.districts();
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (args.length < 2) {
                    throw new CommandError("Использование: /district area create <название>");
                }
                if (!args[1].matches("^[\\p{L}\\p{N}_-]{1,24}$")) {
                    throw new CommandError("Название района: буквы, цифры, _ и -, до 24 символов.");
                }
                if (districts.create(args[1]) == null) {
                    throw new CommandError("Район с таким названием уже есть.");
                }
                saveDistricts();
                Text.ok(sender, "Район «" + args[1] + "» создан.");
            }
            case "delete" -> {
                if (args.length < 2 || !districts.delete(args[1])) {
                    throw new CommandError("Район не найден. Использование: /district area delete <название>");
                }
                saveDistricts();
                Text.ok(sender, "Район удалён.");
            }
            case "add" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district area add <район> <участок> [участок...]");
                }
                District d = district(args[1]);
                for (int i = 2; i < args.length; i++) {
                    districts.assign(plot(args[i]).id, d);
                }
                saveDistricts();
                Text.ok(sender, "В район «" + d.name + "» добавлено участков: " + (args.length - 2) + ".");
            }
            case "remove" -> {
                if (args.length < 2) {
                    throw new CommandError("Использование: /district area remove <участок> [участок...]");
                }
                int n = 0;
                for (int i = 1; i < args.length; i++) {
                    if (districts.remove(args[i])) {
                        n++;
                    }
                }
                saveDistricts();
                Text.ok(sender, "Убрано из районов участков: " + n + ".");
            }
            case "list" -> {
                if (districts.all().isEmpty()) {
                    Text.send(sender, "<gray>Районов пока нет.");
                }
                for (District d : districts.all()) {
                    sender.sendMessage(Text.mm("<gray> " + Text.esc(d.name) + ": участков " + d.plotIds.size()));
                }
            }
            case "info" -> {
                if (args.length < 2) {
                    throw new CommandError("Использование: /district area info <название>");
                }
                District d = district(args[1]);
                Text.send(sender, "<yellow>Район <white>" + Text.esc(d.name) + "</white>: "
                        + Text.esc(d.plotIds.isEmpty() ? "пусто" : String.join(", ", d.plotIds)));
            }
            default -> throw new CommandError("Использование: /district area <create|delete|add|remove|list|info> ...");
        }
    }

    private void saveDistricts() {
        try {
            plugin.districts().save();
        } catch (java.io.IOException e) {
            throw new CommandError("Не удалось сохранить районы: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ автодополнение

    List<String> complete(CommandSender sender, String[] args) {
        int n = args.length;
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> plots = new ArrayList<>();
        plugin.plots().all().forEach(p -> plots.add(p.id));
        List<String> targets = new ArrayList<>(plots);
        plugin.districts().all().forEach(d -> targets.add("area:" + d.name));
        List<String> plants = new ArrayList<>();
        plugin.grid().plants().forEach(p -> plants.add(p.id));
        if (sub.equals("grid")) {
            if (n == 2) {
                return List.of("menu", "info", "capacity", "price", "connect", "disconnect", "restore", "autoconnect", "report");
            }
            String g = args[1].toLowerCase(Locale.ROOT);
            if (n == 3 && g.equals("capacity")) {
                return plants;
            }
            if (n == 3 && List.of("connect", "disconnect", "restore", "report").contains(g)) {
                return targets;
            }
            if (n == 4 && g.equals("connect")) {
                return plants;
            }
            return List.of();
        }
        if (n == 2) {
            return List.of("create", "delete", "add", "remove", "list", "info");
        }
        String a = args[1].toLowerCase(Locale.ROOT);
        if (n == 3 && List.of("delete", "add", "info").contains(a)) {
            List<String> names = new ArrayList<>();
            plugin.districts().all().forEach(d -> names.add(d.name));
            return names;
        }
        if ((n >= 4 && a.equals("add")) || (n >= 3 && a.equals("remove"))) {
            return plots;
        }
        return List.of();
    }
}
