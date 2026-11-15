package ru.buildmaster.command;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.gui.MainMenu;
import ru.buildmaster.listener.SelectionListener;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

public final class DistrictCommand implements CommandExecutor, TabCompleter {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final Pattern ZONE = Pattern.compile("^[\\p{L}\\p{N}_-]{1,24}$");
    private static final int MAX_ZONES = 18;
    private static final int MAX_TRUSTED = 10;

    private static final List<String> PLAYER_SUBS = List.of("menu", "list", "info", "buy", "rent", "extend",
            "autorenew", "release", "trust", "untrust", "tp", "replace", "zones", "claim", "help");
    private static final List<String> ADMIN_SUBS = List.of("wand", "pos1", "pos2", "create", "delete", "evict",
            "setprice", "setrent", "setspawn", "zone", "resnapshot", "reload");

    private final BuildMasterPlugin plugin;

    public DistrictCommand(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        if (ADMIN_SUBS.contains(sub) && !sender.hasPermission("buildmaster.admin")) {
            Text.err(sender, "Недостаточно прав.");
            return true;
        }
        if (PLAYER_SUBS.contains(sub) && !sender.hasPermission("buildmaster.use")) {
            Text.err(sender, "Недостаточно прав.");
            return true;
        }
        try {
            switch (sub) {
                case "menu" -> withPlayer(sender, p -> new MainMenu(plugin, p).open());
                case "help" -> help(sender);
                case "list" -> list(sender);
                case "info" -> withPlayer(sender, p -> info(p, plotOrHere(p, rest.length > 0 ? rest[0] : null)));
                case "buy" -> withPlayer(sender, p -> {
                    Plot plot = plotOrHere(p, rest.length > 0 ? rest[0] : null);
                    if (plot != null) {
                        plugin.service().buy(p, plot);
                    }
                });
                case "rent" -> withPlayer(sender, p -> rent(p, rest, false));
                case "extend" -> withPlayer(sender, p -> rent(p, rest, true));
                case "autorenew" -> withPlayer(sender, p -> autoRenew(p, rest));
                case "release" -> withPlayer(sender, p -> release(p, rest));
                case "trust" -> withPlayer(sender, p -> trust(p, rest, true));
                case "untrust" -> withPlayer(sender, p -> trust(p, rest, false));
                case "tp" -> withPlayer(sender, p -> teleport(p, rest));
                case "replace" -> withPlayer(sender, p -> replace(p, rest));
                case "zones" -> withPlayer(sender, p -> zones(p, rest.length > 0 ? rest[0] : null));
                case "claim" -> withPlayer(sender, this::claim);
                case "wand" -> withPlayer(sender, p -> {
                    p.getInventory().addItem(plugin.selection().createWand());
                    Text.ok(p, "Выдан топор выделения: ЛКМ - точка 1, ПКМ - точка 2.");
                });
                case "pos1", "pos2" -> withPlayer(sender, p -> {
                    Location l = p.getLocation();
                    plugin.selection().setPoint(p, sub.equals("pos1") ? 1 : 2, l.getBlockX(), l.getBlockY(), l.getBlockZ());
                });
                case "create" -> withPlayer(sender, p -> create(p, rest));
                case "delete" -> delete(sender, rest);
                case "evict" -> evict(sender, rest);
                case "setprice" -> setPrice(sender, rest, true);
                case "setrent" -> setPrice(sender, rest, false);
                case "setspawn" -> withPlayer(sender, p -> setSpawn(p, rest));
                case "zone" -> withPlayer(sender, p -> zone(p, rest));
                case "resnapshot" -> withPlayer(sender, p -> resnapshot(p, rest));
                case "reload" -> {
                    plugin.reload();
                    plugin.plots().load();
                    Text.ok(sender, "Конфигурация и участки перезагружены.");
                }
                default -> {
                    Text.err(sender, "Неизвестная подкоманда. Список: /district help");
                }
            }
        } catch (CommandError e) {
            Text.err(sender, e.getMessage());
        }
        return true;
    }

    // ---------------------------------------------------------------- вспомогательное

    private static final class CommandError extends RuntimeException {
        CommandError(String message) {
            super(message, null, false, false);
        }
    }

    private void withPlayer(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (sender instanceof Player p) {
            action.accept(p);
        } else {
            throw new CommandError("Эту команду можно выполнить только игроку.");
        }
    }

