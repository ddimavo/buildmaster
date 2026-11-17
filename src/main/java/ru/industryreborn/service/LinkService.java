package ru.industryreborn.service;

import java.util.ArrayList;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/**
 * Связи между бизнесами: мастерскую можно привязать к магазину или складу, и готовая продукция автоматически уходит туда.
 * Если у обоих предприятий один управляющий, связь создаётся сразу; иначе владелец магазина или склада подтверждает её.
 */
public final class LinkService {

    private final IndustryRebornPlugin plugin;

    public LinkService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    /** Действующая цель связи мастерской (магазин или склад) либо null. */
    public Plot target(Plot source) {
        if (source.linkedTo == null) {
            return null;
        }
        Plot t = plugin.plots().get(source.linkedTo);
        if (t == null || t.isFree() || t.owner == null || t.restorePending
                || (t.category != Category.SHOP && t.category != Category.WAREHOUSE)) {
            return null;
        }
        return t;
    }

    public java.util.List<Plot> sourcesOf(Plot target) {
        java.util.List<Plot> list = new ArrayList<>();
        for (Plot p : plugin.plots().all()) {
            if (target.id.equalsIgnoreCase(p.linkedTo)) {
                list.add(p);
            }
        }
        return list;
    }

    /** Запрос на связь мастерской с магазином или складом. */
    public String request(Player manager, Plot source, Plot target) {
        if (source.category != Category.WORKSHOP) {
            return "Привязывать можно только мастерскую.";
        }
        if (target.category != Category.SHOP && target.category != Category.WAREHOUSE) {
            return "Мастерскую можно привязать к магазину или складу.";
        }
        if (source.id.equalsIgnoreCase(target.id)) {
            return "Нельзя привязать участок к самому себе.";
        }
        if (source.isFree() || target.isFree() || source.owner == null || target.owner == null) {
            return "Оба предприятия должны иметь управляющего.";
        }
        if (!source.isOwner(manager.getUniqueId()) && !manager.hasPermission("industryreborn.admin")) {
            return "Связь создаёт управляющий мастерской.";
        }
        if (plugin.service().isLocked(source) || plugin.service().isLocked(target)) {
            return "Участок сейчас обрабатывается.";
        }
        if (target.id.equalsIgnoreCase(source.linkedTo)) {
            return "Эта связь уже есть.";
        }
        if (source.owner.equals(target.owner) || manager.hasPermission("industryreborn.admin")) {
            source.linkedTo = target.id;
            plugin.plots().save();
            plugin.journal().add(source.id, manager.getUniqueId(), manager.getName(), Journal.Action.FACTORY,
                    "Связь с участком " + target.id);
            Text.ok(manager, "Мастерская " + source.id + " привязана к " + target.id + ": продукция будет уходить туда автоматически.");
            return null;
        }
        if (!target.linkRequests.add(source.id)) {
            return "Запрос уже отправлен: ждите ответа управляющего участка " + target.id + ".";
        }
        plugin.plots().save();
        plugin.service().warn(target.owner, target.id, "<aqua>" + Text.esc(manager.getName()) + " просит связать мастерскую <white>"
                + Text.esc(source.id) + "</white> с вашим участком <white>" + Text.esc(target.id)
                + "</white>: её продукция будет поступать к вам. <click:run_command:/district link accept " + Text.esc(source.id)
                + " " + Text.esc(target.id) + "><green>[Принять]</green></click> <click:run_command:/district link decline "
                + Text.esc(source.id) + " " + Text.esc(target.id) + "><red>[Отклонить]</red></click>");
        Text.ok(manager, "Запрос отправлен управляющему участка " + target.id + ".");
        return null;
    }

    public String accept(Player manager, Plot target, String sourceId) {
        if (!target.isOwner(manager.getUniqueId()) && !manager.hasPermission("industryreborn.admin")) {
            return "Запросы подтверждает управляющий магазина или склада.";
        }
        Plot source = plugin.plots().get(sourceId);
        if (source == null || !target.linkRequests.remove(source.id)) {
            return "Запроса от «" + sourceId + "» нет.";
        }
        if (source.category != Category.WORKSHOP || source.isFree()) {
            plugin.plots().save();
            return "Эта мастерская больше недоступна.";
        }
        source.linkedTo = target.id;
        plugin.plots().save();
        plugin.journal().add(source.id, manager.getUniqueId(), manager.getName(), Journal.Action.FACTORY, "Связь с участком " + target.id);
        if (source.owner != null) {
            plugin.service().warn(source.owner, source.id, "<green>Связь мастерской <white>" + Text.esc(source.id)
                    + "</white> с участком <white>" + Text.esc(target.id) + "</white> подтверждена.");
        }
        return null;
    }

    public String decline(Player manager, Plot target, String sourceId) {
        if (!target.isOwner(manager.getUniqueId()) && !manager.hasPermission("industryreborn.admin")) {
            return "Запросы отклоняет управляющий магазина или склада.";
        }
        Plot source = plugin.plots().get(sourceId);
        String id = source == null ? sourceId : source.id;
        if (!target.linkRequests.remove(id)) {
            return "Запроса от «" + sourceId + "» нет.";
        }
        plugin.plots().save();
        if (source != null && source.owner != null) {
            plugin.service().warn(source.owner, source.id, "<red>Связь с участком <white>" + Text.esc(target.id) + "</white> отклонена.");
        }
        return null;
    }

    /** Разрывает связь: мастерская (её управляющий) или цель (её управляющий) либо администратор. */
    public String unlink(Player who, Plot source) {
        Plot target = source.linkedTo == null ? null : plugin.plots().get(source.linkedTo);
        boolean allowed = who.hasPermission("industryreborn.admin") || source.isOwner(who.getUniqueId())
                || (target != null && target.isOwner(who.getUniqueId()));
        if (!allowed) {
            return "Связь разрывает управляющий мастерской или цели.";
        }
        if (source.linkedTo == null) {
            return "У мастерской нет связи.";
        }
        source.linkedTo = null;
        plugin.plots().save();
        Text.ok(who, "Связь разорвана: продукция снова выкладывается в сундук продукции.");
        return null;
    }

    /** Участок освобождается или удаляется: связи с ним и от него снимаются. */
    public void clear(Plot plot) {
        plot.linkedTo = null;
        plot.linkRequests.clear();
        for (Plot p : plugin.plots().all()) {
            if (plot.id.equalsIgnoreCase(p.linkedTo)) {
                p.linkedTo = null;
            }
            p.linkRequests.remove(plot.id);
        }
    }
}
