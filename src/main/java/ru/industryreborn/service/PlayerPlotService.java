package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/**
 * Участки игроков: регион WorldGuard, который игрок хочет безопасно продать или сдать в аренду.
 * Привязка требует одобрения администратора и согласия совладельцев; на время аренды регион переходит арендатору
 * (с возможным сохранением доступа арендодателя), а после аренды возвращается.
 */
public final class PlayerPlotService {

    private final IndustryRebornPlugin plugin;

    public PlayerPlotService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ настройки

    public boolean enabled() {
        return plugin.getConfig().getBoolean("player-plots.enabled", true);
    }

    /** Типы, которые игрок может выбрать при привязке региона. */
    public List<Category> allowedTypes() {
        List<Category> list = new ArrayList<>();
        for (String name : plugin.getConfig().getStringList("player-plots.allowed-types")) {
            Category c = Category.parse(name);
            if (c != null && !c.isAdminOnly() && !c.isRentOnly() && c != Category.WORKSHOP && c != Category.SHOP) {
                list.add(c);
            }
        }
        return list;
    }

    /** Предел цены аренды за сутки для участка: растёт с размером региона. */
    public double rentCap(Plot plot) {
        var cfg = plugin.getConfig();
        return RentCap.compute(plot.box.volume(), cfg.getDouble("player-plots.max-rent-per-1000-blocks", 20),
                cfg.getDouble("player-plots.min-rent-cap", 10), cfg.getDouble("player-plots.max-rent-cap", 500));
    }

    public long sharedAccessMinVolume() {
        return plugin.getConfig().getLong("player-plots.shared-access-min-volume", 2000);
    }

    private String name(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() == null ? id.toString().substring(0, 8) : op.getName();
    }

    // ------------------------------------------------------------ привязка региона

    /** Привязывает регион WorldGuard игрока. Участок появляется в списках после одобрения администратором. */
    public String bind(Player p, String regionId, Category type) {
        if (!enabled()) {
            return "Участки игроков отключены на этом сервере.";
        }
        if (!plugin.regions().available()) {
            return "WorldGuard не найден: привязка регионов недоступна.";
        }
        if (type == null || !allowedTypes().contains(type)) {
            return "Этот тип недоступен игрокам. Можно: " + typeNames()
                    + ". Магазины и мастерские создаёт только администрация.";
        }
        int max = plugin.getConfig().getInt("player-plots.max-per-player", 5);
        long mine = plugin.plots().all().stream().filter(x -> x.isLandlord(p.getUniqueId())).count();
        if (max > 0 && mine >= max && !p.hasPermission("industryreborn.bypass")) {
            return "Вы уже привязали максимальное число участков (" + max + ").";
        }
        World world = p.getWorld();
        RegionAccess.RegionInfo info = plugin.regions().find(world, regionId);
        if (info == null) {
            return "Регион «" + regionId + "» не найден в этом мире. Встаньте в мире региона и укажите его название.";
        }
        if (!info.cuboid()) {
            return "Поддерживаются только прямоугольные (cuboid) регионы.";
        }
        if (!info.owners().contains(p.getUniqueId())) {
            return "Привязать можно только регион, которым вы владеете.";
        }
        String id = info.id();
        if (!id.matches("^[A-Za-z0-9_-]{1,32}$")) {
            return "Название региона должно состоять из латиницы, цифр, _ и - (до 32 символов).";
        }
        if (plugin.plots().get(id) != null) {
            return "Участок с номером «" + id + "» уже есть. Переименуйте регион.";
        }
        long volume = info.box().volume();
        long minV = plugin.getConfig().getLong("player-plots.min-volume", 100);
        long maxV = plugin.getConfig().getLong("player-plots.max-volume", 500_000);
        if (volume < minV || volume > maxV) {
            return "Размер региона должен быть от " + minV + " до " + maxV + " блоков (сейчас " + volume + ").";
        }
        Plot overlap = plugin.plots().overlapping(world.getName(), info.box());
        if (overlap != null) {
            return "Регион пересекается с участком " + overlap.id + ".";
        }

        Plot plot = new Plot(id, world.getName(), info.box());
        plot.kind = Plot.Kind.PLAYER;
        plot.category = type;
        plot.regionId = id;
        plot.landlord = p.getUniqueId();
        plot.landlordName = p.getName();
        plot.createdBy = p.getUniqueId();
        plot.approval = Plot.Approval.PENDING;
        plot.price = -1;
        plot.rentPerDay = -1;
        for (UUID other : info.owners()) {
            if (!other.equals(p.getUniqueId())) {
                plot.requiredConsents.add(other);
            }
        }
        plot.regionMembers.addAll(info.members());
        plugin.plots().add(plot);
        plugin.plots().save();
        plugin.stations().register(plot);
        plugin.journal().add(id, p.getUniqueId(), p.getName(), Journal.Action.PLAYER_PLOT,
                "Привязан регион, тип: " + type.title() + ", " + info.box().describe());
        plugin.moderation().submitPlot(p, plot);
        Text.ok(p, "Регион <white>" + Text.esc(id) + "</white> привязан и отправлен на одобрение администратору. "
                + "После одобрения выставьте цену: /district region sale|rent " + id + " ...");
        if (!plot.requiredConsents.isEmpty()) {
            List<String> names = new ArrayList<>();
            plot.requiredConsents.forEach(u -> names.add(name(u)));
            Text.send(p, "<yellow>У региона есть другие владельцы: <white>" + Text.esc(String.join(", ", names))
                    + "</white>. Чтобы продавать или сдавать регион, уберите их из владельцев либо получите согласие: "
                    + "каждый должен выполнить <white>/district region consent " + Text.esc(id) + "</white>.");
            for (UUID other : plot.requiredConsents) {
                plugin.service().warn(other, id, "<yellow>" + Text.esc(p.getName()) + " хочет продавать или сдавать регион <white>"
                        + Text.esc(id) + "</white>, в котором вы владелец. Согласны - выполните <white>/district region consent "
                        + Text.esc(id) + "</white>.");
            }
        }
        return null;
    }

