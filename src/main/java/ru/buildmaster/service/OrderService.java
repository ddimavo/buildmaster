package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.ServiceOrder;
import ru.buildmaster.model.StorageEntry;
import ru.buildmaster.util.Text;

/**
 * Заказы в мастерских: клиент оплачивает услугу и отдаёт материалы (эскроу), мастерская выполняет заказ
 * автоматически, пока есть электричество, клиент забирает результат, деньги уходят на счёт мастерской.
 * Готовые заказы можно забрать всегда; при закрытии мастерской клиентам возвращаются материалы и деньги.
 */
public final class OrderService {

    private final BuildMasterPlugin plugin;
    private final File file;
    private final Map<UUID, ServiceOrder> orders = new LinkedHashMap<>();
    private long lastTickSec = Long.MIN_VALUE;

    public OrderService(BuildMasterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "orders.yml");
    }

    // ------------------------------------------------------------ запросы

    public List<ServiceOrder> all() {
        return new ArrayList<>(orders.values());
    }

    public List<ServiceOrder> ofPlot(String plotId) {
        List<ServiceOrder> list = new ArrayList<>();
        for (ServiceOrder o : orders.values()) {
            if (o.plotId.equalsIgnoreCase(plotId)) {
                list.add(o);
            }
        }
        return list;
    }

    public List<ServiceOrder> ofClient(UUID client) {
        List<ServiceOrder> list = new ArrayList<>();
        for (ServiceOrder o : orders.values()) {
            if (o.client.equals(client)) {
                list.add(o);
            }
        }
        return list;
    }

    /** Заказ по полному идентификатору или первым символам. */
    public ServiceOrder find(String idOrPrefix) {
        String q = idOrPrefix.toLowerCase();
        ServiceOrder found = null;
        for (ServiceOrder o : orders.values()) {
            if (o.id.toString().startsWith(q)) {
                if (found != null) {
                    return null;
                }
                found = o;
            }
        }
        return found;
    }

    private static String stationKeyOf(ServiceCatalog.Offer def) {
        return def.stationKey();
    }

    private double timeFactor() {
        return Math.max(0, plugin.getConfig().getDouble("services.time-factor", 1.0));
    }

    /** Время на единицу работы с учётом числа станков и скорости; бесконечность без станков. */
    private double secondsPerUnit(Plot plot, ServiceCatalog.Offer def, ServiceOrder order) {
        int stations = plugin.stations().countByTariffKey(plot, def.stationKey());
        double base = def.secondsPerUnit();
        if (def.kind() == ServiceCatalog.Kind.SMELT && !order.input.isEmpty()) {
            base = plugin.catalog().baseSeconds(def, ItemStack.deserializeBytes(order.input.get(0)));
        }
        double speed = Math.max(0.01, plugin.upgrades().of(plot).speed());
        return OrderProgress.secondsPerUnit(base, stations, timeFactor()) / speed;
    }

    /** Оценка, через сколько секунд заказ будет готов (с учётом очереди); бесконечность - если не движется. */
    public double etaSeconds(ServiceOrder order) {
        Plot plot = plugin.plots().get(order.plotId);
        ServiceCatalog.Offer def = plugin.catalog().get(order.offerKey);
        if (plot == null || def == null) {
            return Double.POSITIVE_INFINITY;
        }
        if (order.state == ServiceOrder.State.READY) {
            return 0;
        }
        double total = 0;
        for (ServiceOrder o : queue(plot, def.stationKey())) {
            ServiceCatalog.Offer d = plugin.catalog().get(o.offerKey);
            if (d == null) {
                continue;
            }
            total += OrderProgress.remainingSeconds(o.units, o.progressSeconds, secondsPerUnit(plot, d, o));
            if (o.id.equals(order.id)) {
                break;
            }
        }
        return total;
    }

    /** Очередь на станок данного типа: по времени создания, готовые не входят. */
    private List<ServiceOrder> queue(Plot plot, String stationKey) {
        List<ServiceOrder> list = new ArrayList<>();
        for (ServiceOrder o : ofPlot(plot.id)) {
            ServiceCatalog.Offer def = plugin.catalog().get(o.offerKey);
            if (o.state != ServiceOrder.State.READY && def != null && stationKeyOf(def).equals(stationKey)) {
                list.add(o);
            }
        }
        list.sort(Comparator.comparingLong(o -> o.createdAt));
        return list;
    }

    // ------------------------------------------------------------ прайс мастерской

    /** Предложение мастерской (по умолчанию выключено, цена из каталога). */
    public Plot.Offer offerOf(Plot plot, ServiceCatalog.Offer def) {
        return plot.offers.getOrDefault(def.key(), new Plot.Offer(false, def.defaultPrice()));
    }

    /** Управляющий меняет цену и/или включает услугу. Возвращает ошибку или null. */
    public String configureOffer(Player manager, Plot plot, String offerKey, Double price, Boolean enabled) {
        if (!plot.isOwner(manager.getUniqueId()) && !manager.hasPermission("buildmaster.admin")) {
            return "Прайс меняет только управляющий мастерской.";
        }
        if (plot.category != Category.WORKSHOP) {
            return "Услуги есть только у мастерских.";
        }
        ServiceCatalog.Offer def = plugin.catalog().get(offerKey);
        if (def == null) {
            return "Неизвестная услуга. Список: /district service offers";
        }
        if (price != null && (price < 0 || price.isNaN() || price.isInfinite() || price > def.maxPrice())) {
            return "Цена за единицу: от 0 до " + plugin.economy().format(def.maxPrice()) + ".";
        }
        Plot.Offer offer = plot.offers.computeIfAbsent(def.key(), k -> new Plot.Offer(false, def.defaultPrice()));
        if (price != null) {
            offer.price = price;
        }
        if (enabled != null) {
            offer.enabled = enabled;
        }
        plugin.plots().save();
        plugin.journal().add(plot.id, manager.getUniqueId(), manager.getName(), Journal.Action.SERVICE_PRICE,
                def.title() + ": " + (offer.enabled ? "включено" : "выключено") + ", "
                        + plugin.economy().format(offer.price) + " за единицу");
        return null;
    }

    // ------------------------------------------------------------ создание заказа

    private List<ItemStack> candidates(Player client, boolean all) {
        ItemStack hand = client.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            return List.of();
        }
        if (!all) {
            return List.of(hand);
        }
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack s : client.getInventory().getStorageContents()) {
            if (s != null && s.getType() == hand.getType()) {
                list.add(s);
            }
        }
        return list;
    }

    /**
     * Создаёт заказ: забирает материалы из руки (или все такие же из инвентаря) и деньги, всё уходит в эскроу.
     * Возвращает текст ошибки или null.
     */
    public String create(Player client, Plot plot, String offerKey, boolean all) {
        if (plot.category != Category.WORKSHOP || plot.isFree() || plot.owner == null) {
            return "Заказы принимают только действующие мастерские.";
        }
        if (plugin.service().isLocked(plot)) {
            return "Мастерская сейчас обрабатывается, попробуйте позже.";
        }
        if (plot.isOwner(client.getUniqueId())) {
            return "Нельзя сделать заказ в собственной мастерской.";
        }
        ServiceCatalog.Offer def = plugin.catalog().get(offerKey);
        Plot.Offer offer = def == null ? null : plot.offers.get(def.key());
        if (def == null || offer == null || !offer.enabled) {
            return "Эта мастерская не оказывает такую услугу.";
        }
        if (!plugin.power().isPowered(plot)) {
            String reason = plugin.power().gridOffReason(plot);
            return reason != null ? "Мастерская отключена от электростанции: " + reason + "."
                    : "Мастерская обесточена: новые заказы не принимаются.";
        }
        if (plugin.stations().countByTariffKey(plot, def.stationKey()) <= 0) {
            return "В мастерской нет нужного станка для этой услуги.";
        }
        var cfg = plugin.getConfig();
        if (ofPlot(plot.id).stream().filter(o -> o.state != ServiceOrder.State.READY).count()
                >= cfg.getInt("services.max-active-orders-per-workshop", 20) + plugin.upgrades().of(plot).orderQueue()) {
            return "В мастерской слишком большая очередь, попробуйте позже.";
        }
        if (ofClient(client.getUniqueId()).size() >= cfg.getInt("services.max-orders-per-client", 5)) {
            return "У вас уже слишком много незавершённых заказов. Заберите готовые: /district service orders";
        }
        if (!plugin.economy().available()) {
            return "Экономика недоступна: не найден плагин с поддержкой Vault.";
        }

        ServiceCatalog.Prepared prepared = plugin.catalog().prepare(def, candidates(client, all));
        if (!prepared.ok()) {
            return prepared.error();
        }
        int maxUnits = cfg.getInt("services.max-units-per-order", 256);
        if (prepared.units() > maxUnits) {
            return "Слишком большой заказ: не больше " + maxUnits + " единиц за раз.";
        }
        double total = offer.price * prepared.units();
        PlayerInventory inv = client.getInventory();
        for (ItemStack s : prepared.consume()) {
            if (!inv.containsAtLeast(s, s.getAmount())) {
                return "Предметы для заказа изменились, повторите.";
            }
        }
        if (!plugin.economy().has(client, total)) {
            return "Недостаточно средств. Нужно: " + plugin.economy().format(total);
        }
        if (!inv.removeItem(prepared.consume().toArray(new ItemStack[0])).isEmpty()) {
            return "Не удалось забрать предметы для заказа.";
        }
        if (!plugin.economy().withdraw(client, total)) {
            deliver(client.getUniqueId(), plot.id, prepared.consume(), "Возврат: не удалось списать оплату заказа");
            return "Недостаточно средств. Нужно: " + plugin.economy().format(total);
        }

        ServiceOrder order = new ServiceOrder(UUID.randomUUID(), plot.id, client.getUniqueId(), client.getName(), def.key(),
                prepared.units(), total, System.currentTimeMillis());
        prepared.consume().forEach(s -> order.input.add(s.serializeAsBytes()));
        prepared.outputs().forEach(s -> order.output.add(s.serializeAsBytes()));
        orders.put(order.id, order);
        save();
        plugin.journal().add(plot.id, client.getUniqueId(), client.getName(), Journal.Action.SERVICE_ORDER,
                def.title() + " × " + prepared.units() + ", оплачено " + plugin.economy().format(total)
                        + " (заказ " + order.shortId() + ")");
        Text.ok(client, "Заказ <white>" + order.shortId() + "</white> принят: " + Text.esc(def.title()) + " × "
                + prepared.units() + ". Оплачено " + plugin.economy().format(total)
                + ". Примерное время: " + eta(order) + ". Следить: /district service orders");
        if (plot.owner != null) {
            plugin.service().warn(plot.owner, plot.id, "<green>Новый заказ в мастерской <white>" + Text.esc(plot.id)
                    + "</white>: " + Text.esc(def.title()) + " × " + prepared.units() + " от " + Text.esc(client.getName()) + ".");
        }
        return null;
    }

    public String eta(ServiceOrder order) {
        double s = etaSeconds(order);
        return Double.isInfinite(s) ? "не определено (мастерская не работает)" : Text.duration((long) (s * 1000));
    }

    // ------------------------------------------------------------ выдача, отмена, возврат

    /** Возвращает предметы онлайн-игроку, остаток и всё для оффлайн-игрока - в хранилище. */
    public void deliver(UUID uuid, String plotId, List<ItemStack> items, String reason) {
        if (items.isEmpty()) {
            return;
        }
        List<ItemStack> rest = items;
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            rest = new ArrayList<>(online.getInventory().addItem(items.toArray(new ItemStack[0])).values());
        }
        if (!rest.isEmpty()) {
            plugin.storage().add(uuid, plotId, rest, StorageEntry.State.AVAILABLE, reason, "order-" + UUID.randomUUID());
        }
    }

    private static List<ItemStack> stacks(List<byte[]> data) {
        List<ItemStack> list = new ArrayList<>();
        for (byte[] b : data) {
            list.add(ItemStack.deserializeBytes(b));
        }
        return list;
    }

    private void refundMoney(UUID client, double amount) {
        if (amount <= 0) {
            return;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(client);
        if (!plugin.economy().deposit(op, amount)) {
            plugin.treasury().deposit(amount, "Не возвращены деньги по заказу (" + op.getName() + ")");
            plugin.saveTreasury();
            plugin.getLogger().warning("Не удалось вернуть оплату заказа игроку " + op.getName());
        }
    }

    /** Деньги за выполненный заказ поступают на счёт мастерской. */
    private void payWorkshop(Plot plot, ServiceOrder order, UUID manager) {
        if (plot != null && manager != null) {
            plugin.power().touch(plot);
            plot.account.deposit(order.paid);
            plugin.plots().save();
            plugin.service().warn(manager, plot.id, "<green>Заказ <white>" + order.shortId() + "</white> выдан клиенту "
                    + Text.esc(order.clientName) + ": на счёт участка <white>" + Text.esc(plot.id) + "</white> зачислено "
                    + plugin.economy().format(order.paid) + ".");
        } else {
            refundMoney(order.client, order.paid);
        }
    }

    /** Клиент забирает готовый заказ. Возвращает ошибку или null. */
    public String collect(Player client, UUID orderId) {
        ServiceOrder o = orders.get(orderId);
        if (o == null || !o.client.equals(client.getUniqueId())) {
            return "Заказ не найден.";
        }
        if (o.state != ServiceOrder.State.READY) {
            return "Заказ ещё не готов.";
        }
        orders.remove(o.id);
        save();
        deliver(client.getUniqueId(), o.plotId, stacks(o.output), "Готовый заказ " + o.shortId());
        Plot workshop = plugin.plots().get(o.plotId);
        payWorkshop(workshop, o, workshop == null ? null : workshop.owner);
        plugin.journal().add(o.plotId, client.getUniqueId(), client.getName(), Journal.Action.SERVICE_DONE,
                "Заказ " + o.shortId() + " выдан, оплата " + plugin.economy().format(o.paid) + " зачислена на счёт мастерской");
        Text.ok(client, "Заказ <white>" + o.shortId() + "</white> выдан. Если не хватило места, остальное в хранилище: /district storage");
        return null;
    }

    /** Клиент отменяет заказ, который ещё не начали выполнять. */
    public String cancel(Player client, UUID orderId) {
        ServiceOrder o = orders.get(orderId);
        if (o == null || !o.client.equals(client.getUniqueId())) {
            return "Заказ не найден.";
        }
        if (o.state != ServiceOrder.State.PENDING) {
            return o.state == ServiceOrder.State.ACTIVE ? "Заказ уже выполняется: отменить нельзя."
                    : "Заказ готов: заберите его.";
        }
        refund(o, "Отмена заказа клиентом", client.getName());
        return null;
    }

    /** Возврат материалов и денег клиенту; заказ удаляется. */
    private void refund(ServiceOrder o, String reason, String actor) {
        orders.remove(o.id);
        save();
        deliver(o.client, o.plotId, stacks(o.input), reason + ": материалы заказа " + o.shortId());
        refundMoney(o.client, o.paid);
        plugin.journal().add(o.plotId, o.client, actor, Journal.Action.SERVICE_REFUND,
                "Заказ " + o.shortId() + ": " + reason + ", возвращено " + plugin.economy().format(o.paid));
        plugin.service().warn(o.client, o.plotId, "<yellow>Заказ <white>" + o.shortId() + "</white> в мастерской <white>"
                + Text.esc(o.plotId) + "</white> отменён (" + Text.esc(reason) + "). Материалы возвращены (в инвентарь или "
                + "хранилище), деньги - " + plugin.economy().format(o.paid) + ".");
    }

    /** Администратор отменяет заказ с обязательной причиной. Возвращает ошибку или null. */
    public String adminCancel(String admin, ServiceOrder o, String reason) {
        if (reason == null || reason.isBlank()) {
            return "Укажите причину отмены.";
        }
        if (o.state == ServiceOrder.State.READY) {
            // материалы уже переработаны: клиенту результат и деньги
            orders.remove(o.id);
            save();
            deliver(o.client, o.plotId, stacks(o.output), "Отмена администратором: заказ " + o.shortId());
            refundMoney(o.client, o.paid);
            plugin.journal().add(o.plotId, o.client, admin, Journal.Action.SERVICE_REFUND,
                    "Заказ " + o.shortId() + " отменён администратором: " + reason);
            plugin.service().warn(o.client, o.plotId, "<yellow>Заказ <white>" + o.shortId() + "</white> отменён администрацией: "
                    + Text.esc(reason) + ". Результат отправлен вам, деньги возвращены.");
        } else {
            refund(o, "Отмена администратором: " + reason, admin);
        }
        return null;
    }

    /**
     * Мастерская закрывается (отказ, окончание аренды, изъятие): невыполненные заказы возвращаются клиентам
     * с материалами и деньгами, готовые - результатом в хранилище, а оплата поступает на счёт мастерской.
     */
    public void closeWorkshop(Plot plot, UUID manager) {
        for (ServiceOrder o : ofPlot(plot.id)) {
            if (o.state == ServiceOrder.State.READY) {
                orders.remove(o.id);
                deliver(o.client, o.plotId, stacks(o.output), "Готовый заказ " + o.shortId() + " (мастерская закрыта)");
                payWorkshop(plot, o, manager);
                plugin.journal().add(o.plotId, o.client, "система", Journal.Action.SERVICE_DONE,
                        "Заказ " + o.shortId() + " выдан в хранилище: мастерская закрыта");
            } else {
                refund(o, "Мастерская закрыта", "система");
            }
        }
        save();
    }

    // ------------------------------------------------------------ выполнение

    /** Периодическая задача: продвигает заказы по биллинговому времени, пока в мастерской есть электричество. */
    public void tick() {
        long now = plugin.power().nowSec();
        double dt = lastTickSec == Long.MIN_VALUE ? 0 : Math.max(0, now - lastTickSec);
        lastTickSec = now;
        if (orders.isEmpty()) {
            return;
        }
        boolean changed = false;
        List<String> plotIds = new ArrayList<>();
        for (ServiceOrder o : orders.values()) {
            if (!plotIds.contains(o.plotId.toLowerCase())) {
                plotIds.add(o.plotId.toLowerCase());
            }
        }
        for (String id : plotIds) {
            Plot plot = plugin.plots().get(id);
            if (plot == null) {
                for (ServiceOrder o : ofPlot(id)) {
                    refund(o, "Мастерская больше не существует", "система");
                }
                changed = true;
                continue;
            }
            changed |= expireReady(plot);
            if (plot.restorePending || plugin.service().isLocked(plot)) {
                continue;
            }
            changed |= process(plot, dt);
        }
        if (changed) {
            save();
        }
    }

    private boolean expireReady(Plot plot) {
        long limit = Math.max(1, plugin.getConfig().getInt("services.ready-expire-days", 30)) * plugin.service().dayMillis();
        boolean changed = false;
        for (ServiceOrder o : ofPlot(plot.id)) {
            if (o.state == ServiceOrder.State.READY && System.currentTimeMillis() - o.readyAt > limit) {
                orders.remove(o.id);
                deliver(o.client, o.plotId, stacks(o.output), "Заказ " + o.shortId() + " не забрали вовремя");
                payWorkshop(plot, o, plot.owner);
                plugin.journal().add(o.plotId, o.client, "система", Journal.Action.SERVICE_DONE,
                        "Заказ " + o.shortId() + " не забрали за " + plugin.getConfig().getInt("services.ready-expire-days", 30)
                                + " сут.: результат в хранилище клиента");
                changed = true;
            }
        }
        return changed;
    }

    /** Один шаг по мастерской: на каждый тип станка работает первый заказ очереди. */
    private boolean process(Plot plot, double dt) {
        boolean powered = plugin.power().isPowered(plot);
        boolean changed = false;
        Map<String, Boolean> served = new LinkedHashMap<>();
        for (ServiceOrder o : ofPlot(plot.id).stream().sorted(Comparator.comparingLong(x -> x.createdAt)).toList()) {
            if (o.state == ServiceOrder.State.READY) {
                continue;
            }
            ServiceCatalog.Offer def = plugin.catalog().get(o.offerKey);
            if (def == null) {
                refund(o, "Услуга больше не предоставляется", "система");
                changed = true;
                continue;
            }
            if (served.putIfAbsent(def.stationKey(), true) != null) {
                continue; // на этот станок уже работает заказ впереди в очереди
            }
            if (o.state == ServiceOrder.State.PENDING) {
                o.state = ServiceOrder.State.ACTIVE;
                changed = true;
            }
            if (powered) {
                changed |= advance(plot, o, def, dt);
            }
        }
        return changed;
    }

    private boolean advance(Plot plot, ServiceOrder o, ServiceCatalog.Offer def, double dt) {
        double eff = secondsPerUnit(plot, def, o);
        if (Double.isInfinite(eff)) {
            return false; // станков нет: заказ ждёт
        }
        boolean changed = dt > 0;
        o.progressSeconds += dt;
        int target = OrderProgress.unitsReady(o.progressSeconds, eff, o.units);
        while (o.unitsDone < target) {
            if (!plugin.power().useStation(plot, def.stationKey())) {
                // нет электричества или денег на операцию: время простоя не накапливается
                o.progressSeconds = Math.min(o.progressSeconds, o.unitsDone * eff);
                break;
            }
            o.unitsDone++;
            changed = true;
        }
        if (o.unitsDone >= o.units) {
            o.state = ServiceOrder.State.READY;
            o.readyAt = System.currentTimeMillis();
            changed = true;
            plugin.service().warn(o.client, o.plotId, "<green>Ваш заказ <white>" + o.shortId() + "</white> ("
                    + Text.esc(def.title()) + ") в мастерской <white>" + Text.esc(o.plotId)
                    + "</white> готов. Заберите: /district service orders");
        }
        return changed;
    }

    // ------------------------------------------------------------ хранение

    public void load() {
        orders.clear();
        if (!file.exists()) {
            return;
        }
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("orders");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            ServiceOrder o = new ServiceOrder(UUID.fromString(key), s.getString("plot"), UUID.fromString(s.getString("client")),
                    s.getString("client-name"), s.getString("offer"), s.getInt("units"), s.getDouble("paid"),
                    s.getLong("created"));
            o.state = ServiceOrder.State.valueOf(s.getString("state", "PENDING"));
            o.unitsDone = s.getInt("done");
            o.progressSeconds = s.getDouble("progress");
            o.readyAt = s.getLong("ready-at");
            for (String b : s.getStringList("input")) {
                o.input.add(Base64.getDecoder().decode(b));
            }
            for (String b : s.getStringList("output")) {
                o.output.add(Base64.getDecoder().decode(b));
            }
            orders.put(o.id, o);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (ServiceOrder o : orders.values()) {
            String k = "orders." + o.id + ".";
            yml.set(k + "plot", o.plotId);
            yml.set(k + "client", o.client.toString());
            yml.set(k + "client-name", o.clientName);
            yml.set(k + "offer", o.offerKey);
            yml.set(k + "units", o.units);
            yml.set(k + "paid", o.paid);
            yml.set(k + "created", o.createdAt);
            yml.set(k + "state", o.state.name());
            yml.set(k + "done", o.unitsDone);
            yml.set(k + "progress", o.progressSeconds);
            yml.set(k + "ready-at", o.readyAt);
            yml.set(k + "input", o.input.stream().map(b -> Base64.getEncoder().encodeToString(b)).toList());
            yml.set(k + "output", o.output.stream().map(b -> Base64.getEncoder().encodeToString(b)).toList());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить orders.yml", e);
        }
    }
}
