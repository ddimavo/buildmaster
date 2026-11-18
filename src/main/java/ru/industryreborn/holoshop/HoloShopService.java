package ru.industryreborn.holoshop;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.holo.ClickZones;
import ru.industryreborn.holo.Holo;
import ru.industryreborn.holo.HoloSpec;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Module;
import ru.industryreborn.util.Text;

/**
 * Торговля через голограммы. Витрина - доска-таблица: иконка, название, цена и остаток каждого товара в своих колонках (каждая ячейка - отдельная
 * сущность, поэтому ничто не перекрывается), сверху заголовок и подписи колонок, по бокам кнопки листания. Доска стоит по направлению витрины;
 * с обратной стороны по умолчанию висит вторая (отключается для отдельной витрины). Клик по товару покупает его (ПКМ - один, ПКМ с Shift - до 16), клик по стрелкам перелистывает страницу.
 * Работает поверх обычных голограмм или FancyHolograms, клики ловят невидимые сущности Interaction.
 */
public final class HoloShopService {

    private final IndustryRebornPlugin plugin;
    private final File file;
    private final Map<String, HoloShop> shops = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldown = new java.util.HashMap<>();
    private LongSupplier clock = System::currentTimeMillis;

    public HoloShopService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "holoshops.yml");
        load();
    }

    /** Только для тестов. */
    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean enabled() {
        return plugin.modules().enabled(Module.HOLO_SHOP) && plugin.modules().enabled(Module.GOODS);
    }

    public List<HoloShop> all() {
        return List.copyOf(shops.values());
    }

    public HoloShop get(String id) {
        return shops.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public List<HoloShop> ofPlot(String plotId) {
        return shops.values().stream().filter(s -> s.plotId.equalsIgnoreCase(plotId)).toList();
    }

    private double cfg(String key, double def) {
        return plugin.getConfig().getDouble("holo-shop." + key, def);
    }

    private int perPage() {
        return Math.max(1, Math.min(8, plugin.getConfig().getInt("holo-shop.items-per-page", 5)));
    }

    // ------------------------------------------------------------ создание и удаление

    /** Создаёт витрину в позиции игрока. Возвращает ошибку или null. */
    public String create(Player player, Plot plot) {
        String off = plugin.modules().refusal(Module.HOLO_SHOP);
        if (off == null) {
            off = plugin.modules().refusal(Module.GOODS);
        }
        if (off != null) {
            return off;
        }
        if (plot == null || !plugin.goods().sells(plot)) {
            return "Витрина создаётся у магазина или мастерской с управляющим.";
        }
        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission("industryreborn.admin")) {
            return "Витрину создаёт управляющий предприятия.";
        }
        Location l = player.getLocation();
        if (!player.hasPermission("industryreborn.admin")
                && (!plot.world.equals(l.getWorld().getName()) || !plot.box.contains(l.getBlockX(), l.getBlockY(), l.getBlockZ()))) {
            return "Витрина должна стоять на территории вашего предприятия.";
        }
        if (ofPlot(plot.id).size() >= plugin.getConfig().getInt("holo-shop.max-per-plot", 2)) {
            return "На предприятии не больше " + plugin.getConfig().getInt("holo-shop.max-per-plot", 2) + " витрин-голограмм.";
        }
        int n = 1;
        while (shops.containsKey((plot.id + "-" + n).toLowerCase(java.util.Locale.ROOT))) {
            n++;
        }
        float yaw = Math.round(l.getYaw() / 90f) * 90f;
        HoloShop shop = new HoloShop((plot.id + "-" + n).toLowerCase(java.util.Locale.ROOT), plot.id, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), yaw);
        shops.put(shop.id, shop);
        save();
        return null;
    }

    /** Удаляет витрину. Возвращает ошибку или null. */
    public String remove(Player player, String id) {
        HoloShop shop = get(id);
        if (shop == null) {
            return "Витрина «" + id + "» не найдена: /district holoshop list";
        }
        Plot plot = plugin.plots().get(shop.plotId);
        boolean mine = plot != null && plot.isOwner(player.getUniqueId());
        if (!mine && !player.hasPermission("industryreborn.admin")) {
            return "Удалять витрину может управляющий предприятия.";
        }
        clear(shop);
        shops.remove(shop.id);
        save();
        return null;
    }

    // ------------------------------------------------------------ показ

    /** Точка на доске: right - вправо от покупателя, dy - вверх от места витрины, depth - к покупателю; back - вид с обратной стороны. */
    private Location at(HoloShop s, World w, double right, double dy, double depth, boolean back) {
        double yaw = Math.toRadians(s.yaw);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double rx = fz;                  // правая рука покупателя, смотрящего на витрину
        double rz = -fx;
        double r = back ? -right : right;
        double d = back ? -depth : depth;
        return new Location(w, s.x + rx * r + fx * d, s.y + dy, s.z + rz * r + fz * d, s.yaw + (back ? 180f : 0f), 0f);
    }

    private Location at(HoloShop s, World w, double right, double dy) {
        return at(s, w, right, dy, 0, false);
    }

    private boolean twoSided(HoloShop s) {
        return s.backSide != null ? s.backSide : plugin.getConfig().getBoolean("holo-shop.two-sided", true);
    }

    /** Включает или выключает доску с обратной стороны у одной витрины (null - как в config.yml). Возвращает ошибку или null. */
    public String setBack(Player player, String id, Boolean value) {
        HoloShop shop = get(id);
        if (shop == null) {
            return "Витрина «" + id + "» не найдена: /district holoshop list";
        }
        Plot plot = plugin.plots().get(shop.plotId);
        if ((plot == null || !plot.isOwner(player.getUniqueId())) && !player.hasPermission("industryreborn.admin")) {
            return "Настраивать витрину может управляющий предприятия.";
        }
        shop.backSide = value;
        clear(shop);
        save();
        return null;
    }

    private void clear(HoloShop s) {
        s.parts.forEach(Holo::remove);
        s.zones.forEach(ClickZones.Zone::remove);
        s.parts.clear();
        s.zones.clear();
        s.refresh.clear();
        s.pageItems.clear();
        s.layoutKey = "";
    }

    public void removeAll() {
        for (HoloShop s : shops.values()) {
            clear(s);
        }
    }

    /** Раз в секунду: показывает витрины рядом с игроками, обновляет остатки и цены, убирает дальние. */
    public void tick() {
        if (!enabled()) {
            removeAll();
            return;
        }
        double view = cfg("view-distance", 20);
        long now = clock.getAsLong();
        for (HoloShop s : shops.values()) {
            Plot plot = plugin.plots().get(s.plotId);
            World world = Bukkit.getWorld(s.world);
            if (plot == null || world == null || !plugin.goods().sells(plot) || !world.isChunkLoaded((int) Math.floor(s.x) >> 4, (int) Math.floor(s.z) >> 4)) {
                clear(s);
                continue;
            }
            Location base = new Location(world, s.x, s.y, s.z);
            boolean viewer = false;
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(base) <= view * view) {
                    viewer = true;
                    break;
                }
            }
            if (!viewer) {
                clear(s);
                continue;
            }
            if (s.page != 0 && now - s.lastClick > (long) (cfg("page-reset-seconds", 60) * 1000)) {
                s.page = 0;
            }
            render(s, plot, world);
        }
    }

    private List<Material> items(Plot plot) {
        return plugin.goods().listed(plot);
    }

    private static String name(Material m) {
        return "<lang:" + new ItemStack(m).translationKey() + ">";
    }

    private String priceText(Plot plot, Material m) {
        return "<yellow>" + plugin.economy().format(plugin.goods().price(plot, m));
    }

    private String stockText(Plot plot, Material m) {
        int stock = plugin.goods().stock(plot, m);
        return stock <= 0 ? "<red>Нет" : stock <= 10 ? "<gold>" + stock : "<green>" + stock;
    }

    private String titleText(HoloShop s, Plot plot, int pages) {
        return "<gold><bold>" + Text.esc(plot.displayName()) + "</bold>\n<gray>Страница " + (s.page + 1) + " из " + pages;
    }

    private void render(HoloShop s, Plot plot, World world) {
        List<Material> all = items(plot);
        int per = perPage();
        int pages = Math.max(1, (all.size() + per - 1) / per);
        s.page = Math.max(0, Math.min(s.page, pages - 1));
        List<Material> page = all.subList(Math.min(all.size(), s.page * per), Math.min(all.size(), s.page * per + per));
        String key = s.page + "|" + page + "|" + pages + "|" + twoSided(s);
        boolean valid = s.shown() && s.parts.stream().allMatch(Holo::valid) && s.zones.stream().allMatch(ClickZones.Zone::valid);
        if (!valid || !key.equals(s.layoutKey)) {
            clear(s);
            build(s, plot, world, page, pages);
            s.layoutKey = key;
            return;
        }
        // раскладка прежняя: обновляем только тексты (остатки, цены, заголовок)
        s.refresh.forEach(Runnable::run);
    }

    // Раскладка доски (блоки вправо от центра). Каждая ячейка - отдельная сущность с собственным фоном и фиксированной шириной,
    // поэтому длинное название или цена переносится внутри своей колонки и не наезжает на соседние.
    private static final double COL_ICON = -1.3;
    private static final double COL_NAME = -0.6;
    private static final double COL_PRICE = 0.45;
    private static final double COL_STOCK = 1.2;
    private static final double W_NAME = 1.2;
    private static final double W_PRICE = 0.9;
    private static final double W_STOCK = 0.55;
    private static final double BOARD = 3.0;
    private static final double BOTTOM = 1.1;
    private static final double DEPTH = 0.02;

    /** Ширина колонки в пикселях текста при данном масштабе (один пиксель - 0.025 блока). */
    private static int px(double blocks, double textScale) {
        return Math.max(10, (int) Math.round(blocks / (0.025 * textScale)));
    }

    private void build(HoloShop s, Plot plot, World world, List<Material> page, int pages) {
        float range = (float) (cfg("view-distance", 20) / 64.0);
        int bg = 0x78000000;
        double ts = Math.max(0.4, Math.min(1.5, cfg("text-scale", 0.7)));
        double rowH = Math.max(0.3, cfg("row-height", 0.42));
        float tsf = (float) ts;
        double line = 0.275 * ts;                         // высота одной строки текста
        int n = Math.max(1, page.size());
        double top = BOTTOM + n * rowH;
        boolean[] sides = twoSided(s) ? new boolean[] {false, true} : new boolean[] {false};
        for (boolean back : sides) {
            String tag = "hs_" + s.id + (back ? "b" : "f");
            // заголовок
            Location titleAt = at(s, world, 0, top + 0.5, DEPTH, back);
            Holo title = plugin.holoFactory().spawn(tag + "_h", new HoloSpec(titleAt, titleText(s, plot, pages))
                    .billboard(org.bukkit.entity.Display.Billboard.FIXED).style(260, range, bg));
            s.parts.add(title);
            s.refresh.add(() -> title.text(titleText(s, plot, pages)));
            if (page.isEmpty()) {
                s.parts.add(plugin.holoFactory().spawn(tag + "_e", cell(at(s, world, 0, BOTTOM + 0.15, DEPTH, back), "<gray>Товаров пока нет", 220, range, bg, tsf)));
                continue;
            }
            // заголовки колонок
            double hy = top + 0.1;
            s.parts.add(plugin.holoFactory().spawn(tag + "_c0", cell(at(s, world, COL_NAME, hy, DEPTH, back), "<gray>Товар", px(W_NAME, ts), range, bg, tsf)));
            s.parts.add(plugin.holoFactory().spawn(tag + "_c1", cell(at(s, world, COL_PRICE, hy, DEPTH, back), "<gray>Цена", px(W_PRICE, ts), range, bg, tsf)));
            s.parts.add(plugin.holoFactory().spawn(tag + "_c2", cell(at(s, world, COL_STOCK, hy, DEPTH, back), "<gray>Остаток", px(W_STOCK, ts), range, bg, tsf)));
            for (int i = 0; i < page.size(); i++) {
                Material m = page.get(i);
                double center = BOTTOM + (page.size() - 1 - i + 0.5) * rowH;
                double ty = center - line / 2;
                s.parts.add(plugin.holoFactory().spawnItem(tag + "_i" + i, at(s, world, COL_ICON, center, DEPTH, back), new ItemStack(m),
                        (float) (rowH * 0.85), range, true));
                s.parts.add(plugin.holoFactory().spawn(tag + "_n" + i, cell(at(s, world, COL_NAME, ty, DEPTH, back), "<white>" + name(m), px(W_NAME, ts), range, bg, tsf)));
                Holo price = plugin.holoFactory().spawn(tag + "_p" + i, cell(at(s, world, COL_PRICE, ty, DEPTH, back), priceText(plot, m), px(W_PRICE, ts), range, bg, tsf));
                Holo stock = plugin.holoFactory().spawn(tag + "_s" + i, cell(at(s, world, COL_STOCK, ty, DEPTH, back), stockText(plot, m), px(W_STOCK, ts), range, bg, tsf));
                s.parts.add(price);
                s.parts.add(stock);
                s.refresh.add(() -> {
                    price.text(priceText(plot, m));
                    stock.text(stockText(plot, m));
                });
            }
            s.parts.add(plugin.holoFactory().spawn(tag + "_f", cell(at(s, world, 0, BOTTOM - 0.3, DEPTH, back),
                    "<dark_gray>ПКМ - купить 1 · Shift+ПКМ - до 16", 300, range, bg, tsf)));
            if (pages > 1) {
                s.parts.add(plugin.holoFactory().spawn(tag + "_pv", new HoloSpec(at(s, world, -BOARD / 2 - 0.3, BOTTOM + n * rowH / 2 - 0.15, DEPTH, back), "<green><bold>◀")
                        .billboard(org.bukkit.entity.Display.Billboard.FIXED).style(100, range, bg).scale(1.4f)));
                s.parts.add(plugin.holoFactory().spawn(tag + "_nx", new HoloSpec(at(s, world, BOARD / 2 + 0.3, BOTTOM + n * rowH / 2 - 0.15, DEPTH, back), "<green><bold>▶")
                        .billboard(org.bukkit.entity.Display.Billboard.FIXED).style(100, range, bg).scale(1.4f)));
            }
        }
        // зоны кликов общие для обеих досок: квадрат шириной с доску охватывает обе стороны
        double zoneW = BOARD;
        for (int i = 0; i < page.size(); i++) {
            Material m = page.get(i);
            double center = BOTTOM + (page.size() - 1 - i + 0.5) * rowH;
            s.pageItems.add(m);
            s.zones.add(plugin.clickZones().create(at(s, world, 0, center - rowH / 2), zoneW, rowH, (p, sneak, left) -> clickItem(s, m, p, sneak, left)));
        }
        if (pages > 1) {
            double ay = BOTTOM + n * rowH / 2 - 0.45;
            for (int dir : new int[] {-1, 1}) {
                double x = dir * (BOARD / 2 + 0.3);
                // одна зона на место стрелки: с обратной стороны доска зеркальная, поэтому направление считается по тому, с какой стороны стоит игрок
                s.zones.add(plugin.clickZones().create(at(s, world, x, ay), 0.8, 0.9, (p, sneak, left) -> flip(s, behind(s, p) ? -dir : dir, p)));
            }
        }
    }

    /** Игрок стоит позади витрины (смотрит на доску с обратной стороны). */
    private boolean behind(HoloShop s, Player p) {
        double yaw = Math.toRadians(s.yaw);
        Location l = p.getLocation();
        return twoSided(s) && (l.getX() - s.x) * -Math.sin(yaw) + (l.getZ() - s.z) * Math.cos(yaw) < 0;
    }

    private HoloSpec cell(Location at, String text, int lineWidth, float range, int bg, float scale) {
        return new HoloSpec(at, text).billboard(org.bukkit.entity.Display.Billboard.FIXED).style(lineWidth, range, bg).scale(scale);
    }

    // ------------------------------------------------------------ клики

    private boolean tooFast(Player p) {
        long now = clock.getAsLong();
        Long last = cooldown.get(p.getUniqueId());
        if (last != null && now - last < (long) cfg("click-cooldown-ms", 350)) {
            return true;
        }
        cooldown.put(p.getUniqueId(), now);
        return false;
    }

    /** Листание: shift - сразу на первую или последнюю страницу не требуется, шаг одна страница по кругу. */
    void flip(HoloShop s, int dir, Player p) {
        if (!enabled() || tooFast(p)) {
            return;
        }
        Plot plot = plugin.plots().get(s.plotId);
        if (plot == null) {
            return;
        }
        int pages = Math.max(1, (items(plot).size() + perPage() - 1) / perPage());
        s.page = Math.floorMod(s.page + dir, pages);
        s.lastClick = clock.getAsLong();
        World world = Bukkit.getWorld(s.world);
        if (world != null) {
            render(s, plot, world);
        }
    }

    void clickItem(HoloShop s, Material m, Player p, boolean sneaking, boolean left) {
        if (left || !enabled() || tooFast(p)) {
            return;
        }
        Plot plot = plugin.plots().get(s.plotId);
        if (plot == null) {
            return;
        }
        if (!p.hasPermission("industryreborn.use")) {
            Text.err(p, "Недостаточно прав.");
            return;
        }
        s.lastClick = clock.getAsLong();
        int stock = plugin.goods().stock(plot, m);
        int amount = sneaking ? Math.min(16, Math.max(1, stock)) : 1;
        String error = plugin.goods().buy(p, plot, m, amount);
        if (error != null) {
            Text.err(p, error);
        }
        World world = Bukkit.getWorld(s.world);
        if (world != null) {
            render(s, plot, world);
        }
    }

    /** Для тестов и команд: нажать на товар или стрелку витрины. */
    public boolean press(String shopId, int slot, Player player, boolean sneaking) {
        HoloShop s = get(shopId);
        if (s == null) {
            return false;
        }
        if (slot == -1 || slot == -2) {
            flip(s, slot == -1 ? -1 : 1, player);
            return true;
        }
        if (slot < 0 || slot >= s.pageItems.size()) {
            return false;
        }
        clickItem(s, s.pageItems.get(slot), player, sneaking, false);
        return true;
    }

    // ------------------------------------------------------------ хранение

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("shops");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s != null) {
                HoloShop shop = new HoloShop(id, s.getString("plot", ""), s.getString("world", "world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                        (float) s.getDouble("yaw"));
                if (s.isBoolean("back")) {
                    shop.backSide = s.getBoolean("back");
                }
                shops.put(id, shop);
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (HoloShop s : shops.values()) {
            String k = "shops." + s.id + ".";
            yml.set(k + "plot", s.plotId);
            yml.set(k + "world", s.world);
            yml.set(k + "x", s.x);
            yml.set(k + "y", s.y);
            yml.set(k + "z", s.z);
            yml.set(k + "yaw", s.yaw);
            if (s.backSide != null) {
                yml.set(k + "back", s.backSide);
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить holoshops.yml: " + e.getMessage());
        }
    }

    /** Убирает витрины участка (предприятие закрыто). */
    public void removeOfPlot(String plotId) {
        boolean any = false;
        for (HoloShop s : new ArrayList<>(ofPlot(plotId))) {
            clear(s);
            shops.remove(s.id);
            any = true;
        }
        if (any) {
            save();
        }
    }
}
