package ru.industryreborn.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Request;
import ru.industryreborn.util.Text;

/** Модерация: всё, что вводят игроки (название, описание), публикуется только после одобрения администратора. */
public final class ModerationService {

    private static final int MAX_DECIDED = 300;

    private final IndustryRebornPlugin plugin;
    private final File file;
    private final List<Request> requests = new ArrayList<>();

    public ModerationService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "requests.yml");
    }

    // ------------------------------------------------------------ подача

    /** Подаёт заявку. Для администратора текст применяется сразу. Возвращает сообщение об ошибке или null. */
    public String submit(Player player, Plot plot, Request.Type type, String rawText) {
        String text;
        try {
            if (type == Request.Type.NAME) {
                text = TextRules.clean(rawText, plugin.getConfig().getInt("moderation.max-name-length", 32), "Название");
            } else {
                text = TextRules.clean(rawText, plugin.getConfig().getInt("moderation.max-description-length", 120), "Описание");
            }
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (player.hasPermission("industryreborn.admin")) {
            apply(plot, type, text);
            Text.ok(player, type.title() + " установлено.");
            return null;
        }
        // новая заявка вытесняет предыдущую ожидающую того же типа
        requests.removeIf(r -> r.status == Request.Status.PENDING && r.plotId.equals(plot.id)
                && r.type == type && r.requester.equals(player.getUniqueId()));
        requests.add(new Request(UUID.randomUUID(), type, plot.id, player.getUniqueId(), player.getName(), text,
                System.currentTimeMillis()));
        save();
        Text.ok(player, type.title() + " отправлено на проверку администратору.");
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("industryreborn.admin")) {
                Text.send(admin, "<yellow>Новая заявка на проверку: " + type.title().toLowerCase()
                        + " участка " + Text.esc(plot.id) + ". Откройте /district moderation");
            }
        }
        return null;
    }

    /** Заявка на одобрение привязанного участка игрока. Администратору одобрение не нужно. */
    public void submitPlot(Player player, Plot plot) {
        if (player.hasPermission("industryreborn.admin")) {
            plugin.playerPlots().approve(plot);
            return;
        }
        requests.add(new Request(UUID.randomUUID(), Request.Type.PLOT, plot.id, player.getUniqueId(), player.getName(),
                plot.category.title() + ", " + plot.box.describe(), System.currentTimeMillis()));
        save();
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("industryreborn.admin")) {
                Text.send(admin, "<yellow>Новая заявка: участок игрока " + Text.esc(plot.id) + ". Откройте /district moderation");
            }
        }
    }

    // ------------------------------------------------------------ зачарования станков

    /** Заявка на зачарование для стола зачарований мастерской. Администратору одобрение не нужно. */
    public void submitEnchant(Player player, Plot plot, String recipeId) {
        if (player.hasPermission("industryreborn.admin")) {
            plot.approvedEnchants.add(recipeId);
            plugin.plots().save();
            plugin.journal().add(plot.id, player.getUniqueId(), player.getName(), Journal.Action.ENCHANT, recipeId);
            return;
        }
        requests.removeIf(r -> r.status == Request.Status.PENDING && r.type == Request.Type.ENCHANT
                && r.plotId.equals(plot.id) && r.text.equals(recipeId));
        requests.add(new Request(UUID.randomUUID(), Request.Type.ENCHANT, plot.id, player.getUniqueId(), player.getName(),
                recipeId, System.currentTimeMillis()));
        save();
        for (Player a : Bukkit.getOnlinePlayers()) {
            if (a.hasPermission("industryreborn.admin")) {
                Text.send(a, "<yellow>Новая заявка: зачарование для мастерской " + Text.esc(plot.id) + ". Откройте /district moderation");
            }
        }
    }

    // ------------------------------------------------------------ класс номера

    /** Заявка управляющего на смену класса номера; решение принимает администратор (проверка номера на месте). */
    public void submitRoomClass(Player player, Plot room, ru.industryreborn.model.RoomClass target) {
        requests.add(new Request(UUID.randomUUID(), Request.Type.ROOM_CLASS, room.id, player.getUniqueId(), player.getName(),
                target.name(), System.currentTimeMillis()));
        save();
        for (Player a : Bukkit.getOnlinePlayers()) {
            if (a.hasPermission("industryreborn.admin")) {
                Text.send(a, "<yellow>Новая заявка: класс номера " + Text.esc(room.id) + " → " + Text.esc(target.title())
                        + ". Проверьте номер и откройте /district moderation");
            }
        }
    }

    // ------------------------------------------------------------ рецепты станков

    /** Заявка управляющего или работника на рецепт станка. */
    public void submitRecipe(Player player, Plot plot, ru.industryreborn.items.RecipeRequests.Spec spec) {
        requests.add(new Request(UUID.randomUUID(), Request.Type.RECIPE, plot.id, player.getUniqueId(), player.getName(),
                spec.serialize(), System.currentTimeMillis()));
        save();
        for (Player a : Bukkit.getOnlinePlayers()) {
            if (a.hasPermission("industryreborn.admin")) {
                Text.send(a, "<yellow>Новая заявка: рецепт станка мастерской " + Text.esc(plot.id) + ". Откройте /district moderation");
            }
        }
    }

    /** Отзывает ожидающую заявку (её автор). Возвращает ошибку или null. */
    public String cancel(Player player, Request r) {
        if (r.status != Request.Status.PENDING) {
            return "Заявка уже рассмотрена.";
        }
        if (!r.requester.equals(player.getUniqueId()) && !player.hasPermission("industryreborn.admin")) {
            return "Отозвать заявку может её автор.";
        }
        requests.remove(r);
        save();
        return null;
    }

    /** Ожидающая заявка на смену класса номера или null. */
    public Request pendingRoomClass(String roomId) {
        for (Request r : requests) {
            if (r.status == Request.Status.PENDING && r.type == Request.Type.ROOM_CLASS && r.plotId.equalsIgnoreCase(roomId)) {
                return r;
            }
        }
        return null;
    }

    /**
     * Куда телепортировать администратора при проверке заявки: для позиции голограммы - предложенная точка, для класса номера - коридор перед его дверью.
     * null - особого места нет (телепорт к самому участку).
     */
    public org.bukkit.Location teleportSpot(Request r) {
        if (r.type == Request.Type.HOLO) {
            return holoLocation(r);
        }
        if (r.type == Request.Type.ROOM_CLASS) {
            Plot room = plugin.plots().get(r.plotId);
            org.bukkit.World world = room == null ? null : Bukkit.getWorld(room.world);
            if (world == null || room.doors.isEmpty()) {
                return null;
            }
            for (int[] d : room.doors) {
                double[] spot = ru.industryreborn.service.DoorText.standSpot(room.box, d[0], d[1], d[2]);
                if (spot != null) {
                    return new org.bukkit.Location(world, spot[0], spot[1], spot[2], (float) spot[3], 0f);
                }
            }
            int[] d = room.doors.get(0);
            return new org.bukkit.Location(world, d[0] + 0.5, d[1], d[2] + 0.5);
        }
        return null;
    }

    /** Текст заявки для людей: для класса номера «Эконом → Люкс», иначе как есть. */
    public String describe(Request r) {
        if (r.type == Request.Type.RECIPE) {
            var spec = ru.industryreborn.items.RecipeRequests.Spec.parse(r.text);
            return spec == null ? "повреждённая заявка" : plugin.recipeRequests().describe(spec);
        }
        if (r.type == Request.Type.ROOM_CLASS) {
            Plot room = plugin.plots().get(r.plotId);
            ru.industryreborn.model.RoomClass target = ru.industryreborn.model.RoomClass.parse(r.text);
            String from = room == null ? "?" : room.roomClass.title();
            return from + " → " + (target == null ? r.text : target.title());
        }
        return r.text;
    }

    /** Ожидающие заявки на зачарования мастерской. */
    public List<Request> pendingEnchants(String plotId) {
        List<Request> list = new ArrayList<>();
        for (Request r : requests) {
            if (r.status == Request.Status.PENDING && r.type == Request.Type.ENCHANT && r.plotId.equals(plotId)) {
                list.add(r);
            }
        }
        return list;
    }

    // ------------------------------------------------------------ позиция голограммы

    /** Почему точка не годится для голограммы участка, либо null. */
    public String holoError(Plot plot, org.bukkit.Location at) {
        if (at.getWorld() == null || !at.getWorld().getName().equals(plot.world)) {
            return "Позиция должна быть в мире участка.";
        }
        int bx = at.getBlockX();
        int by = at.getBlockY();
        int bz = at.getBlockZ();
        if (!plot.box.contains(bx, by, bz)) {
            return "Голограмма должна находиться внутри вашего участка.";
        }
        if (plugin.plots().at(plot.world, bx, by, bz) != plot) {
            return "Эта точка принадлежит другому участку (например, номеру отеля).";
        }
        return null;
    }

    private static String holoText(Plot.HoloKind kind, org.bukkit.Location at) {
        return kind.name() + " " + String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", at.getX(), at.getY(), at.getZ());
    }

    /** Вид голограммы из текста заявки: «SERVICES x y z»; старые заявки без вида - общая голограмма. */
    public static Plot.HoloKind holoKind(String text) {
        String[] p = text.trim().split(" ");
        if (p.length == 4) {
            try {
                return Plot.HoloKind.valueOf(p[0]);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return Plot.HoloKind.MAIN;
    }

    private org.bukkit.Location holoParse(Plot plot, String text) {
        String[] p = text.trim().split(" ");
        if (p.length == 4) {
            p = java.util.Arrays.copyOfRange(p, 1, 4);
        }
        org.bukkit.World world = Bukkit.getWorld(plot.world);
        if (p.length != 3 || world == null) {
            return null;
        }
        try {
            return new org.bukkit.Location(world, Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Предлагает позицию голограммы (текущее положение игрока). Управляющему - на одобрение администратором,
     * арендодателю участка игрока и администратору - сразу. Возвращает сообщение об ошибке или null.
     */
    public String submitHolo(Player player, Plot plot, org.bukkit.Location at) {
        return submitHolo(player, plot, at, Plot.HoloKind.MAIN);
    }

    /**
     * Предлагает положение одной из голограмм участка. Панель управляющего двигается свободно (она видна только ему),
     * общая голограмма и голограмма услуг публичные, поэтому проходят одобрение администратором.
     */
    public String submitHolo(Player player, Plot plot, org.bukkit.Location at, Plot.HoloKind kind) {
        boolean admin = player.hasPermission("industryreborn.admin");
        if (!admin && !plot.isOwner(player.getUniqueId()) && !plot.isLandlord(player.getUniqueId())) {
            return "Позицию голограммы предлагает управляющий участка.";
        }
        if (plot.isFree() && !admin && !plot.isLandlord(player.getUniqueId())) {
            return "Участок свободен: голограмму задаёт администрация.";
        }
        if (plugin.service().isLocked(plot)) {
            return "Участок сейчас обрабатывается.";
        }
        String error = holoError(plot, at);
        if (error != null) {
            return error;
        }
        if (admin || kind == Plot.HoloKind.MANAGER) {
            setHolo(plot, kind, at);
            Text.ok(player, kind.title() + " участка " + plot.id + " перенесена.");
            return null;
        }
        requests.removeIf(r -> r.status == Request.Status.PENDING && r.plotId.equals(plot.id)
                && r.type == Request.Type.HOLO && r.requester.equals(player.getUniqueId()) && kind == holoKind(r.text));
        requests.add(new Request(UUID.randomUUID(), Request.Type.HOLO, plot.id, player.getUniqueId(), player.getName(),
                holoText(kind, at), System.currentTimeMillis()));
        save();
        Text.ok(player, "Позиция: " + kind.title().toLowerCase() + " отправлена на проверку администратору. Пока действует прежняя.");
        for (Player a : Bukkit.getOnlinePlayers()) {
            if (a.hasPermission("industryreborn.admin")) {
                Text.send(a, "<yellow>Новая заявка: позиция голограммы участка " + Text.esc(plot.id)
                        + ". Откройте /district moderation");
            }
        }
        return null;
    }

    private void setHolo(Plot plot, Plot.HoloKind kind, org.bukkit.Location at) {
        switch (kind) {
            case MAIN -> {
                plot.hasHolo = true;
                plot.holoX = at.getX();
                plot.holoY = at.getY();
                plot.holoZ = at.getZ();
            }
            case SERVICES -> plot.servicesPos = new double[] {at.getX(), at.getY(), at.getZ()};
            case MANAGER -> plot.managerPos = new double[] {at.getX(), at.getY(), at.getZ()};
            case LOG -> plot.logPos = new double[] {at.getX(), at.getY(), at.getZ()};
        }
        plugin.plots().save();
        plugin.holograms().update(plot);
    }

    /** Координаты позиции из заявки (для меню модерации) или null. */
    public org.bukkit.Location holoLocation(Request r) {
        Plot plot = plugin.plots().get(r.plotId);
        return plot == null ? null : holoParse(plot, r.text);
    }

    private void apply(Plot plot, Request.Type type, String text) {
        if (type == Request.Type.NAME) {
            plot.name = text;
        } else {
            plot.description = text;
        }
        plugin.plots().save();
        plugin.holograms().update(plot);
    }

    // ------------------------------------------------------------ решения

    /** Ожидающая заявка по началу идентификатора (первые символы UUID). */
    public Request findPending(String prefix) {
        Request found = null;
        for (Request r : pending()) {
            if (r.id.toString().startsWith(prefix.toLowerCase(java.util.Locale.ROOT))) {
                if (found != null) {
                    return null;     // неоднозначно
                }
                found = r;
            }
        }
        return found;
    }

    public static String shortId(Request r) {
        return r.id.toString().substring(0, 8);
    }

    public List<Request> pending() {
        List<Request> list = new ArrayList<>();
        for (Request r : requests) {
            if (r.status == Request.Status.PENDING) {
                list.add(r);
            }
        }
        return list;
    }

    public List<Request> forPlayer(UUID player) {
        List<Request> list = new ArrayList<>();
        for (int i = requests.size() - 1; i >= 0; i--) {
            if (requests.get(i).requester.equals(player)) {
                list.add(requests.get(i));
            }
        }
        return list;
    }

    public void approve(Request r, String adminName) {
        approve(r, adminName, null);
    }

    /** Одобряет заявку; blueprintPrice - цена чертежа для заявки на рецепт (null - по умолчанию из настроек). */
    public void approve(Request r, String adminName, Double blueprintPrice) {
        if (r.status != Request.Status.PENDING) {
            return;
        }
        Plot plot = plugin.plots().get(r.plotId);
        r.status = Request.Status.APPROVED;
        r.decidedBy = adminName;
        r.decidedAt = System.currentTimeMillis();
        r.reason = "";
        if (r.type == Request.Type.PLOT) {
            if (plot != null) {
                plugin.playerPlots().approve(plot);
            }
        } else if (r.type == Request.Type.ENCHANT) {
            RecipeBook.Recipe recipe = plugin.recipes().get(r.text);
            String stale = plot == null || plot.category != ru.industryreborn.model.Category.WORKSHOP || plot.isFree()
                    ? "мастерская больше недоступна"
                    : !plot.isOwner(r.requester) ? "мастерская сменила управляющего"
                    : recipe == null || !recipe.enchanting() ? "зачарование недействительно" : null;
            if (stale != null) {
                r.status = Request.Status.REJECTED;
                r.reason = "Заявка устарела: " + stale;
                plugin.journal().add(r.plotId, r.requester, adminName, Journal.Action.ENCHANT, "«" + r.text + "»: " + r.reason);
                notifyRequester(r, "<red>" + r.type.title() + " мастерской " + Text.esc(r.plotId) + " не применено: " + Text.esc(stale));
                trim();
                save();
                return;
            }
            plot.approvedEnchants.add(r.text);
            plugin.plots().save();
        } else if (r.type == Request.Type.RECIPE) {
            String stale = plugin.recipeRequests().approve(r, adminName,
                    blueprintPrice != null ? blueprintPrice : plugin.recipeRequests().defaultBlueprintPrice());
            if (stale != null) {
                r.status = Request.Status.REJECTED;
                r.reason = "Заявка устарела или не прошла проверку: " + stale;
                plugin.journal().add(r.plotId, r.requester, adminName, Journal.Action.FACTORY, "Рецепт: " + r.reason);
                notifyRequester(r, "<red>Рецепт мастерской " + Text.esc(r.plotId) + " не создан: " + Text.esc(stale));
                trim();
                save();
                return;
            }
            notifyRequester(r, "<green>Рецепт мастерской " + Text.esc(r.plotId) + " одобрен: «" + Text.esc(describe(r))
                    + "». Он уже изучен вашей мастерской, остальным нужен чертёж.");
            trim();
            save();
            return;
        } else if (r.type == Request.Type.ROOM_CLASS) {
            ru.industryreborn.model.RoomClass target = ru.industryreborn.model.RoomClass.parse(r.text);
            Plot hotel = plot == null ? null : plugin.hotels().hotelOf(plot);
            String stale = plot == null || plot.category != ru.industryreborn.model.Category.ROOM ? "номера больше нет"
                    : hotel == null || !hotel.isOwner(r.requester) ? "отель сменил управляющего"
                    : target == null ? "неизвестный класс"
                    : plot.roomClass == target ? "номер уже этого класса" : null;
            if (stale != null) {
                r.status = Request.Status.REJECTED;
                r.reason = "Заявка устарела: " + stale;
                plugin.journal().add(r.plotId, r.requester, adminName, Journal.Action.HOTEL, "Класс номера: " + r.reason);
                notifyRequester(r, "<red>Класс номера " + Text.esc(r.plotId) + " не изменён: " + Text.esc(stale));
                trim();
                save();
                return;
            }
            plugin.hotels().applyClass(plot, target, adminName, "по заявке " + r.requesterName, r.requester);
            notifyRequester(r, "<green>Класс номера " + Text.esc(r.plotId) + " изменён: " + target.badge());
            trim();
            save();
            return;
        } else if (r.type == Request.Type.HOLO) {
            org.bukkit.Location at = plot == null ? null : holoParse(plot, r.text);
            String stale = plot == null || at == null || holoKind(r.text) == null ? "участок или позиция больше недействительны"
                    : !plot.isOwner(r.requester) && !plot.isLandlord(r.requester) ? "участок сменил управляющего"
                    : holoError(plot, at);
            if (stale != null) {
                r.status = Request.Status.REJECTED;
                r.reason = "Заявка устарела: " + stale;
                plugin.journal().add(r.plotId, r.requester, adminName, Journal.Action.HOLO, "«" + r.text + "»: " + r.reason);
                notifyRequester(r, "<red>" + r.type.title() + " участка " + Text.esc(r.plotId) + " не применена: "
                        + Text.esc(stale));
                trim();
                save();
                return;
            }
            setHolo(plot, holoKind(r.text), at);
        } else if (plot != null) {
            apply(plot, r.type, r.text);
        }
        plugin.journal().add(r.plotId, r.requester, adminName, journalApproved(r.type), r.text);
        notifyRequester(r, "<green>" + r.type.title() + " участка " + Text.esc(r.plotId) + " одобрено и опубликовано.");
        trim();
        save();
    }

    public void reject(Request r, String adminName, String reason) {
        if (r.status != Request.Status.PENDING) {
            return;
        }
        r.status = Request.Status.REJECTED;
        r.decidedBy = adminName;
        r.decidedAt = System.currentTimeMillis();
        r.reason = reason;
        Plot rejected = plugin.plots().get(r.plotId);
        if (r.type == Request.Type.PLOT && rejected != null && rejected.isPlayerPlot()) {
            plugin.playerPlots().reject(rejected);
        }
        plugin.journal().add(r.plotId, r.requester, adminName, journalRejected(r.type), "«" + r.text + "»: " + reason);
        notifyRequester(r, "<red>" + r.type.title() + " участка " + Text.esc(r.plotId) + " отклонено. Причина: "
                + Text.esc(reason));
        trim();
        save();
    }

    private static Journal.Action journalApproved(Request.Type t) {
        return t == Request.Type.NAME ? Journal.Action.NAME_APPROVED
                : t == Request.Type.DESCRIPTION ? Journal.Action.DESC_APPROVED
                : t == Request.Type.HOLO ? Journal.Action.HOLO : t == Request.Type.ENCHANT ? Journal.Action.ENCHANT
                : t == Request.Type.ROOM_CLASS ? Journal.Action.HOTEL : t == Request.Type.RECIPE ? Journal.Action.FACTORY : Journal.Action.PLAYER_PLOT;
    }

    private static Journal.Action journalRejected(Request.Type t) {
        return t == Request.Type.NAME ? Journal.Action.NAME_REJECTED
                : t == Request.Type.DESCRIPTION ? Journal.Action.DESC_REJECTED
                : t == Request.Type.HOLO ? Journal.Action.HOLO : t == Request.Type.ENCHANT ? Journal.Action.ENCHANT
                : t == Request.Type.ROOM_CLASS ? Journal.Action.HOTEL : t == Request.Type.RECIPE ? Journal.Action.FACTORY : Journal.Action.PLAYER_PLOT;
    }

    private void notifyRequester(Request r, String message) {
        Player online = Bukkit.getPlayer(r.requester);
        if (online != null) {
            Text.send(online, message);
            r.notified = true;
        }
    }

    /** Сообщает игроку о решениях, принятых, пока он был оффлайн. */
    public void deliverMissed(Player player) {
        boolean changed = false;
        for (Request r : requests) {
            if (r.requester.equals(player.getUniqueId()) && r.status != Request.Status.PENDING && !r.notified) {
                r.notified = true;
                changed = true;
                if (r.status == Request.Status.APPROVED) {
                    Text.send(player, "<green>" + r.type.title() + " участка " + Text.esc(r.plotId) + " одобрено.");
                } else {
                    Text.send(player, "<red>" + r.type.title() + " участка " + Text.esc(r.plotId)
                            + " отклонено. Причина: " + Text.esc(String.valueOf(r.reason)) + " (журнал: /district journal)");
                }
            }
        }
        if (changed) {
            save();
        }
    }

    private void trim() {
        List<Request> decided = new ArrayList<>();
        for (Request r : requests) {
            if (r.status != Request.Status.PENDING) {
                decided.add(r);
            }
        }
        while (decided.size() > MAX_DECIDED) {
            requests.remove(decided.remove(0));
        }
    }

    // ------------------------------------------------------------ хранение

    public void load() {
        requests.clear();
        if (!file.exists()) {
            return;
        }
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("requests");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            Request r = new Request(UUID.fromString(key), Request.Type.valueOf(s.getString("type")),
                    s.getString("plot"), UUID.fromString(s.getString("requester")), s.getString("requester-name"),
                    s.getString("text"), s.getLong("created"));
            r.status = Request.Status.valueOf(s.getString("status", "PENDING"));
            r.reason = s.getString("reason");
            r.decidedBy = s.getString("decided-by");
            r.decidedAt = s.getLong("decided-at");
            r.notified = s.getBoolean("notified");
            requests.add(r);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Request r : requests) {
            String k = "requests." + r.id + ".";
            yml.set(k + "type", r.type.name());
            yml.set(k + "plot", r.plotId);
            yml.set(k + "requester", r.requester.toString());
            yml.set(k + "requester-name", r.requesterName);
            yml.set(k + "text", r.text);
            yml.set(k + "created", r.createdAt);
            yml.set(k + "status", r.status.name());
            yml.set(k + "reason", r.reason);
            yml.set(k + "decided-by", r.decidedBy);
            yml.set(k + "decided-at", r.decidedAt);
            yml.set(k + "notified", r.notified);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить requests.yml", e);
        }
    }
}
