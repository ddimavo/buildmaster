package ru.industryreborn.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.listener.SelectionListener;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/** Команды отелей и номеров: /district room ... */
final class RoomCommands {

    private final IndustryRebornPlugin plugin;

    RoomCommands(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    private static void requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("industryreborn.admin")) {
            throw new CommandError("Недостаточно прав.");
        }
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        throw new CommandError("Эту команду можно выполнить только игроку.");
    }

    private Plot plot(String id) {
        Plot p = plugin.plots().get(id);
        if (p == null) {
            throw new CommandError("Участок «" + id + "» не найден.");
        }
        return p;
    }

    private Plot hotel(String id) {
        Plot p = plot(id);
        if (p.category != Category.HOTEL) {
            throw new CommandError("«" + id + "» не отель.");
        }
        return p;
    }

    private Plot room(String id) {
        Plot p = plot(id);
        if (p.category != Category.ROOM) {
            throw new CommandError("«" + id + "» не номер отеля.");
        }
        return p;
    }

    private static double number(String s, String what) {
        try {
            double v = Double.parseDouble(s.replace(',', '.'));
            if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new CommandError(what + " должна быть числом не меньше нуля.");
        }
    }

    private static int integer(String s, String what) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new CommandError(what + " должно быть целым числом.");
        }
    }

    private Box selection(Player p, Plot hotel) {
        SelectionListener.Selection sel = plugin.selection().get(p);
        Box box = sel == null ? null : sel.box();
        if (box == null || !hotel.world.equals(sel.world)) {
            throw new CommandError("Сначала выделите интерьер номера внутри отеля: /district wand");
        }
        return box;
    }

    void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "list" -> list(sender, rest.length > 0 ? rest[0] : null);
            case "rate" -> {
                if (rest.length < 2) {
                    throw new CommandError("Использование: /district room rate <номер> <цена за сутки>");
                }
                String error = plugin.hotels().setRate(player(sender), room(rest[0]), number(rest[1], "Цена"));
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Цена номера " + rest[0] + ": " + plugin.economy().format(number(rest[1], "Цена")) + " за сутки.");
            }
            case "create" -> {
                requireAdmin(sender);
                if (rest.length < 3) {
                    throw new CommandError("Использование: /district room create <отель> <номер комнаты> <цена за сутки>");
                }
                Player p = player(sender);
                Plot hotel = hotel(rest[0]);
                Box box = selection(p, hotel);
                String error = plugin.hotels().validateRoomBox(hotel, box, rest[1]);
                if (error != null) {
                    throw new CommandError(error);
                }
                plugin.service().createRoom(p, hotel, rest[1], box, number(rest[2], "Цена"), List.of(), ok -> { });
            }
            case "template" -> {
                requireAdmin(sender);
                if (rest.length < 6) {
                    throw new CommandError("Использование: /district room template <отель> <префикс> <x|z> <шаг> <количество> "
                            + "<цена за сутки> [dx dy dz - смещение двери от угла номера]");
                }
                Player p = player(sender);
                Plot hotel = hotel(rest[0]);
                boolean axisX = switch (rest[2].toLowerCase(Locale.ROOT)) {
                    case "x" -> true;
                    case "z" -> false;
                    default -> throw new CommandError("Ось: x или z.");
                };
                int[] door = null;
                if (rest.length >= 9) {
                    door = new int[] {integer(rest[6], "dx"), integer(rest[7], "dy"), integer(rest[8], "dz")};
                }
                String error = plugin.hotels().template(p, hotel, rest[1], selection(p, hotel), axisX,
                        integer(rest[3], "Шаг"), integer(rest[4], "Количество"), number(rest[5], "Цена"), door,
                        created -> Text.ok(p, "Шаблон выполнен: создано номеров - " + created + "."));
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.send(p, "<yellow>Создаю номера по шаблону...");
            }
            case "door" -> {
                requireAdmin(sender);
                if (rest.length >= 2 && rest[0].equalsIgnoreCase("clear")) {
                    plugin.hotels().clearDoors(room(rest[1]));
                    Text.ok(sender, "Двери номера удалены.");
                    return;
                }
                if (rest.length < 1) {
                    throw new CommandError("Использование: /district room door <номер> (смотрите на блок двери) | door clear <номер>");
                }
                Block target = player(sender).getTargetBlockExact(6);
                if (target == null) {
                    throw new CommandError("Наведите взгляд на блок двери (не дальше 6 блоков).");
                }
                String error = plugin.hotels().addDoor(room(rest[0]), target);
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Дверь добавлена: " + target.getX() + ", " + target.getY() + ", " + target.getZ() + ".");
            }
            case "auto" -> {
                requireAdmin(sender);
                if (rest.length < 2) {
                    throw new CommandError("Использование: /district room auto <номер комнаты> <цена за сутки> "
                            + "(смотрите на дверь номера, стоя в коридоре)");
                }
                String error = plugin.roomAuto().analyze(player(sender), rest[0], number(rest[1], "Цена"));
                if (error != null) {
                    throw new CommandError(error);
                }
            }
            case "confirm" -> {
                requireAdmin(sender);
                String error = plugin.roomAuto().confirm(player(sender));
                if (error != null) {
                    throw new CommandError(error);
                }
            }
            case "cancel" -> {
                requireAdmin(sender);
                if (plugin.roomAuto().cancel(player(sender))) {
                    Text.ok(sender, "Создание номера отменено.");
                } else {
                    throw new CommandError("Нет номера, ожидающего подтверждения.");
                }
            }
            case "delete" -> {
                requireAdmin(sender);
                if (rest.length < 1) {
                    throw new CommandError("Использование: /district room delete <номер>");
                }
                String error = plugin.hotels().deleteRoom(room(rest[0]));
                if (error != null) {
                    throw new CommandError(error);
                }
                Text.ok(sender, "Номер удалён.");
            }
            case "maxrate" -> {
                requireAdmin(sender);
                if (rest.length < 2) {
                    throw new CommandError("Использование: /district room maxrate <отель> <максимальная цена за сутки>");
                }
                Plot hotel = hotel(rest[0]);
                hotel.maxRoomRate = number(rest[1], "Цена");
                plugin.plots().save();
                Text.ok(sender, "Управляющий отеля " + hotel.id + " может назначать цену номеров до "
                        + plugin.economy().format(hotel.maxRoomRate) + " за сутки"
                        + (hotel.maxRoomRate <= 0 ? " (то есть не может)." : "."));
            }
            default -> throw new CommandError("Использование: /district room [list|rate|create|auto|confirm|cancel|template|door|delete|maxrate]");
        }
    }

    private void list(CommandSender sender, String hotelId) {
        Plot hotel;
        if (hotelId != null) {
            Plot p = plot(hotelId);
            hotel = p.category == Category.ROOM ? plugin.hotels().hotelOf(p) : p;
        } else if (sender instanceof Player pl) {
            Plot here = plugin.plots().at(pl.getLocation());
            hotel = here == null ? null : here.category == Category.ROOM ? plugin.hotels().hotelOf(here) : here;
        } else {
            hotel = null;
        }
        if (hotel == null || hotel.category != Category.HOTEL) {
            throw new CommandError("Укажите отель: /district room list <отель>");
        }
        List<Plot> rooms = plugin.hotels().rooms(hotel);
        Text.send(sender, "<yellow>Номера отеля <white>" + Text.esc(hotel.displayName()) + "</white> (свободно "
                + plugin.hotels().freeRooms(hotel) + " из " + rooms.size() + "):");
        for (Plot r : rooms) {
            String state = r.isFree() ? "<green>свободен" : "<red>занят до " + Text.date(r.rentedUntil);
            sender.sendMessage(Text.mm("<gray> <white>" + Text.esc(r.id) + "</white> - " + state + "<gray>, "
                    + plugin.economy().format(r.rentPerDay) + " за сутки, " + r.box.describe()));
        }
    }

    List<String> complete(CommandSender sender, String[] args) {
        int n = args.length; // args[0] = "room"
        if (n == 2) {
            return List.of("list", "rate", "create", "auto", "confirm", "cancel", "template", "door", "delete", "maxrate");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        List<String> hotels = plugin.plots().all().stream().filter(p -> p.category == Category.HOTEL).map(p -> p.id).toList();
        List<String> rooms = plugin.plots().all().stream().filter(p -> p.category == Category.ROOM).map(p -> p.id).toList();
        if (n == 3 && List.of("create", "template", "maxrate", "list").contains(sub)) {
            return hotels;
        }
        if (n == 3 && List.of("rate", "delete").contains(sub)) {
            return rooms;
        }
        if (n == 3 && sub.equals("door")) {
            return java.util.stream.Stream.concat(java.util.stream.Stream.of("clear"), rooms.stream()).toList();
        }
        if (n == 4 && sub.equals("door") && args[2].equalsIgnoreCase("clear")) {
            return rooms;
        }
        if (n == 4 && sub.equals("template")) {
            return List.of("room");
        }
        if (n == 5 && sub.equals("template")) {
            return List.of("x", "z");
        }
        return List.of();
    }
}