    private String typeNames() {
        StringBuilder sb = new StringBuilder();
        for (Category c : allowedTypes()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(c.key());
        }
        return sb.length() == 0 ? "нет доступных типов" : sb.toString();
    }

    /** Совладелец региона даёт согласие на продажу и аренду. */
    public String consent(Player p, Plot plot) {
        if (!plot.isPlayerPlot() || !plot.requiredConsents.contains(p.getUniqueId())) {
            return "От вас согласие по этому участку не требуется.";
        }
        if (!plot.consents.add(p.getUniqueId())) {
            return "Вы уже дали согласие.";
        }
        plugin.plots().save();
        plugin.journal().add(plot.id, p.getUniqueId(), p.getName(), Journal.Action.PLAYER_PLOT, "Согласие на продажу и аренду");
        Text.ok(p, "Согласие учтено: участок " + Text.esc(plot.id) + " можно продавать и сдавать.");
        plugin.service().warn(plot.landlord, plot.id, "<green>" + Text.esc(p.getName()) + " согласился на продажу и аренду региона <white>"
                + Text.esc(plot.id) + "</white>." + (plot.consentsComplete() ? " Все согласия получены." : ""));
        return null;
    }

    // ------------------------------------------------------------ объявление

    private String checkLandlord(Player p, Plot plot) {
        if (!plot.isPlayerPlot()) {
            return "Это не участок игрока.";
        }
        if (!plot.isLandlord(p.getUniqueId()) && !p.hasPermission("industryreborn.admin")) {
            return "Объявлением управляет только владелец участка.";
        }
        if (plugin.service().isLocked(plot)) {
            return "Участок сейчас обрабатывается.";
        }
        return null;
    }

