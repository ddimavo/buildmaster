package ru.buildmaster.service;

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
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Request;
import ru.buildmaster.util.Text;

/** Модерация: всё, что вводят игроки (название, описание), публикуется только после одобрения администратора. */
public final class ModerationService {

    private static final int MAX_DECIDED = 300;

    private final BuildMasterPlugin plugin;
    private final File file;
    private final List<Request> requests = new ArrayList<>();

    public ModerationService(BuildMasterPlugin plugin) {
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
        if (player.hasPermission("buildmaster.admin")) {
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
            if (admin.hasPermission("buildmaster.admin")) {
                Text.send(admin, "<yellow>Новая заявка на проверку: " + type.title().toLowerCase()
                        + " участка " + Text.esc(plot.id) + ". Откройте /district moderation");
            }
        }
        return null;
    }

    /** Заявка на одобрение привязанного участка игрока. Администратору одобрение не нужно. */
    public void submitPlot(Player player, Plot plot) {
        if (player.hasPermission("buildmaster.admin")) {
            plugin.playerPlots().approve(plot);
            return;
        }
        requests.add(new Request(UUID.randomUUID(), Request.Type.PLOT, plot.id, player.getUniqueId(), player.getName(),
                plot.category.title() + ", " + plot.box.describe(), System.currentTimeMillis()));
        save();
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("buildmaster.admin")) {
                Text.send(admin, "<yellow>Новая заявка: участок игрока " + Text.esc(plot.id) + ". Откройте /district moderation");
            }
        }
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
        } else if (plot != null && r.type != Request.Type.HOLO) {
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
                : t == Request.Type.DESCRIPTION ? Journal.Action.DESC_APPROVED : Journal.Action.PLAYER_PLOT;
    }

    private static Journal.Action journalRejected(Request.Type t) {
        return t == Request.Type.NAME ? Journal.Action.NAME_REJECTED
                : t == Request.Type.DESCRIPTION ? Journal.Action.DESC_REJECTED : Journal.Action.PLAYER_PLOT;
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
