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
 * Торговля через голограммы. Витрина стоит в мире: над ней висят иконки товаров предприятия, под ними текст (название, цена, остаток),
 * по бокам кнопки листания. Клик по товару покупает его (ПКМ - один, ПКМ с Shift - до 16), клик по стрелкам перелистывает страницу.
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
        return Math.max(1, Math.min(5, plugin.getConfig().getInt("holo-shop.items-per-page", 3)));
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

    private Location at(HoloShop s, World w, double right, double dy) {
        double yaw = Math.toRadians(s.yaw);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double rx = fz;                  // правая рука покупателя, смотрящего на витрину
        double rz = -fx;
        return new Location(w, s.x + rx * right, s.y + dy, s.z + rz * right);
    }

    private void clear(HoloShop s) {
        s.parts.forEach(Holo::remove);
        s.zones.forEach(ClickZones.Zone::remove);
        s.parts.clear();
        s.zones.clear();
        s.texts.clear();
        s.pageItems.clear();
        s.header = null;
        s.footer = null;
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

    private String itemText(Plot plot, Material m) {
        int stock = plugin.goods().stock(plot, m);
        return "<white>" + name(m) + "\n<yellow>" + plugin.economy().format(plugin.goods().price(plot, m)) + "\n"
                + (stock > 0 ? "<gray>В наличии: <white>" + stock : "<red>Нет в наличии");
    }

    private String headerText(HoloShop s, Plot plot, int pages) {
        return "<gold><bold>" + Text.esc(plot.displayName()) + "</bold>\n<gray>Страница " + (s.page + 1) + " из " + pages
                + "\n<dark_gray>ПКМ - купить 1, Shift+ПКМ - до 16";
    }

    private void render(HoloShop s, Plot plot, World world) {
        List<Material> all = items(plot);
        int per = perPage();
        int pages = Math.max(1, (all.size() + per - 1) / per);
        s.page = Math.max(0, Math.min(s.page, pages - 1));
        List<Material> page = all.subList(Math.min(all.size(), s.page * per), Math.min(all.size(), s.page * per + per));
        String key = s.page + "|" + page + "|" + pages;
        boolean valid = s.shown() && s.parts.stream().allMatch(Holo::valid) && s.zones.stream().allMatch(ClickZones.Zone::valid);
        if (!valid || !key.equals(s.layoutKey)) {
            clear(s);
            build(s, plot, world, page, pages);
            s.layoutKey = key;
            return;
        }
        // раскладка прежняя: обновляем только тексты (остатки, цены, заголовок)
        s.header.text(headerText(s, plot, pages));
        for (int i = 0; i < page.size(); i++) {
            s.texts.get(i).text(itemText(plot, page.get(i)));
        }
    }

    private void build(HoloShop s, Plot plot, World world, List<Material> page, int pages) {
        float range = (float) (cfg("view-distance", 20) / 64.0);
        int bg = 0x78000000;
        s.header = plugin.holoFactory().spawn("hs_" + s.id + "_h", new HoloSpec(at(s, world, 0, 2.7), headerText(s, plot, pages)).style(240, range, bg));
        s.parts.add(s.header);
        double spacing = cfg("spacing", 1.3);
        if (page.isEmpty()) {
            s.parts.add(plugin.holoFactory().spawn("hs_" + s.id + "_e", new HoloSpec(at(s, world, 0, 1.6), "<gray>Товаров пока нет").style(200, range, bg)));
        }
        for (int i = 0; i < page.size(); i++) {
            Material m = page.get(i);
            double off = (i - (page.size() - 1) / 2.0) * spacing;
            s.parts.add(plugin.holoFactory().spawnItem("hs_" + s.id + "_i" + i, at(s, world, off, 1.9), new ItemStack(m), 0.8f, range));
            Holo text = plugin.holoFactory().spawn("hs_" + s.id + "_t" + i, new HoloSpec(at(s, world, off, 1.15), itemText(plot, m)).style(200, range, bg));
            s.parts.add(text);
            s.texts.add(text);
            s.pageItems.add(m);
            s.zones.add(plugin.clickZones().create(at(s, world, off, 0.7), 1.1, 1.6, (p, sneak, left) -> clickItem(s, m, p, sneak, left)));
        }
        if (pages > 1) {
            s.parts.add(plugin.holoFactory().spawn("hs_" + s.id + "_p", new HoloSpec(at(s, world, -1.2, 0.5), "<green><bold>◀").style(100, range, bg).scale(1.4f)));
            s.parts.add(plugin.holoFactory().spawn("hs_" + s.id + "_n", new HoloSpec(at(s, world, 1.2, 0.5), "<green><bold>▶").style(100, range, bg).scale(1.4f)));
            s.zones.add(plugin.clickZones().create(at(s, world, -1.2, 0.1), 0.8, 0.9, (p, sneak, left) -> flip(s, -1, p)));
            s.zones.add(plugin.clickZones().create(at(s, world, 1.2, 0.1), 0.8, 0.9, (p, sneak, left) -> flip(s, 1, p)));
        }
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
                shops.put(id, new HoloShop(id, s.getString("plot", ""), s.getString("world", "world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                        (float) s.getDouble("yaw")));
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