    /**
     * Назначает цену продажи и/или аренды за сутки (null - не менять, отрицательное - снять с продажи или аренды).
     * Участок должен быть одобрен и свободен, согласия совладельцев получены, цена аренды не выше предела.
     */
    public String setListing(Player p, Plot plot, Double sale, Double rent) {
        String error = checkLandlord(p, plot);
        if (error != null) {
            return error;
        }
        if (plot.approval != Plot.Approval.APPROVED) {
            return "Участок ещё не одобрен администратором.";
        }
        if (!plot.isFree()) {
            return "Нельзя менять объявление, пока участок продан или сдан.";
        }
        boolean offering = (sale != null && sale >= 0) || (rent != null && rent >= 0);
        if (offering && !plot.consentsComplete()) {
            return "Не все совладельцы региона дали согласие: " + missingConsents(plot);
        }
        if (sale != null && (sale.isNaN() || sale.isInfinite())) {
            return "Цена должна быть числом.";
        }
        if (rent != null && rent >= 0) {
            double cap = rentCap(plot);
            if (rent > cap) {
                return "Аренда за сутки не может быть выше " + plugin.economy().format(cap)
                        + " для участка такого размера (" + plot.box.describe() + ").";
            }
        }
        if (sale != null) {
            plot.price = sale < 0 ? -1 : sale;
        }
        if (rent != null) {
            plot.rentPerDay = rent < 0 ? -1 : rent;
        }
        plugin.plots().save();
        plugin.holograms().update(plot);
        plugin.journal().add(plot.id, p.getUniqueId(), p.getName(), Journal.Action.PLAYER_PLOT,
                "Объявление: продажа " + (plot.price < 0 ? "нет" : plugin.economy().format(plot.price)) + ", аренда "
                        + (plot.rentPerDay < 0 ? "нет" : plugin.economy().format(plot.rentPerDay)) + " за сутки");
        return null;
    }

    public String missingConsents(Plot plot) {
        List<String> names = new ArrayList<>();
        for (UUID u : plot.requiredConsents) {
            if (!plot.consents.contains(u)) {
                names.add(name(u));
            }
        }
        return String.join(", ", names);
    }

    /** Условие аренды: арендодатель сохраняет доступ. Допустимо только для достаточно больших участков. */
    public String setSharedAccess(Player p, Plot plot, boolean shared) {
        String error = checkLandlord(p, plot);
        if (error != null) {
            return error;
        }
        if (!plot.isFree()) {
            return "Условия можно менять только пока участок свободен.";
        }
        if (shared && plot.box.volume() < sharedAccessMinVolume()) {
            return "Общий доступ возможен для участков не меньше " + sharedAccessMinVolume() + " блоков (у вас "
                    + plot.box.volume() + "): на маленьком участке двоим тесно.";
        }
        plot.sharedAccess = shared;
        plugin.plots().save();
        plugin.journal().add(plot.id, p.getUniqueId(), p.getName(), Journal.Action.PLAYER_PLOT,
                "Условие аренды: арендодатель " + (shared ? "сохраняет доступ" : "не сохраняет доступ"));
        return null;
    }

    /** Отвязывает регион (только свободный участок). Права в WorldGuard не меняются. */
    public String unbind(Player p, Plot plot) {
        String error = checkLandlord(p, plot);
        if (error != null) {
            return error;
        }
        if (!plot.isFree()) {
            return "Нельзя отвязать продаваемый или сданный участок.";
        }
        removePlot(plot);
        plugin.journal().add(plot.id, p.getUniqueId(), p.getName(), Journal.Action.PLAYER_PLOT, "Регион отвязан");
        return null;
    }

    /** Удаляет запись об участке игрока со всеми данными. */
    public void removePlot(Plot plot) {
        plugin.plots().remove(plot);
        plugin.ledgers().delete(plot.id);
        plugin.holograms().remove(plot);
        plugin.stations().forget(plot);
        plugin.plots().save();
    }

    // ------------------------------------------------------------ решения администратора

    public void approve(Plot plot) {
        plot.approval = Plot.Approval.APPROVED;
        plugin.plots().save();
    }

    public void reject(Plot plot) {
        removePlot(plot);
    }

    // ------------------------------------------------------------ аренда

