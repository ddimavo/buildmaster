package ru.industryreborn.lot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.holo.HoloSpec;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.Module;
import ru.industryreborn.service.PlotService;
import ru.industryreborn.util.Text;

/**
 * Аукцион участков. Администрация (или игрок, продающий свой участок) выставляет свободный участок: стартовая цена, шаг ставки, срок,
 * необязательный выкуп. Игроки ставят ставки; деньги ставки списываются сразу и возвращаются, если ставку перебили. Ставка в последние минуты
 * продлевает торги. По окончании участок достаётся победителю, деньги идут в казну (за вычетом комиссии - продавцу). У лота есть своя голограмма
 * с последней ставкой, шагом, выкупом и временем; клик по ней делает минимальную ставку.
 */
public final class LotService {

    /** Итог торгов для истории. */
    public record Result(long time, String plotId, String winner, double price, String outcome) { }

    private final IndustryRebornPlugin plugin;
    private final File file;
    private final Map<String, Lot> lots = new LinkedHashMap<>();
    private final List<Result> history = new ArrayList<>();
    private final Map<UUID, long[]> pendingClick = new java.util.HashMap<>();   // игрок -> {время, действие 0/1}
    private LongSupplier clock = System::currentTimeMillis;

    public LotService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lots.yml");
        load();
    }

    /** Только для тестов. */
    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    private long now() {
        return clock.getAsLong();
    }

    public boolean enabled() {
        return plugin.modules().enabled(Module.PLOT_AUCTION);
    }

    private double cfg(String key, double def) {
        return plugin.getConfig().getDouble("plot-auction." + key, def);
    }

    public List<Lot> all() {
        return List.copyOf(lots.values());
    }

    public Lot get(String plotId) {
        return plotId == null ? null : lots.get(plotId.toLowerCase(Locale.ROOT));
    }

    public Lot get(Plot plot) {
        return plot == null ? null : get(plot.id);
    }

    public boolean isLot(Plot plot) {
        return get(plot) != null;
    }

    public List<Result> history() {
        return List.copyOf(history);
    }

    private String money(double v) {
        return plugin.economy().format(v);
    }

    // ------------------------------------------------------------ выставление

    /** Выставляет свободный участок. seller - игрок-продавец либо null (администрация). Возвращает текст ошибки или null. */
    public String start(Plot plot, UUID seller, String sellerName, double startPrice, int hours, double step, double buyout) {
        String off = plugin.modules().refusal(Module.PLOT_AUCTION);
        if (off != null) {
            return off;
        }
        if (plot == null) {
            return "Участок не найден.";
        }
        if (isLot(plot)) {
            return "Этот участок уже выставлен на аукцион.";
        }
        if (plot.category.isAdminOnly() || plot.category.isRentOnly() || plot.isPlayerPlot()) {
            return "Участки типа «" + plot.category.title() + "» и участки игроков на аукцион не выставляются.";
        }
        if (!plot.isFree() && seller == null) {
            return "Участок занят: на аукцион выставляется свободный участок (или продажа самим управляющим: /district lot sell).";
        }
        if (plugin.service().isLocked(plot) && seller == null) {
            return "Участок сейчас обрабатывается.";
        }
        if (plot.approval != Plot.Approval.APPROVED) {
            return "Участок ещё не одобрен.";
        }
        if (startPrice <= 0) {
            return "Стартовая цена должна быть больше нуля.";
        }
        int min = (int) cfg("min-hours", 1);
        int max = (int) cfg("max-hours", 168);
        if (hours < min || hours > max) {
            return "Срок торгов: от " + min + " до " + max + " часов.";
        }
        if (buyout > 0 && buyout <= startPrice) {
            return "Цена выкупа должна быть больше стартовой.";
        }
        if (lots.size() >= (int) cfg("max-lots", 20)) {
            return "Одновременно можно проводить не больше " + (int) cfg("max-lots", 20) + " аукционов.";
        }
        double minStep = Math.max(0.01, cfg("min-step", 10));
        double stepPrice = step > 0 ? step : Math.max(minStep, Lot.round(startPrice * cfg("step-percent", 5) / 100.0));
        if (stepPrice < minStep) {
            return "Шаг ставки не меньше " + money(minStep) + ".";
        }
        Lot lot = new Lot(plot.id, seller, sellerName, startPrice, stepPrice, buyout > 0 ? buyout : 0, now(), now() + hours * 3_600_000L);
        placeHolo(lot, plot);
        lots.put(plot.id.toLowerCase(Locale.ROOT), lot);
        save();
        plugin.journal().add(plot.id, seller, sellerName == null ? "администрация" : sellerName, Journal.Action.LOT,
                "Торги: старт " + money(startPrice) + ", шаг " + money(stepPrice) + (lot.buyout > 0 ? ", выкуп " + money(lot.buyout) : "") + ", " + hours + " ч.");
        announce("Аукцион участка " + plot.id, plot.category.title() + " " + plot.box.sizeX() + "×" + plot.box.sizeZ() + (plot.hasBuilding ? " с готовым зданием" : "")
                + ". Старт " + money(startPrice) + ", шаг " + money(stepPrice) + ", торги " + hours + " ч.");
        broadcast("<gold>Новый аукцион участка <white>" + Text.esc(plot.id) + "</white> (" + Text.esc(plot.category.title()) + ")<gold>: старт "
                + money(startPrice) + ", шаг " + money(stepPrice) + ", до " + Text.date(lot.endAt) + ". /district lot info " + plot.id);
        return null;
    }

    /** Позиция голограммы: у голограммы участка, иначе по центру участка. */
    private void placeHolo(Lot lot, Plot plot) {
        double h = cfg("holo-height", 2.2);
        lot.world = plot.world;
        if (plot.hasHolo) {
            lot.x = plot.holoX;
            lot.y = plot.holoY + h;
            lot.z = plot.holoZ;
        } else {
            lot.x = (plot.box.minX() + plot.box.maxX() + 1) / 2.0;
            lot.y = plot.box.minY() + 3 + h;
            lot.z = (plot.box.minZ() + plot.box.maxZ() + 1) / 2.0;
        }
    }

    /** Переносит голограмму лота в позицию администратора. */
    public String moveHolo(Plot plot, Location at) {
        Lot lot = get(plot);
        if (lot == null) {
            return "Участок не выставлен на аукцион.";
        }
        lot.world = at.getWorld().getName();
        lot.x = at.getX();
        lot.y = at.getY();
        lot.z = at.getZ();
        clear(lot);
        save();
        return null;
    }

    /** Игрок продаёт свой участок: участок освобождается (здание остаётся) и выставляется. */
    public String sell(Player p, Plot plot, double startPrice, int hours, double step, double buyout) {
        String off = plugin.modules().refusal(Module.PLOT_AUCTION);
        if (off != null) {
            return off;
        }
        if (!plugin.getConfig().getBoolean("plot-auction.player-sales", true)) {
            return "Продажа участков игроками отключена администрацией.";
        }
        if (plot.isPlayerPlot() || plot.status != Plot.Status.OWNED || !plot.isOwner(p.getUniqueId())) {
            return "Продать на аукционе можно свой купленный участок (не арендованный и не регион игрока).";
        }
        if (plugin.service().isLocked(plot)) {
            return "Участок сейчас обрабатывается.";
        }
        if (isLot(plot)) {
            return "Этот участок уже выставлен на аукцион.";
        }
        // проверка параметров до освобождения участка
        if (startPrice <= 0) {
            return "Стартовая цена должна быть больше нуля.";
        }
        int min = (int) cfg("min-hours", 1);
        int max = (int) cfg("max-hours", 168);
        if (hours < min || hours > max) {
            return "Срок торгов: от " + min + " до " + max + " часов.";
        }
        if (buyout > 0 && buyout <= startPrice) {
            return "Цена выкупа должна быть больше стартовой.";
        }
        if (lots.size() >= (int) cfg("max-lots", 20)) {
            return "Сейчас слишком много аукционов, попробуйте позже.";
        }
        // освобождаем участок: здание остаётся, вещи уходят в хранилище владельца
        plugin.service().evict(plot, PlotService.Release.keeping(p.getName(), "Продажа на аукционе", Journal.Action.RELEASED, false));
        String error = start(plot, p.getUniqueId(), p.getName(), startPrice, hours, step, buyout);
        if (error != null) {
            Text.err(p, "Участок освобождён, но аукцион не начат: " + error);
        }
        return null;
    }

    // ------------------------------------------------------------ ставки

    private String bidderError(Player p, Plot plot, Lot lot) {
        if (!plugin.modules().enabled(Module.PLOT_AUCTION)) {
            return plugin.modules().refusal(Module.PLOT_AUCTION);
        }
        if (!p.hasPermission("industryreborn.use")) {
            return "Недостаточно прав.";
        }
        if (lot.seller != null && lot.seller.equals(p.getUniqueId())) {
            return "Нельзя делать ставки на свой лот.";
        }
        if (now() >= lot.endAt) {
            return "Торги уже закончились.";
        }
        if (plot.restorePending || plot.busy) {
            return "Участок готовится к торгам, подождите несколько секунд.";
        }
        if (!plugin.economy().available()) {
            return "Экономика недоступна: не найден плагин с поддержкой Vault.";
        }
        String limit = plugin.service().ownershipError(p.getUniqueId(), p.hasPermission("industryreborn.bypass"), plot);
        if (limit != null) {
            return limit;
        }
        return null;
    }

    /** Ставка. amount <= 0 - минимально допустимая. Возвращает текст ошибки или null. */
    public String bid(Player p, Plot plot, double amount) {
        Lot lot = get(plot);
        if (lot == null) {
            return "Этот участок не выставлен на аукцион.";
        }
        String error = bidderError(p, plot, lot);
        if (error != null) {
            return error;
        }
        if (lot.bidder != null && lot.bidder.equals(p.getUniqueId())) {
            return "Ваша ставка уже последняя: " + money(lot.bid) + ".";
        }
        double value = amount <= 0 ? lot.minNext() : Lot.round(amount);
        if (value + 1e-9 < lot.minNext()) {
            return "Ставка не меньше " + money(lot.minNext()) + " (шаг " + money(lot.step) + ").";
        }
        if (lot.buyout > 0 && value >= lot.buyout) {
            return "Ставка достигла цены выкупа: выкупите участок за " + money(lot.buyout) + " (/district lot buyout " + plot.id + ").";
        }
        if (!plugin.economy().withdraw(p, value)) {
            return "Недостаточно средств. Нужно: " + money(value) + ".";
        }
        UUID previous = lot.bidder;
        String previousName = lot.bidderName;
        double previousBid = lot.bid;
        if (previous != null) {
            refund(previous, previousBid, plot);
            plugin.service().warn(previous, plot.id, "<red>Вашу ставку на участок <white>" + Text.esc(plot.id) + "</white> перебил игрок <white>"
                    + Text.esc(p.getName()) + "</white>: " + money(value) + ". Ставка " + money(previousBid) + " возвращена. До "
                    + Text.date(lot.endAt) + ".");
        }
        lot.bid = value;
        lot.bidder = p.getUniqueId();
        lot.bidderName = p.getName();
        lot.bids++;
        long window = (long) (cfg("snipe-window-minutes", 2) * 60_000);
        if (window > 0 && lot.endAt - now() < window) {
            lot.endAt = now() + (long) (cfg("snipe-extend-minutes", 2) * 60_000);
        }
        save();
        refresh(lot, plot);
        plugin.journal().add(plot.id, p.getUniqueId(), p.getName(), Journal.Action.LOT, "Ставка " + money(value) + (previousName == null ? "" : " (перебита " + previousName + ")"));
        Text.ok(p, "Ваша ставка на участок <white>" + plot.id + "</white>: " + money(value) + ". Следующая будет не меньше " + money(lot.minNext())
                + ". До " + Text.date(lot.endAt) + ".");
        return null;
    }

    /** Мгновенный выкуп по цене выкупа: торги заканчиваются, участок достаётся игроку. */
    public String buyout(Player p, Plot plot) {
        Lot lot = get(plot);
        if (lot == null) {
            return "Этот участок не выставлен на аукцион.";
        }
        if (lot.buyout <= 0) {
            return "У этого лота нет цены выкупа.";
        }
        String error = bidderError(p, plot, lot);
        if (error != null) {
            return error;
        }
        if (!plugin.economy().withdraw(p, lot.buyout)) {
            return "Недостаточно средств. Нужно: " + money(lot.buyout) + ".";
        }
        if (lot.bidder != null) {
            refund(lot.bidder, lot.bid, plot);
            plugin.service().warn(lot.bidder, plot.id, "<red>Участок <white>" + Text.esc(plot.id) + "</white> выкуплен игроком <white>"
                    + Text.esc(p.getName()) + "</white> за " + money(lot.buyout) + ". Ваша ставка " + money(lot.bid) + " возвращена.");
        }
        lot.bid = lot.buyout;
        lot.bidder = p.getUniqueId();
        lot.bidderName = p.getName();
        lot.bids++;
        finish(lot, "выкуп");
        return null;
    }

    private void refund(UUID player, double amount, Plot plot) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(player);
        if (!plugin.economy().deposit(op, amount)) {
            plugin.getLogger().warning("Не удалось вернуть ставку " + amount + " игроку " + player + " по лоту " + plot.id);
        }
    }

    // ------------------------------------------------------------ завершение

    /** Отмена торгов администратором: ставка возвращается, участок остаётся свободным. */
    public String cancel(Plot plot, String actor, String reason) {
        Lot lot = get(plot);
        if (lot == null) {
            return "Этот участок не выставлен на аукцион.";
        }
        if (lot.bidder != null) {
            refund(lot.bidder, lot.bid, plot);
            plugin.service().warn(lot.bidder, plot.id, "<yellow>Торги по участку <white>" + Text.esc(plot.id) + "</white> отменены администрацией. Ставка "
                    + money(lot.bid) + " возвращена. Причина: " + Text.esc(reason));
        }
        remove(lot);
        record(lot, null, 0, "отменён");
        plugin.journal().add(plot.id, null, actor, Journal.Action.LOT, "Торги отменены: " + reason);
        return null;
    }

    /** Завершает торги сейчас (администратор) либо по времени. */
    public void finish(Lot lot, String how) {
        Plot plot = plugin.plots().get(lot.plotId);
        if (plot == null) {
            if (lot.bidder != null) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(lot.bidder);
                plugin.economy().deposit(op, lot.bid);
            }
            remove(lot);
            return;
        }
        if (plugin.service().isLocked(plot)) {
            // участок ещё готовится (сброс, стройка): торги закончатся через полминуты
            lot.endAt = Math.max(lot.endAt, now() + 30_000L);
            save();
            return;
        }
        if (lot.bidder == null) {
            remove(lot);
            record(lot, null, 0, "без ставок");
            plugin.journal().add(plot.id, lot.seller, "система", Journal.Action.LOT, "Торги закончились без ставок");
            if (lot.seller != null) {
                plugin.service().warn(lot.seller, plot.id, "<yellow>Торги по участку <white>" + Text.esc(plot.id) + "</white> закончились без ставок. Участок свободен.");
            }
            announce("Аукцион участка " + plot.id + " закончился", "Ставок не было, участок свободен.");
            return;
        }
        UUID winner = lot.bidder;
        String limit = plugin.service().ownershipError(winner, Bukkit.getPlayer(winner) != null && Bukkit.getPlayer(winner).hasPermission("industryreborn.bypass"), plot);
        if (limit != null) {
            refund(winner, lot.bid, plot);
            plugin.service().warn(winner, plot.id, "<red>Вы выиграли участок <white>" + Text.esc(plot.id) + "</white>, но получить его нельзя: "
                    + Text.esc(limit) + ". Ставка " + money(lot.bid) + " возвращена.");
            remove(lot);
            record(lot, lot.bidderName, 0, "победитель не смог принять участок");
            plugin.journal().add(plot.id, winner, "система", Journal.Action.LOT, "Победитель не смог принять участок, ставка возвращена");
            return;
        }
        double price = lot.bid;
        if (lot.seller != null) {
            double commission = Lot.round(price * Math.max(0, Math.min(100, cfg("seller-commission-percent", 10))) / 100.0);
            plugin.treasury().deposit(commission, "Комиссия аукциона участка " + plot.id);
            plugin.economy().deposit(Bukkit.getOfflinePlayer(lot.seller), price - commission);
            plugin.service().warn(lot.seller, plot.id, "<green>Участок <white>" + Text.esc(plot.id) + "</white> продан игроку <white>" + Text.esc(lot.bidderName)
                    + "</white> за " + money(price) + ". Вам зачислено " + money(price - commission) + " (комиссия " + money(commission) + ").");
        } else {
            plugin.treasury().deposit(price, "Аукцион участка " + plot.id + ": " + lot.bidderName);
        }
        plugin.saveTreasury();
        remove(lot);
        plugin.service().occupy(plot, winner, lot.bidderName, Plot.Status.OWNED, 0);
        plugin.holograms().update(plot);
        record(lot, lot.bidderName, price, how == null ? "продан" : how);
        plugin.journal().add(plot.id, winner, lot.bidderName, Journal.Action.BOUGHT, "Аукцион: " + money(price) + (how == null ? "" : " (" + how + ")"));
        plugin.service().warn(winner, plot.id, "<green>Вы выиграли аукцион: участок <white>" + Text.esc(plot.id) + "</white> за " + money(price)
                + (plot.hasBuilding ? " (с готовым зданием)" : "") + ". Вы - управляющий: главным владельцем остаётся администрация.");
        announce("Аукцион участка " + plot.id + " завершён", "Победитель " + lot.bidderName + ", цена " + money(price) + ".");
        broadcast("<gold>Аукцион участка <white>" + Text.esc(plot.id) + "</white><gold> завершён: победил <white>" + Text.esc(lot.bidderName) + "</white><gold> со ставкой " + money(price) + ".");
    }

    private void record(Lot lot, String winner, double price, String outcome) {
        history.add(0, new Result(now(), lot.plotId, winner, price, outcome));
        while (history.size() > 30) {
            history.remove(history.size() - 1);
        }
        save();
    }

    private void remove(Lot lot) {
        clear(lot);
        lots.remove(lot.plotId.toLowerCase(Locale.ROOT));
        save();
    }

    private void announce(String title, String text) {
        plugin.webhooks().event("lot", title, text, 0xFFCF5A);
    }

    private void broadcast(String mini) {
        if (!plugin.getConfig().getBoolean("plot-auction.broadcast", true)) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            Text.send(p, mini);
        }
    }

    // ------------------------------------------------------------ голограмма

    private String remaining(Lot lot) {
        long left = Math.max(0, lot.endAt - now());
        return Text.duration(left);
    }

    /** Текст голограммы лота. */
    public String holoText(Lot lot, Plot plot) {
        StringBuilder sb = new StringBuilder("<gold><bold>АУКЦИОН УЧАСТКА</bold>\n<white>№ ").append(Text.esc(plot.id)).append(" · ").append(Text.esc(plot.category.title()))
                .append(" · ").append(plot.box.sizeX()).append("×").append(plot.box.sizeZ());
        if (plot.hasBuilding) {
            sb.append("\n<green>с готовым зданием");
        }
        if (lot.hasBids()) {
            sb.append("\n<yellow>Последняя ставка: <white>").append(money(lot.bid)).append(" <gray>(").append(Text.esc(lot.bidderName)).append(")");
        } else {
            sb.append("\n<yellow>Ставок нет, старт: <white>").append(money(lot.start));
        }
        sb.append("\n<gray>Шаг ставки: <white>").append(money(lot.step)).append(" <gray>· следующая: <green>").append(money(lot.minNext()));
        if (lot.buyout > 0) {
            sb.append("\n<aqua>Выкуп: ").append(money(lot.buyout));
        }
        sb.append("\n<gray>Осталось: <white>").append(remaining(lot)).append(" <gray>· ставок: ").append(lot.bids);
        if (plugin.getConfig().getBoolean("plot-auction.click-bid", true)) {
            sb.append("\n<dark_gray>ПКМ - ставка ").append(money(lot.minNext())).append(lot.buyout > 0 ? ", Shift+ПКМ - выкуп" : "");
        }
        return sb.toString();
    }

    private void clear(Lot lot) {
        if (lot.holo != null) {
            lot.holo.remove();
            lot.holo = null;
        }
        if (lot.zone != null) {
            lot.zone.remove();
            lot.zone = null;
        }
        lot.shownText = "";
    }

    public void removeAll() {
        lots.values().forEach(this::clear);
    }

    private void refresh(Lot lot, Plot plot) {
        if (lot.holo != null && lot.holo.valid()) {
            String text = holoText(lot, plot);
            if (!text.equals(lot.shownText)) {
                lot.holo.text(text);
                lot.shownText = text;
            }
        }
    }

    private boolean viewerNear(Lot lot, World world) {
        double view = cfg("view-distance", 25);
        Location base = new Location(world, lot.x, lot.y, lot.z);
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(base) <= view * view) {
                return true;
            }
        }
        return false;
    }

    /** Раз в секунду: завершает истёкшие торги, показывает голограммы рядом с игроками и обновляет их. */
    public void tick() {
        if (!enabled()) {
            removeAll();
            return;
        }
        for (Lot lot : new ArrayList<>(lots.values())) {
            if (now() >= lot.endAt) {
                finish(lot, null);
                continue;
            }
            Plot plot = plugin.plots().get(lot.plotId);
            World world = Bukkit.getWorld(lot.world == null ? "" : lot.world);
            if (plot == null || world == null || !world.isChunkLoaded((int) Math.floor(lot.x) >> 4, (int) Math.floor(lot.z) >> 4) || !viewerNear(lot, world)) {
                clear(lot);
                continue;
            }
            boolean valid = lot.holo != null && lot.holo.valid() && (lot.zone == null || lot.zone.valid());
            if (!valid) {
                clear(lot);
                float range = (float) (cfg("view-distance", 25) / 64.0);
                Location at = new Location(world, lot.x, lot.y, lot.z);
                String text = holoText(lot, plot);
                lot.holo = plugin.holoFactory().spawn("lot_" + lot.plotId, new HoloSpec(at, text).style(260, range, 0x78000000).marker(lot.plotId));
                lot.shownText = text;
                if (plugin.getConfig().getBoolean("plot-auction.click-bid", true)) {
                    lot.zone = plugin.clickZones().create(new Location(world, lot.x, lot.y - 0.6, lot.z), 1.6, 2.2, (p, sneak, left) -> click(lot, p, sneak, left));
                }
            } else {
                refresh(lot, plot);
            }
        }
    }

    // ------------------------------------------------------------ клик по голограмме

    void click(Lot lot, Player p, boolean sneaking, boolean left) {
        if (left || !enabled() || get(lot.plotId) != lot) {
            return;
        }
        Plot plot = plugin.plots().get(lot.plotId);
        if (plot == null) {
            return;
        }
        boolean buy = sneaking && lot.buyout > 0;
        long[] pending = pendingClick.get(p.getUniqueId());
        long window = (long) (cfg("click-confirm-seconds", 6) * 1000);
        long time = now();
        if (pending == null || time - pending[0] > window || pending[1] != (buy ? 1 : 0)) {
            pendingClick.put(p.getUniqueId(), new long[] {time, buy ? 1 : 0});
            Text.send(p, buy ? "<yellow>Выкуп участка <white>" + Text.esc(plot.id) + "</white> за " + money(lot.buyout) + ". Нажмите ещё раз (Shift+ПКМ) в течение "
                    + (window / 1000) + " с для подтверждения."
                    : "<yellow>Ставка " + money(lot.minNext()) + " на участок <white>" + Text.esc(plot.id) + "</white>. Нажмите ещё раз (ПКМ) в течение "
                    + (window / 1000) + " с для подтверждения.");
            return;
        }
        pendingClick.remove(p.getUniqueId());
        String error = buy ? buyout(p, plot) : bid(p, plot, 0);
        if (error != null) {
            Text.err(p, error);
        }
    }

    /** Для тестов: клик по голограмме лота. */
    public void press(String plotId, Player p, boolean sneaking) {
        Lot lot = get(plotId);
        if (lot != null) {
            click(lot, p, sneaking, false);
        }
    }

    // ------------------------------------------------------------ хранение

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("lots");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(id);
                if (c == null) {
                    continue;
                }
                try {
                    UUID seller = c.getString("seller") == null ? null : UUID.fromString(c.getString("seller"));
                    Lot lot = new Lot(id, seller, c.getString("seller-name"), c.getDouble("start"), c.getDouble("step"), c.getDouble("buyout"),
                            c.getLong("started-at"), c.getLong("end-at"));
                    lot.bid = c.getDouble("bid");
                    lot.bidder = c.getString("bidder") == null ? null : UUID.fromString(c.getString("bidder"));
                    lot.bidderName = c.getString("bidder-name");
                    lot.bids = c.getInt("bids");
                    lot.world = c.getString("world");
                    lot.x = c.getDouble("x");
                    lot.y = c.getDouble("y");
                    lot.z = c.getDouble("z");
                    lots.put(id.toLowerCase(Locale.ROOT), lot);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Лот " + id + " пропущен: " + e.getMessage());
                }
            }
        }
        for (String line : yml.getStringList("history")) {
            String[] p = line.split("\\|", 5);
            if (p.length == 5) {
                try {
                    history.add(new Result(Long.parseLong(p[0]), p[1], p[2].isEmpty() ? null : p[2], Double.parseDouble(p[3]), p[4]));
                } catch (NumberFormatException ignored) {
                    // повреждённая запись
                }
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Lot lot : lots.values()) {
            String k = "lots." + lot.plotId + ".";
            yml.set(k + "seller", lot.seller == null ? null : lot.seller.toString());
            yml.set(k + "seller-name", lot.sellerName);
            yml.set(k + "start", lot.start);
            yml.set(k + "step", lot.step);
            yml.set(k + "buyout", lot.buyout);
            yml.set(k + "started-at", lot.startedAt);
            yml.set(k + "end-at", lot.endAt);
            yml.set(k + "bid", lot.bid);
            yml.set(k + "bidder", lot.bidder == null ? null : lot.bidder.toString());
            yml.set(k + "bidder-name", lot.bidderName);
            yml.set(k + "bids", lot.bids);
            yml.set(k + "world", lot.world);
            yml.set(k + "x", lot.x);
            yml.set(k + "y", lot.y);
            yml.set(k + "z", lot.z);
        }
        List<String> h = new ArrayList<>();
        for (Result r : history) {
            h.add(r.time() + "|" + r.plotId() + "|" + (r.winner() == null ? "" : r.winner().replace("|", "/")) + "|" + r.price() + "|" + r.outcome().replace("|", "/"));
        }
        yml.set("history", h);
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить lots.yml: " + e.getMessage());
        }
    }
}
