package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.holoshop.HoloShop;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Module;
import ru.industryreborn.support.PluginTestBase;

/** Торговля через голограммы: показ, листание, покупка, права, отключение функций и FancyHolograms. */
class HoloShopTest extends PluginTestBase {

    /** Поддельная сущность (TextDisplay, ItemDisplay, Interaction): помнит текст, предмет и положение. */
    private static final class Fake implements java.lang.reflect.InvocationHandler {
        final UUID id = UUID.randomUUID();
        boolean valid = true;
        String text = "";
        ItemStack item;
        Location at;
        double[] size;
        int lineWidth;

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) {
            switch (m.getName()) {
                case "isValid":
                    return valid;
                case "remove":
                    valid = false;
                    return null;
                case "getUniqueId":
                    return id;
                case "text":
                    if (a != null && a.length == 1) {
                        text = PlainTextComponentSerializer.plainText().serialize((net.kyori.adventure.text.Component) a[0]);
                        return null;
                    }
                    return null;
                case "setLineWidth":
                    lineWidth = (Integer) a[0];
                    return null;
                case "setItemStack":
                    item = (ItemStack) a[0];
                    return null;
                case "teleport":
                    at = (Location) a[0];
                    return true;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == a[0];
                default:
                    return m.getReturnType() == boolean.class ? (Object) false : null;
            }
        }
    }

    private final List<Fake> texts = new ArrayList<>();
    private final List<Fake> icons = new ArrayList<>();
    private final List<Fake> zones = new ArrayList<>();
    private final Map<UUID, Fake> byId = new HashMap<>();
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private PlayerMock steve;
    private PlayerMock mia;
    private PlayerMock boss;
    private Plot shop;

    private <T> T proxy(Class<T> type, Fake f) {
        byId.put(f.id, f);
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, f));
    }

    private List<Fake> alive(List<Fake> list) {
        return list.stream().filter(f -> f.valid).toList();
    }

    @BeforeEach
    void setUp() {
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.shop", 0);
        plugin.getConfig().set("goods.sales-tax-percent", 0);
        plugin.getConfig().set("holo-shop.two-sided", false);        // двусторонняя доска проверяется отдельными тестами
        plugin.getConfig().set("holo-shop.items-per-page", 3);
        server.getWorld("world").loadChunk(0, 0);
        plugin.holoFactory().setSpawner((at, init) -> {
            Fake f = new Fake();
            f.at = at;
            TextDisplay d = proxy(TextDisplay.class, f);
            init.accept(d);
            texts.add(f);
            return d;
        });
        plugin.holoFactory().setItemSpawner((at, init) -> {
            Fake f = new Fake();
            f.at = at;
            ItemDisplay d = proxy(ItemDisplay.class, f);
            init.accept(d);
            icons.add(f);
            return d;
        });
        plugin.clickZones().setSpawner((at, size) -> {
            Fake f = new Fake();
            f.at = at;
            f.size = size;
            zones.add(f);
            return proxy(Entity.class, f);
        });
        plugin.holoShops().setClock(clock::get);
        steve = player("Steve", 100_000);
        mia = player("Mia", 1_000);
        boss = admin("Boss");
        shop = addPlot("s", Category.SHOP, 0, -1, 10);
        shop.name = "Лавка";
        assertTrue(plugin.service().rent(steve, shop, 5));
        stock(Material.IRON_INGOT, 50, 10);
        stock(Material.GOLD_INGOT, 5, 25);
        stock(Material.DIAMOND, 0, 100);
        steve.teleport(new Location(server.getWorld("world"), 3, 1, 3));
        mia.teleport(new Location(server.getWorld("world"), 3, 1, 5));
        boss.teleport(new Location(server.getWorld("world"), 400, 1, 400));
        steve.teleport(new Location(server.getWorld("world"), 3, 1, 3, 0f, 0f));
    }

    private void stock(Material m, int n, double price) {
        shop.goods.put(m, n);
        shop.prices.put(m, price);
    }

    private HoloShop make() {
        assertNull(plugin.holoShops().create(steve, shop));
        return plugin.holoShops().all().get(0);
    }

    private String all(List<Fake> list) {
        StringBuilder sb = new StringBuilder();
        alive(list).forEach(f -> sb.append(f.text).append('\n'));
        return sb.toString();
    }

    // ------------------------------------------------------------ создание

    @Test
    void theManagerCreatesAShopInsideTheirPlot() {
        HoloShop s = make();
        assertEquals("s-1", s.id);
        assertEquals("s", s.plotId);
        assertEquals(0f, s.yaw, 0);
        steve.teleport(new Location(server.getWorld("world"), 3, 1, 3, 100f, 0f));
        assertNull(plugin.holoShops().create(steve, shop));
        assertEquals(90f, plugin.holoShops().get("s-2").yaw, 0, "направление округляется до 90°");
    }

    @Test
    void creationRulesAreChecked() {
        assertNotNull(plugin.holoShops().create(mia, shop), "чужой предприятие");
        mia.teleport(new Location(server.getWorld("world"), 3, 1, 3));
        assertNotNull(plugin.holoShops().create(mia, shop));
        steve.teleport(new Location(server.getWorld("world"), 300, 1, 300));
        assertNotNull(plugin.holoShops().create(steve, shop), "вне участка");
        steve.teleport(new Location(server.getWorld("world"), 3, 1, 3));
        Plot house = addPlot("h", Category.HOUSE, 30, 100, 10);
        assertNotNull(plugin.holoShops().create(boss, house), "дом ничего не продаёт");
        assertNull(plugin.holoShops().create(boss, shop), "администратор может где угодно");
        assertNull(plugin.holoShops().create(steve, shop));
        assertNotNull(plugin.holoShops().create(steve, shop), "не больше двух на предприятие");
    }

    @Test
    void commandsCreateListAndRemove() {
        steve.performCommand("district holoshop create s");
        assertEquals(1, plugin.holoShops().all().size());
        steve.performCommand("district holoshop list");
        mia.performCommand("district holoshop remove s-1");
        assertEquals(1, plugin.holoShops().all().size(), "чужому нельзя");
        steve.performCommand("district holoshop remove s-1");
        assertTrue(plugin.holoShops().all().isEmpty());
    }

    @Test
    void shopsSurviveARestart() {
        make();
        var fresh = new ru.industryreborn.holoshop.HoloShopService(plugin);
        assertEquals(1, fresh.all().size());
        assertEquals("s", fresh.get("s-1").plotId);
        assertEquals(3.0, fresh.get("s-1").x, 1e-9);
    }

    // ------------------------------------------------------------ показ

    @Test
    void aShopAppearsNearPlayersWithIconsTextsAndClickZones() {
        make();
        plugin.holoShops().tick();
        assertEquals(3, alive(icons).size(), "три товара на странице");
        assertEquals(Material.DIAMOND, alive(icons).get(0).item.getType(), "по алфавиту");
        String text = all(texts);
        assertTrue(text.contains("Лавка") && text.contains("Страница 1 из 1"), text);
        assertTrue(text.contains("Товар") && text.contains("Цена") && text.contains("Остаток"), text);
        assertTrue(text.contains("50") && text.contains("Нет"), text);
        assertEquals(3, alive(zones).size());
        assertTrue(alive(zones).get(0).size[0] > 0);
    }

    @Test
    void rowsAreStackedAndEveryCellIsItsOwnEntity() {
        make();
        plugin.holoShops().tick();
        var ys = alive(icons).stream().map(f -> f.at.getY()).toList();
        assertEquals(0.42, ys.get(0) - ys.get(1), 1e-9, "строки идут сверху вниз с шагом row-height");
        assertEquals(0.42, ys.get(1) - ys.get(2), 1e-9);
        assertEquals(1, alive(icons).stream().map(f -> f.at.getX()).distinct().count(), "иконки в одной колонке");
        // 3 товара: заголовок, 3 подписи колонок, 3 x (название, цена, остаток), подсказка
        assertEquals(1 + 3 + 9 + 1, alive(texts).size());
    }

    @Test
    void columnsNeverOverlapEachOther() {
        make();
        plugin.holoShops().tick();
        double ts = plugin.getConfig().getDouble("holo-shop.text-scale", 0.7);
        var cells = alive(texts).subList(4, 7);            // первая строка: название, цена, остаток
        double[] left = new double[3];
        double[] right = new double[3];
        for (int i = 0; i < 3; i++) {
            double half = cells.get(i).lineWidth * 0.025 * ts / 2;
            left[i] = cells.get(i).at.getX() - half;
            right[i] = cells.get(i).at.getX() + half;
        }
        assertTrue(right[0] <= left[1] + 1e-9, "название не заходит на цену");
        assertTrue(right[1] <= left[2] + 1e-9, "цена не заходит на остаток");
        assertTrue(left[0] > icons.get(0).at.getX(), "название правее иконки");
    }

    @Test
    void theBackBoardMirrorsTheFrontAndCanBeSwitchedOffPerShop() {
        plugin.getConfig().set("holo-shop.two-sided", true);
        HoloShop s = make();
        plugin.holoShops().tick();
        assertEquals(6, alive(icons).size(), "доска с двух сторон");
        assertEquals(3, alive(zones).size(), "зоны кликов общие");
        assertEquals(0f, icons.get(0).at.getYaw(), 0);
        assertEquals(180f, icons.get(3).at.getYaw(), 0, "обратная сторона смотрит назад");
        assertTrue(icons.get(3).at.getX() > 3.0 && icons.get(0).at.getX() < 3.0, "иконки зеркально по разные стороны от центра");
        assertNotNull(plugin.holoShops().setBack(mia, s.id, false), "чужой управляющий не настраивает");
        assertNull(plugin.holoShops().setBack(steve, s.id, false));
        plugin.holoShops().tick();
        assertEquals(3, alive(icons).size(), "обратная доска отключена только у этой витрины");
        assertEquals(Boolean.FALSE, plugin.holoShops().get(s.id).backSide());
        assertEquals(Boolean.FALSE, new ru.industryreborn.holoshop.HoloShopService(plugin).get(s.id).backSide(), "настройка сохраняется");
        assertNull(plugin.holoShops().setBack(steve, s.id, null));
        plugin.holoShops().tick();
        assertEquals(6, alive(icons).size(), "default - как в config.yml");
        assertNotNull(plugin.holoShops().setBack(steve, "нет-такой", true));
    }

    @Test
    void theBackCommandAndArrowsSeenFromBehind() {
        plugin.getConfig().set("holo-shop.two-sided", true);
        stock(Material.COAL, 10, 1);
        stock(Material.COPPER_INGOT, 10, 1);
        stock(Material.EMERALD, 10, 1);
        stock(Material.LAPIS_LAZULI, 10, 1);
        stock(Material.QUARTZ, 10, 1);        // 3 страницы: направление листания однозначно
        HoloShop s = make();
        plugin.holoShops().tick();
        assertEquals(3 + 2, alive(zones).size(), "три строки и две стрелки");
        Fake next = alive(zones).get(4);                   // правая зона: место ▶ спереди и ◀ сзади
        assertTrue(next.at.getX() > 3.0);
        assertTrue(plugin.clickZones().dispatch(next.id, mia, false, false));       // mia стоит спереди (z=5, витрина смотрит на +z)
        assertEquals(1, s.page(), "спереди правая стрелка листает вперёд");
        clock.addAndGet(500);
        mia.teleport(new Location(server.getWorld("world"), 3, 1, 1));              // позади доски
        var zone = alive(zones).get(alive(zones).size() - 1);   // правая стрелка последняя
        assertTrue(plugin.clickZones().dispatch(zone.id, mia, false, false));
        assertEquals(0, s.page(), "сзади та же зона листает назад: доска зеркальная");
        steve.performCommand("district holoshop back s-1 off");
        assertEquals(Boolean.FALSE, plugin.holoShops().get("s-1").backSide());
        steve.performCommand("district holoshop back s-1 default");
        assertNull(plugin.holoShops().get("s-1").backSide());
        steve.performCommand("district holoshop back s-1 maybe");
        assertNull(plugin.holoShops().get("s-1").backSide(), "неверное значение ничего не меняет");
    }

    @Test
    void farFromEveryoneNothingIsShown() {
        make();
        steve.teleport(new Location(server.getWorld("world"), 500, 1, 500));
        mia.teleport(new Location(server.getWorld("world"), 500, 1, 500));
        plugin.holoShops().tick();
        assertTrue(alive(icons).isEmpty() && alive(texts).isEmpty() && alive(zones).isEmpty());
        steve.teleport(new Location(server.getWorld("world"), 3, 1, 3));
        plugin.holoShops().tick();
        assertFalse(alive(icons).isEmpty());
        assertEquals(0, plugin.clickZones().count() - alive(zones).size());
    }

    @Test
    void noGoodsShowsAMessage() {
        shop.goods.clear();
        shop.prices.clear();
        make();
        plugin.holoShops().tick();
        assertTrue(all(texts).contains("Товаров пока нет"));
        assertTrue(alive(zones).isEmpty());
    }

    @Test
    void textsFollowStockAndPriceWithoutRebuilding() {
        make();
        plugin.holoShops().tick();
        int icons1 = icons.size();
        shop.goods.put(Material.IRON_INGOT, 49);
        shop.prices.put(Material.IRON_INGOT, 12.0);
        plugin.holoShops().tick();
        assertEquals(icons1, icons.size(), "иконки не пересоздавались");
        assertTrue(alive(texts).stream().anyMatch(f -> f.text.equals("49")), all(texts));
        assertTrue(all(texts).contains("12"));
    }

    // ------------------------------------------------------------ листание

    @Test
    void paginationWrapsAroundAndResetsWhenIdle() {
        stock(Material.COAL, 10, 1);
        stock(Material.COPPER_INGOT, 10, 1);
        HoloShop s = make();
        plugin.holoShops().tick();
        assertTrue(all(texts).contains("Страница 1 из 2"));
        assertEquals(3, alive(icons).size());
        mia.performCommand("district menu");
        assertTrue(plugin.holoShops().press(s.id, -2, mia, false));      // вперёд
        assertTrue(all(texts).contains("Страница 2 из 2"), all(texts));
        assertEquals(2, alive(icons).size(), "на второй странице два товара");
        clock.addAndGet(500);
        plugin.holoShops().press(s.id, -2, mia, false);
        assertTrue(all(texts).contains("Страница 1 из 2"), "по кругу");
        clock.addAndGet(500);
        plugin.holoShops().press(s.id, -1, mia, false);                  // назад
        assertTrue(all(texts).contains("Страница 2 из 2"));
        clock.addAndGet(61_000);
        plugin.holoShops().tick();
        assertTrue(all(texts).contains("Страница 1 из 2"), "без кликов вернулась на первую");
    }

    @Test
    void arrowsExistOnlyWhenThereAreSeveralPages() {
        make();
        plugin.holoShops().tick();
        assertEquals(3, alive(zones).size());
        stock(Material.COAL, 10, 1);
        plugin.holoShops().tick();
        assertEquals(3 + 2, alive(zones).size(), "появились две стрелки");
        assertTrue(all(texts).contains("◀") && all(texts).contains("▶"));
    }

    // ------------------------------------------------------------ покупка

    @Test
    void clickingAnItemBuysOneAndTheTextUpdates() {
        HoloShop s = make();
        plugin.holoShops().tick();
        double before = economy.getBalance(mia);
        assertTrue(plugin.holoShops().press(s.id, 1, mia, false));       // GOLD_INGOT (по алфавиту: DIAMOND, GOLD_INGOT, IRON_INGOT)
        assertEquals(before - 25, economy.getBalance(mia), 1e-9);
        assertEquals(4, shop.goods.get(Material.GOLD_INGOT));
        assertTrue(alive(texts).stream().anyMatch(f -> f.text.equals("4")), all(texts));
        assertEquals(25, shop.account.rawBalance() - 0 >= 25 ? 25 : 0, 1e-9);
    }

    @Test
    void sneakingBuysUpToSixteenAndNeverMoreThanTheStock() {
        HoloShop s = make();
        plugin.holoShops().tick();
        plugin.holoShops().press(s.id, 2, mia, true);                     // IRON_INGOT: 50 в наличии, цена 10 - 16 штук стоят 160
        assertEquals(34, shop.goods.get(Material.IRON_INGOT));
        clock.addAndGet(500);
        plugin.holoShops().press(s.id, 1, mia, true);                     // GOLD_INGOT: осталось 5 - покупаем 5 за 125
        assertEquals(0, shop.goods.getOrDefault(Material.GOLD_INGOT, 0));
    }

    @Test
    void purchaseProblemsAreReportedAndChangeNothing() {
        HoloShop s = make();
        plugin.holoShops().tick();
        mia.getInventory().clear();
        String before = String.valueOf(economy.getBalance(mia));
        assertTrue(plugin.holoShops().press(s.id, 0, mia, false));        // DIAMOND: нет в наличии
        assertEquals(before, String.valueOf(economy.getBalance(mia)));
        economy.set(mia, 5);
        clock.addAndGet(500);
        plugin.holoShops().press(s.id, 2, mia, false);                    // не хватает денег
        assertEquals(50, shop.goods.get(Material.IRON_INGOT));
        clock.addAndGet(500);
        plugin.holoShops().press(s.id, 2, steve, false);                  // хозяин у себя не покупает
        assertEquals(50, shop.goods.get(Material.IRON_INGOT));
    }

    @Test
    void doubleClicksAreIgnoredByTheCooldown() {
        HoloShop s = make();
        plugin.holoShops().tick();
        plugin.holoShops().press(s.id, 2, mia, false);
        plugin.holoShops().press(s.id, 2, mia, false);
        assertEquals(49, shop.goods.get(Material.IRON_INGOT), "второй клик в пределах 350 мс проигнорирован");
        clock.addAndGet(400);
        plugin.holoShops().press(s.id, 2, mia, false);
        assertEquals(48, shop.goods.get(Material.IRON_INGOT));
    }

    @Test
    void realClicksOnTheInvisibleZonesWork() {
        HoloShop s = make();
        plugin.holoShops().tick();
        Fake zone = alive(zones).get(2);        // IRON_INGOT
        assertTrue(plugin.clickZones().dispatch(zone.id, mia, false, false));
        assertEquals(49, shop.goods.get(Material.IRON_INGOT));
        clock.addAndGet(500);
        assertTrue(plugin.clickZones().dispatch(zone.id, mia, false, true));
        assertEquals(49, shop.goods.get(Material.IRON_INGOT), "левая кнопка ничего не покупает");
        assertFalse(plugin.clickZones().dispatch(UUID.randomUUID(), mia, false, false), "чужая сущность не наша");
        assertNotNull(s);
    }

    // ------------------------------------------------------------ функции и закрытие

    @Test
    void theShopIsBlockedWhenItsFeatureOrGoodsAreOff() {
        HoloShop s = make();
        plugin.holoShops().tick();
        assertFalse(alive(icons).isEmpty());
        plugin.modules().set(Module.HOLO_SHOP, false, "Boss");
        plugin.holoShops().tick();
        assertTrue(alive(icons).isEmpty() && alive(zones).isEmpty() && alive(texts).isEmpty(), "витрина убрана");
        plugin.holoShops().press(s.id, 2, mia, false);
        assertEquals(50, shop.goods.get(Material.IRON_INGOT), "купить нельзя, пока функция выключена");
        assertNotNull(plugin.holoShops().create(boss, shop), "создавать тоже нельзя, даже администратору");
        boss.performCommand("district holoshop list");
        plugin.modules().set(Module.HOLO_SHOP, true, "Boss");
        plugin.modules().set(Module.GOODS, false, "Boss");
        plugin.holoShops().tick();
        assertTrue(alive(icons).isEmpty(), "без товаров функции витрина тоже выключена");
    }

    @Test
    void closingTheBusinessRemovesItsShops() {
        make();
        plugin.holoShops().tick();
        plugin.service().release(steve, shop);
        assertTrue(plugin.holoShops().all().isEmpty());
        assertTrue(alive(icons).isEmpty() || plugin.holoShops().all().isEmpty());
    }

    @Test
    void removeAllCleansEverythingOnDisable() {
        make();
        plugin.holoShops().tick();
        plugin.holoShops().removeAll();
        assertTrue(alive(icons).isEmpty() && alive(texts).isEmpty() && alive(zones).isEmpty());
        assertEquals(1, plugin.holoShops().all().size(), "сами витрины остаются");
    }

    // ------------------------------------------------------------ FancyHolograms

    @Test
    void withFancyHologramsTheIconsAndTextsGoThroughIt() {
        ru.industryreborn.holo.FancyHolograms.MANAGER.holograms.clear();
        ru.industryreborn.holo.FancyHolograms.MANAGER.loaded = true;
        org.mockbukkit.mockbukkit.MockBukkit.loadSimple(ru.industryreborn.holo.FancyHolograms.class, "FancyHolograms", "2.10.0");
        make();
        plugin.holoShops().tick();
        var holograms = ru.industryreborn.holo.FancyHolograms.MANAGER.holograms;
        long items = holograms.values().stream().filter(h -> h.getData() instanceof de.oliver.fancyholograms.api.data.ItemHologramData).count();
        long texts2 = holograms.values().stream().filter(h -> h.getData() instanceof de.oliver.fancyholograms.api.data.TextHologramData).count();
        assertEquals(3, items);
        assertEquals(1 + 3 + 9 + 1, texts2, "заголовок, подписи колонок, ячейки трёх строк и подсказка");
        var firstItem = (de.oliver.fancyholograms.api.data.ItemHologramData) holograms.values().stream()
                .filter(h -> h.getData() instanceof de.oliver.fancyholograms.api.data.ItemHologramData).findFirst().get().getData();
        assertEquals(Material.DIAMOND, firstItem.getItemStack().getType());
        assertTrue(holograms.values().stream().noneMatch(h -> h.getData().isPersistent()));
        assertEquals(3, alive(zones).size(), "клики всё равно ловят зоны Interaction");
        // витрина убрана - голограммы убраны
        plugin.holoShops().removeAll();
        assertTrue(holograms.isEmpty());
        ru.industryreborn.holo.FancyHolograms.MANAGER.holograms.clear();
    }

    @Test
    void persistenceFileIsPlainYaml() {
        make();
        File f = new File(plugin.getDataFolder(), "holoshops.yml");
        assertTrue(f.exists());
    }
}