    /** Перед началом аренды: регион переходит арендатору (арендодатель сохраняет доступ, если так объявлено). */
    public String beforeRent(Plot plot, Player tenant) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null || !plugin.regions().available()) {
            return "WorldGuard сейчас недоступен: аренда невозможна.";
        }
        Set<UUID> owners = new HashSet<>();
        owners.add(tenant.getUniqueId());
        Set<UUID> members = new HashSet<>();
        if (plot.sharedAccess && plot.landlord != null) {
            members.add(plot.landlord);
        }
        if (!plugin.regions().setDomains(world, plot.regionId, owners, members)) {
            return "Не удалось передать регион арендатору. Попробуйте позже или обратитесь к администрации.";
        }
        return null;
    }

    /** Возвращает регион арендодателю после аренды (и при откате неудачной аренды). */
    public boolean afterRent(Plot plot) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null || !plugin.regions().available()) {
            plugin.getLogger().warning("Не удалось вернуть регион " + plot.regionId + " арендодателю: WorldGuard недоступен");
            return false;
        }
        Set<UUID> owners = new HashSet<>(plot.requiredConsents);
        if (plot.landlord != null) {
            owners.add(plot.landlord);
        }
        boolean ok = plugin.regions().setDomains(world, plot.regionId, owners, new HashSet<>(plot.regionMembers));
        if (!ok) {
            plugin.getLogger().warning("Не удалось вернуть регион " + plot.regionId + " арендодателю");
        }
        return ok;
    }

    // ------------------------------------------------------------ продажа

    /** Покупка участка игрока: деньги продавцу, регион покупателю, объявление снимается. */
    public boolean buy(Player buyer, Plot plot) {
        if (plot.approval != Plot.Approval.APPROVED || !plot.isFree() || plot.price < 0) {
            Text.err(buyer, "Этот участок не продаётся.");
            return false;
        }
        if (plugin.service().isLocked(plot)) {
            Text.err(buyer, "Участок сейчас обрабатывается.");
            return false;
        }
        if (plot.isLandlord(buyer.getUniqueId())) {
            Text.err(buyer, "Нельзя купить собственный участок.");
            return false;
        }
        if (!plugin.economy().available()) {
            Text.err(buyer, "Экономика недоступна: не найден плагин с поддержкой Vault.");
            return false;
        }
        World world = Bukkit.getWorld(plot.world);
        if (world == null || !plugin.regions().available()) {
            Text.err(buyer, "WorldGuard сейчас недоступен: покупка невозможна.");
            return false;
        }
        if (!plugin.economy().has(buyer, plot.price)) {
            Text.err(buyer, "Недостаточно средств. Нужно: " + plugin.economy().format(plot.price));
            return false;
        }
        Set<UUID> owners = new HashSet<>();
        owners.add(buyer.getUniqueId());
        // сначала регион: если передать не удалось, деньги не списываются
        if (!plugin.regions().setDomains(world, plot.regionId, owners, new HashSet<>())) {
            Text.err(buyer, "Не удалось передать регион. Деньги не списаны.");
            return false;
        }
        if (!plugin.economy().withdraw(buyer, plot.price)) {
            afterRent(plot);
            Text.err(buyer, "Недостаточно средств. Нужно: " + plugin.economy().format(plot.price));
            return false;
        }
        OfflinePlayer seller = Bukkit.getOfflinePlayer(plot.landlord);
        if (!plugin.economy().deposit(seller, plot.price)) {
            plugin.treasury().deposit(plot.price, "Не зачислена оплата участка " + plot.id + " продавцу " + seller.getName());
            plugin.saveTreasury();
            plugin.getLogger().warning("Не удалось зачислить продавцу оплату участка " + plot.id);
        }
        plugin.journal().add(plot.id, buyer.getUniqueId(), buyer.getName(), Journal.Action.PLAYER_PLOT,
                "Куплен у " + plot.landlordName + " за " + plugin.economy().format(plot.price));
        plugin.service().warn(plot.landlord, plot.id, "<green>Участок <white>" + Text.esc(plot.id) + "</white> продан игроку "
                + Text.esc(buyer.getName()) + " за " + plugin.economy().format(plot.price) + ".");
        removePlot(plot);
        Text.ok(buyer, "Вы купили регион <white>" + Text.esc(plot.id) + "</white>: теперь вы его владелец в WorldGuard.");
        return true;
    }
}