    /** Участок по id либо участок, в котором стоит игрок. */
    private Plot plotOrHere(Player p, String id) {
        if (id != null) {
            Plot plot = plugin.plots().get(id);
            if (plot == null) {
                throw new CommandError("Участок «" + id + "» не найден.");
            }
            return plot;
        }
        Plot plot = plugin.plots().at(p.getLocation());
        if (plot == null) {
            throw new CommandError("Вы не стоите на участке. Укажите его номер.");
        }
        return plot;
    }

    private Plot ownPlot(Player p, String id) {
        Plot plot = plotOrHere(p, id);
        if (!plot.isOwner(p.getUniqueId())) {
            throw new CommandError("Это не ваш участок.");
        }
        return plot;
    }

    private static int parseInt(String s, String what) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new CommandError("Нужно целое число: " + what + ".");
        }
    }

    /** Цена: число >= 0 либо "-" (нет). */
    private static double parsePrice(String s) {
        if (s.equals("-") || s.equalsIgnoreCase("нет") || s.equalsIgnoreCase("no")) {
            return -1;
        }
        try {
            double v = Double.parseDouble(s.replace(',', '.'));
            if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new CommandError("Цена должна быть числом от 0 или «-» (нет).");
        }
    }

    private String money(double v) {
        return v < 0 ? "нет" : plugin.economy().format(v);
    }

    // ---------------------------------------------------------------- игрок

    private void help(CommandSender s) {
        Text.send(s, "<yellow>Команды района коммерции:");
        String[] lines = {
                "/district - главное меню",
                "/district list - список участков",
                "/district info [номер] - сведения об участке",
                "/district buy [номер] - купить участок",
                "/district rent <дней> [номер] - арендовать",
                "/district extend <дней> [номер] - продлить аренду",
                "/district autorenew <on|off> [номер] - автопродление",
                "/district release [номер] confirm - отказаться от участка",
                "/district trust|untrust <игрок> [номер] - доверенные игроки",
                "/district replace <зона|все> <из> <в> - заменить материал в здании",
                "/district zones [номер] - части здания",
                "/district tp <номер> - телепорт к участку",
                "/district claim - забрать возвращённые предметы"};
        for (String l : lines) {
            s.sendMessage(Text.mm("<gray>" + Text.esc(l)));
        }
        if (s.hasPermission("buildmaster.admin")) {
            Text.send(s, "<yellow>Для администратора:");
            String[] admin = {
                    "/district wand | pos1 | pos2 - выделение области",
                    "/district create <номер> <цена|-> <аренда в сутки|-> - создать участок из выделения",
                    "/district delete <номер> - удалить свободный участок",
                    "/district evict <номер> - выселить владельца/сбросить здание",
                    "/district setprice|setrent <номер> <цена|-> - изменить цены",
                    "/district setspawn [номер] - точка телепорта",
                    "/district zone add <номер> <имя> [блок-иконка] | remove <номер> <имя> | list [номер]",
                    "/district resnapshot <номер> - запомнить текущий вид как исходный",
                    "/district reload - перезагрузка"};
            for (String l : admin) {
                s.sendMessage(Text.mm("<gray>" + Text.esc(l)));
            }
        }
    }

    private void list(CommandSender sender) {
        if (plugin.plots().all().isEmpty()) {
            Text.send(sender, "<gray>Участков пока нет.");
            return;
        }
        Text.send(sender, "<yellow>Участки:");
        for (Plot p : plugin.plots().all()) {
            String state = switch (p.status) {
                case FREE -> "<green>свободен</green>, цена " + money(p.price) + ", аренда " + money(p.rentPerDay) + "/сут.";
                case OWNED -> "<red>куплен</red> (" + Text.esc(String.valueOf(p.ownerName)) + ")";
                case RENTED -> "<yellow>арендован</yellow> (" + Text.esc(String.valueOf(p.ownerName)) + ")";
            };
            sender.sendMessage(Text.mm("<gray> • <white>" + Text.esc(p.id) + "</white> - " + state));
        }
    }

    private void info(Player p, Plot plot) {
        Text.send(p, "<yellow>Участок <white>" + Text.esc(plot.id) + "</white> (" + plot.box.describe() + ")");
        for (String line : plotInfoLines(plot)) {
            p.sendMessage(Text.mm(line));
        }
    }

    private List<String> plotInfoLines(Plot plot) {
        List<String> lines = new ArrayList<>();
        switch (plot.status) {
            case FREE -> {
                lines.add("<gray> Статус: <green>свободен");
                lines.add("<gray> Цена: <white>" + money(plot.price) + "<gray>, аренда: <white>" + money(plot.rentPerDay) + "/сут.");
            }
            case OWNED -> {
                lines.add("<gray> Статус: <red>куплен<gray>, владелец: <white>" + Text.esc(String.valueOf(plot.ownerName)));
            }
            case RENTED -> {
                lines.add("<gray> Статус: <yellow>арендован<gray>, арендатор: <white>" + Text.esc(String.valueOf(plot.ownerName)));
                lines.add("<gray> Осталось: <white>" + Text.duration(plot.rentedUntil - System.currentTimeMillis())
                        + "<gray>, автопродление: " + (plot.autoRenew ? "<green>вкл" : "<red>выкл"));
            }
        }
        if (!plot.zones.isEmpty()) {
            lines.add("<gray> Зоны: <white>" + Text.esc(String.join(", ", plot.zones.keySet())));
        }
        return lines;
    }

    /** rent <дней> [номер] и extend <дней> [номер]. */
    private void rent(Player p, String[] args, boolean extend) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district " + (extend ? "extend" : "rent") + " <дней> [номер]");
        }
        int days = parseInt(args[0], "количество суток");
        Plot plot = plotOrHere(p, args.length > 1 ? args[1] : null);
        if (extend) {
            plugin.service().extend(p, plot, days);
        } else {
            plugin.service().rent(p, plot, days);
        }
    }

    private void autoRenew(Player p, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district autorenew <on|off> [номер]");
        }
        boolean value = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "вкл", "true" -> true;
            case "off", "выкл", "false" -> false;
            default -> throw new CommandError("Укажите on или off.");
        };
        Plot plot = ownPlot(p, args.length > 1 ? args[1] : null);
        if (plot.status != Plot.Status.RENTED) {
            throw new CommandError("Автопродление есть только у арендованных участков.");
        }
        plugin.service().setAutoRenew(plot, value);
        Text.ok(p, "Автопродление " + (value ? "включено" : "выключено") + ".");
    }

    private void release(Player p, String[] args) {
        String id = null;
        boolean confirmed = false;
        for (String a : args) {
            if (a.equalsIgnoreCase("confirm") || a.equalsIgnoreCase("подтвердить")) {
                confirmed = true;
            } else {
                id = a;
            }
        }
        Plot plot = ownPlot(p, id);
        if (!confirmed) {
            Text.send(p, "<red>Вы отказываетесь от участка <white>" + plot.id + "</white>. Все ваши изменения будут "
                    + "удалены, а предметы вернутся вам.");
            Text.send(p, "<yellow>Для подтверждения введите: <white>/district release " + plot.id + " confirm");
            return;
        }
        plugin.service().release(p, plot);
    }

    private void trust(Player p, String[] args, boolean add) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district " + (add ? "trust" : "untrust") + " <игрок> [номер]");
        }
        Plot plot = ownPlot(p, args.length > 1 ? args[1] : null);
        OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(args[0]);
        }
        if (target == null) {
            throw new CommandError("Игрок «" + args[0] + "» не найден.");
        }
        UUID id = target.getUniqueId();
        if (add) {
            if (id.equals(p.getUniqueId())) {
                throw new CommandError("Вы и так владелец.");
            }
            if (plot.trusted.size() >= MAX_TRUSTED) {
                throw new CommandError("Нельзя добавить больше " + MAX_TRUSTED + " доверенных игроков.");
            }
            plot.trusted.add(id);
            Text.ok(p, target.getName() + " теперь может строить на участке " + plot.id + ".");
        } else {
            if (!plot.trusted.remove(id)) {
                throw new CommandError(target.getName() + " не в списке доверенных.");
            }
            Text.ok(p, target.getName() + " больше не может строить на участке " + plot.id + ".");
        }
        plugin.plots().save();
    }

    private void teleport(Player p, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district tp <номер>");
        }
        Plot plot = plotOrHere(p, args[0]);
        if (!plugin.getConfig().getBoolean("allow-visit-teleport", true) && !plot.isMember(p.getUniqueId())
                && !p.hasPermission("buildmaster.admin")) {
            throw new CommandError("Телепорт доступен только к своим участкам.");
        }
        plugin.service().teleport(p, plot);
    }

    private void replace(Player p, String[] args) {
        if (args.length < 3) {
            throw new CommandError("Использование: /district replace <зона|все> <из> <в>  (например: /district replace фасад oak_planks bricks)");
        }
        Plot plot = plugin.plots().at(p.getLocation());
        if (plot == null) {
            throw new CommandError("Встаньте внутрь своего участка.");
        }
        if (!plot.isOwner(p.getUniqueId())) {
            throw new CommandError("Менять здание может только владелец участка.");
        }
        Box area;
        if (args[0].equalsIgnoreCase("все") || args[0].equalsIgnoreCase("all")) {
            area = plot.box;
        } else {
            Plot.Zone zone = findZone(plot, args[0]);
            if (zone == null) {
                throw new CommandError("Зона «" + args[0] + "» не найдена. Доступно: все"
                        + (plot.zones.isEmpty() ? "" : ", " + String.join(", ", plot.zones.keySet())));
            }
            area = zone.box();
        }
        Material from = Material.matchMaterial(args[1]);
        Material to = Material.matchMaterial(args[2]);
        if (from == null || !from.isBlock()) {
            throw new CommandError("Неизвестный блок: " + args[1]);
        }
        if (to == null || !to.isBlock()) {
            throw new CommandError("Неизвестный блок: " + args[2]);
        }
        plugin.build().replace(p, plot, area, from, to);
    }

    private static Plot.Zone findZone(Plot plot, String name) {
        for (Plot.Zone z : plot.zones.values()) {
            if (z.name().equalsIgnoreCase(name)) {
                return z;
            }
        }
        return null;
    }

    private void zones(Player p, String id) {
        Plot plot = plotOrHere(p, id);
        if (plot.zones.isEmpty()) {
            Text.send(p, "<gray>У участка нет отдельных зон. Можно заменять во всём здании: /district replace все ...");
            return;
        }
        Text.send(p, "<yellow>Зоны участка " + plot.id + ": <white>все, " + Text.esc(String.join(", ", plot.zones.keySet())));
    }

    private void claim(Player p) {
        int before = plugin.pending().count(p.getUniqueId());
        if (before == 0) {
            Text.send(p, "<gray>Для вас нет возвращённых предметов.");
            return;
        }
        int left = plugin.pending().claim(p);
        if (left == 0) {
            Text.ok(p, "Все предметы выданы (" + before + " шт.).");
        } else {
            Text.send(p, "<yellow>Выдано " + (before - left) + " шт., осталось " + left + ". Освободите место и повторите.");
        }
    }

    // ---------------------------------------------------------------- администратор

    private void create(Player p, String[] args) {
        if (args.length < 3) {
            throw new CommandError("Использование: /district create <номер> <цена|-> <аренда в сутки|->");
        }
        String id = args[0];
        if (!ID.matcher(id).matches()) {
            throw new CommandError("Номер участка: латиница, цифры, _ и -, до 32 символов.");
        }
        if (plugin.plots().get(id) != null || plugin.service().isCreating(id)) {
            throw new CommandError("Участок с таким номером уже есть.");
        }
        double price = parsePrice(args[1]);
        double rent = parsePrice(args[2]);
        if (price < 0 && rent < 0) {
            throw new CommandError("Нужна хотя бы цена покупки или аренды.");
        }
        SelectionListener.Selection sel = plugin.selection().get(p);
        Box box = sel == null ? null : sel.box();
        if (box == null) {
            throw new CommandError("Сначала выделите область: /district wand (или pos1 и pos2).");
        }
        if (!p.getWorld().getName().equals(sel.world)) {
            throw new CommandError("Выделение сделано в другом мире.");
        }
        long max = plugin.getConfig().getLong("max-plot-volume", 2_000_000);
        if (box.volume() > max) {
            throw new CommandError("Слишком большая область: " + box.volume() + " блоков (максимум " + max + ").");
        }
        Plot overlap = plugin.plots().overlapping(sel.world, box);
        if (overlap != null) {
            throw new CommandError("Область пересекается с участком " + overlap.id + ".");
        }
        plugin.service().create(p, id, box, price, rent);
    }

    private Plot requirePlot(String id) {
        Plot plot = plugin.plots().get(id);
        if (plot == null) {
            throw new CommandError("Участок «" + id + "» не найден.");
        }
        return plot;
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district delete <номер>");
        }
        Plot plot = requirePlot(args[0]);
        if (!plot.isFree() || plugin.service().isLocked(plot)) {
            throw new CommandError("Удалять можно только свободный участок. Сначала: /district evict " + plot.id);
        }
        plugin.plots().remove(plot);
        File snap = plugin.plots().snapshotFile(plot.id);
        if (snap.exists() && !snap.delete()) {
            plugin.getLogger().warning("Не удалось удалить " + snap);
        }
        plugin.plots().save();
        Text.ok(sender, "Участок " + plot.id + " удалён.");
    }

    private void evict(CommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district evict <номер>");
        }
        Plot plot = requirePlot(args[0]);
        if (plugin.service().isLocked(plot)) {
            throw new CommandError("Участок сейчас обрабатывается.");
        }
        plugin.service().evict(plot, "Администратор освободил участок <white>" + plot.id + "</white>.");
        Text.ok(sender, "Участок " + plot.id + " освобождается, здание сбрасывается.");
    }

    private void setPrice(CommandSender sender, String[] args, boolean buy) {
        if (args.length < 2) {
            throw new CommandError("Использование: /district " + (buy ? "setprice" : "setrent") + " <номер> <цена|->");
        }
        Plot plot = requirePlot(args[0]);
        double value = parsePrice(args[1]);
        if (buy) {
            plot.price = value;
        } else {
            plot.rentPerDay = value;
        }
        plugin.plots().save();
        Text.ok(sender, (buy ? "Цена покупки" : "Аренда в сутки") + " участка " + plot.id + ": " + money(value));
    }

    private void setSpawn(Player p, String[] args) {
        Plot plot = plotOrHere(p, args.length > 0 ? args[0] : null);
        Location l = p.getLocation();
        if (!plot.world.equals(l.getWorld().getName()) || !plot.box.contains(l.getBlockX(), l.getBlockY(), l.getBlockZ())) {
            throw new CommandError("Точка телепорта должна быть внутри участка.");
        }
        plot.hasSpawn = true;
        plot.spawnX = l.getX();
        plot.spawnY = l.getY();
        plot.spawnZ = l.getZ();
        plot.spawnYaw = l.getYaw();
        plot.spawnPitch = l.getPitch();
        plugin.plots().save();
        Text.ok(p, "Точка телепорта участка " + plot.id + " установлена.");
    }

    private void resnapshot(Player p, String[] args) {
        Plot plot = plotOrHere(p, args.length > 0 ? args[0] : null);
        if (!plot.isFree() || plugin.service().isLocked(plot)) {
            throw new CommandError("Обновлять исходный вид можно только у свободного участка.");
        }
        plugin.service().resnapshot(p, plot);
    }

    private void zone(Player p, String[] args) {
        if (args.length < 1) {
            throw new CommandError("Использование: /district zone <add|remove|list> ...");
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district zone add <номер> <имя> [блок-иконка]");
                }
                Plot plot = requirePlot(args[1]);
                String name = args[2].toLowerCase(Locale.ROOT);
                if (!ZONE.matcher(name).matches()) {
                    throw new CommandError("Имя зоны: буквы, цифры, _ и -, до 24 символов.");
                }
                if (name.equals("все") || name.equals("all")) {
                    throw new CommandError("Это имя зарезервировано.");
                }
                if (plot.zones.size() >= MAX_ZONES && !plot.zones.containsKey(name)) {
                    throw new CommandError("Не больше " + MAX_ZONES + " зон на участок.");
                }
                Material icon = Material.BRICKS;
                if (args.length > 3) {
                    icon = Material.matchMaterial(args[3]);
                    if (icon == null || !icon.isItem()) {
                        throw new CommandError("Неизвестный предмет для иконки: " + args[3]);
                    }
                }
                SelectionListener.Selection sel = plugin.selection().get(p);
                Box box = sel == null ? null : sel.box();
                if (box == null || !plot.world.equals(sel.world)) {
                    throw new CommandError("Сначала выделите область зоны внутри участка.");
                }
                if (!plot.box.contains(box)) {
                    throw new CommandError("Зона должна целиком находиться внутри участка " + plot.id + ".");
                }
                plot.zones.put(name, new Plot.Zone(name, box, icon.name()));
                plugin.plots().save();
                Text.ok(p, "Зона «" + name + "» (" + box.describe() + ") добавлена к участку " + plot.id + ".");
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new CommandError("Использование: /district zone remove <номер> <имя>");
                }
                Plot plot = requirePlot(args[1]);
                if (plot.zones.remove(args[2].toLowerCase(Locale.ROOT)) == null) {
                    throw new CommandError("Такой зоны нет.");
                }
                plugin.plots().save();
                Text.ok(p, "Зона удалена.");
            }
            case "list" -> zones(p, args.length > 1 ? args[1] : null);
            default -> throw new CommandError("Использование: /district zone <add|remove|list> ...");
        }
    }

    // ---------------------------------------------------------------- автодополнение

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        boolean admin = sender.hasPermission("buildmaster.admin");
        if (args.length == 1) {
            result.addAll(PLAYER_SUBS);
            if (admin) {
                result.addAll(ADMIN_SUBS);
            }
            return filter(result, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        int n = args.length;
        String current = args[n - 1];
        Plot here = sender instanceof Player p ? plugin.plots().at(p.getLocation()) : null;
        switch (sub) {
            case "info", "buy", "tp", "zones", "resnapshot", "setspawn", "delete", "evict" -> {
                if (n == 2) {
                    result.addAll(plotIds(sub.equals("buy") ? Plot.Status.FREE : null));
                }
            }
            case "setprice", "setrent" -> {
                if (n == 2) {
                    result.addAll(plotIds(null));
                } else if (n == 3) {
                    result.add("-");
                }
            }
            case "rent", "extend" -> {
                if (n == 2) {
                    result.addAll(List.of("1", "7", "14", "30"));
                } else if (n == 3) {
                    result.addAll(plotIds(null));
                }
            }
            case "autorenew" -> {
                if (n == 2) {
                    result.addAll(List.of("on", "off"));
                } else if (n == 3) {
                    result.addAll(plotIds(null));
                }
            }
            case "release" -> {
                result.addAll(plotIds(null));
                result.add("confirm");
            }
            case "trust", "untrust" -> {
                if (n == 2) {
                    Bukkit.getOnlinePlayers().forEach(pl -> result.add(pl.getName()));
                } else if (n == 3) {
                    result.addAll(plotIds(null));
                }
            }
            case "replace" -> {
                if (n == 2) {
                    result.add("все");
                    if (here != null) {
                        result.addAll(here.zones.keySet());
                    }
                } else if (n == 3) {
                    addMaterials(result, false);
                } else if (n == 4) {
                    addMaterials(result, true);
                }
            }
            case "create" -> {
                if (n == 3 || n == 4) {
                    result.add("-");
                }
            }
            case "zone" -> {
                if (n == 2) {
                    result.addAll(List.of("add", "remove", "list"));
                } else if (n == 3) {
                    result.addAll(plotIds(null));
                } else if (n == 4 && args[1].equalsIgnoreCase("remove")) {
                    Plot plot = plugin.plots().get(args[2]);
                    if (plot != null) {
                        result.addAll(plot.zones.keySet());
                    }
                } else if (n == 5 && args[1].equalsIgnoreCase("add")) {
                    addMaterials(result, false);
                }
            }
            default -> { }
        }
        return filter(result, current);
    }

    private List<String> plotIds(Plot.Status only) {
        List<String> ids = new ArrayList<>();
        for (Plot p : plugin.plots().all()) {
            if (only == null || p.status == only) {
                ids.add(p.id);
            }
        }
        return ids;
    }

    private void addMaterials(List<String> out, boolean targets) {
        if (targets) {
            for (Material m : plugin.rules().replaceTargets()) {
                out.add(m.getKey().getKey());
            }
        } else {
            for (Material m : Material.values()) {
                if (!m.isLegacy() && m.isBlock() && !m.isAir()) {
                    out.add(m.getKey().getKey());
                }
            }
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                result.add(o);
            }
        }
        return result;
    }
}
