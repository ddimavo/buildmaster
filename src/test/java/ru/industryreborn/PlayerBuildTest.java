package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.building.BuildingTemplate;
import ru.industryreborn.html.GuiBackend;
import ru.industryreborn.model.Box;
import ru.industryreborn.model.Category;
import ru.industryreborn.model.Plot;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.Module;
import ru.industryreborn.support.PluginTestBase;
import ru.industryreborn.util.ChunkJob;

/** Платное строительство игроками: цена за блок, заказ на своём участке, отказы, возврат, меню на HTML и превью. */
class PlayerBuildTest extends PluginTestBase {

    private static final class FakeGui implements GuiBackend {
        File dir;
        final Map<UUID, String> current = new HashMap<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public File pagesDir() {
            return dir;
        }

        @Override
        public boolean exists(String page) {
            return new File(dir, page + ".html").exists();
        }

        @Override
        public boolean open(Player player, String page) {
            current.put(player.getUniqueId(), page);
            return exists(page);
        }

        @Override
        public void close(Player player) {
            current.remove(player.getUniqueId());
        }

        @Override
        public String pageOf(Player player) {
            return current.get(player.getUniqueId());
        }
    }

    @TempDir
    File tmp;
    private FakeGui gui;
    private World world;
    private PlayerMock steve;
    private PlayerMock alex;
    private PlayerMock boss;
    private Plot plot;
    private BuildingTemplate shop;

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    private Plot makePlot(String id, Category category, Box box, PlayerMock owner) {
        Plot p = new Plot(id, "world", box);
        p.category = category;
        p.price = 100;
        if (owner != null) {
            p.owner = owner.getUniqueId();
            p.status = Plot.Status.OWNED;
        }
        plugin.plots().add(p);
        return p;
    }

    @BeforeEach
    void setUp() {
        ChunkJob.setLoader((w, cx, cz, cb) -> cb.accept(w.getChunkAt(cx, cz), null));
        gui = new FakeGui();
        gui.dir = tmp;
        plugin.htmlMenus().setBackend(gui);
        plugin.getConfig().set("auto-build.synchronous", true);
        plugin.getConfig().set("auto-build.price-per-block", 2.0);
        plugin.getConfig().set("auto-build.min-price", 100);
        plugin.getConfig().set("limits.total", 0);
        world = Bukkit.getWorld("world");
        steve = player("Steve", 100_000);
        alex = player("Alex", 100_000);
        boss = admin("Boss");
        for (Player p : List.of(steve, alex, boss)) {
            p.teleport(new org.bukkit.Location(world, 1600, 10, 1600));
        }
        shop = plugin.buildings().get("shop-small");
        plot = makePlot("p1", Category.SHOP, new Box(0, 0, 0, 14, 20, 12), steve);
    }

    @AfterEach
    void tearDownSeams() {
        ChunkJob.setLoader(null);
    }

    private int solid(int x1, int x2, int z1, int z2) {
        int n = 0;
        for (int x = x1; x <= x2; x++) {
            for (int y = 5; y <= 20; y++) {
                for (int z = z1; z <= z2; z++) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private void run(Player p, String line) {
        p.performCommand("district " + line);
        server.getScheduler().performTicks(2);
    }

    // ------------------------------------------------------------ цена

    @Test
    void priceIsPerBlockWithCategoryMultiplierAndMinimum() {
        var svc = plugin.buildings();
        plugin.getConfig().set("auto-build.price-categories.shop", 1.5);
        assertEquals(3.0, svc.pricePerBlock(Category.SHOP), 1e-9);
        assertEquals(2.0, svc.pricePerBlock(Category.BANK), 1e-9, "множитель не задан - 1.0");
        assertEquals(2.0, svc.pricePerBlock(null), 1e-9);
        assertEquals(shop.blockCount() * 3.0, svc.price(shop), 1e-6);
        plugin.getConfig().set("auto-build.min-price", 1_000_000);
        assertEquals(1_000_000, svc.price(shop), 1e-9, "минимальная цена");
        plugin.getConfig().set("auto-build.min-price", 0);
        plugin.getConfig().set("auto-build.price-per-block", 0.0);
        assertEquals(0.0, svc.price(shop), 1e-9);
    }

    @Test
    void autoBalanceMovesTheBlockPrice() {
        plugin.getConfig().set("modules.auto-balance", true);
        plugin.getConfig().set("auto-balance.faucet-per-day", 10_000);
        plugin.treasury().deposit(5000, "Тест");     // сток вдвое меньше притока - цены растут
        var svc = plugin.buildings();
        double before = svc.pricePerBlock(Category.SHOP);
        var changes = plugin.balance().run(false, "Boss");
        var c = changes.stream().filter(x -> x.key().equals("build-price")).findFirst().orElseThrow();
        assertEquals(1.1, c.to(), 1e-9, "не больше 10% за запуск");
        assertEquals(before * 1.1, svc.pricePerBlock(Category.SHOP), 1e-9);
        assertEquals(before, svc.basePricePerBlock(Category.SHOP), 1e-9, "база из config.yml не меняется");
        assertTrue(plugin.journal().forPlot("-").stream().anyMatch(e -> e.action() == Journal.Action.BALANCE && e.reason().contains("строительства")));
        // сброс возвращает базу
        plugin.balance().reset("Boss");
        assertEquals(before, svc.pricePerBlock(Category.SHOP), 1e-9);
        // можно отключить для одной цены
        plugin.getConfig().set("auto-balance.adjust.build-price", false);
        assertTrue(plugin.balance().run(false, "Boss").stream().noneMatch(x -> x.key().equals("build-price")));
    }

    // ------------------------------------------------------------ заказ

    @Test
    void orderNeedsConfirmationThenChargesTheTreasury() {
        double price = plugin.buildings().price(shop);
        double treasury = plugin.treasury().balance();
        run(steve, "build p1 shop-small");
        String out = drain(steve);
        assertTrue(out.contains("Построить"), out);
        assertTrue(out.contains(String.valueOf(shop.blockCount())), out);
        assertEquals(100_000, economy.getBalance(steve), 1e-9, "без подтверждения ничего не списывается");
        assertEquals(0, solid(0, 14, 0, 12));
        run(steve, "build p1 shop-small confirm");
        out = drain(steve);
        assertTrue(out.contains("построено"), out);
        assertEquals(100_000 - price, economy.getBalance(steve), 1e-6);
        assertEquals(treasury + price, plugin.treasury().balance(), 1e-6, "деньги идут в казну");
        assertTrue(solid(0, 14, 0, 12) > 50, "здание поставлено");
        assertFalse(plot.busy, "участок разблокирован");
        var entry = plugin.journal().forPlot("p1").stream().filter(e -> e.action() == Journal.Action.BUILD).findFirst().orElseThrow();
        assertEquals("p1", entry.plotId());
        assertEquals(steve.getUniqueId(), entry.subject());
        // второй раз то же самое место занято: денег не берём
        double after = economy.getBalance(steve);
        run(steve, "build p1 shop-small confirm");
        assertTrue(drain(steve).contains("уже что-то построено"));
        assertEquals(after, economy.getBalance(steve), 1e-9);
    }

    @Test
    void notEnoughMoneyChargesNothing() {
        economy.set(steve, 10);
        run(steve, "build p1 shop-small confirm");
        assertTrue(drain(steve).contains("Недостаточно средств"));
        assertEquals(10, economy.getBalance(steve), 1e-9);
        assertEquals(0, solid(0, 14, 0, 12));
        assertTrue(plugin.journal().forPlot("p1").stream().noneMatch(e -> e.action() == Journal.Action.BUILD));
    }

    @Test
    void onlyTheOwnerOfAnOccupiedPlotCanOrder() {
        run(alex, "build p1 shop-small confirm");
        assertTrue(drain(alex).contains("только владелец"));
        assertEquals(0, solid(0, 14, 0, 12));
        Plot free = makePlot("free", Category.SHOP, new Box(30, 0, 0, 44, 20, 12), null);
        run(steve, "build free shop-small confirm");
        assertTrue(drain(steve).contains("сначала купите"));
        plot.graceUntil = System.currentTimeMillis() + 100_000;       // просрочена аренда - участок заморожен
        run(steve, "build p1 shop-small confirm");
        assertTrue(drain(steve).contains("просрочена"));
        plot.graceUntil = 0;
        assertNotNull(free);
        // участок игрока (регион WorldGuard)
        Plot region = makePlot("reg", Category.SHOP, new Box(60, 0, 0, 74, 20, 12), steve);
        region.kind = Plot.Kind.PLAYER;
        run(steve, "build reg shop-small confirm");
        assertTrue(drain(steve).contains("участках игроков"));
        // административные здания и номера
        Plot bank = makePlot("bank1", Category.BANK, new Box(90, 0, 0, 110, 20, 14), steve);
        run(steve, "build bank1 bank confirm");
        assertTrue(drain(steve).contains("не заказываются"));
        assertEquals(0, solid(60, 110, 0, 14));
    }

    @Test
    void buildingMustMatchThePlotTypeUnlessDisabled() {
        run(steve, "build p1 house-small confirm");
        assertTrue(drain(steve).contains("не подходит участку"));
        assertEquals(100_000, economy.getBalance(steve), 1e-9);
        plugin.getConfig().set("auto-build.match-category", false);
        run(steve, "build p1 house-small confirm");
        assertTrue(drain(steve).contains("построено"));
    }

    @Test
    void tooSmallAndOccupiedPlots() {
        Plot small = makePlot("small", Category.SHOP, new Box(30, 0, 0, 33, 20, 3), steve);
        run(steve, "build small shop-small confirm");
        assertTrue(drain(steve).contains("не помещается"));
        world.getBlockAt(6, 6, 5).setType(Material.OAK_PLANKS);
        run(steve, "build p1 shop-small confirm");
        assertTrue(drain(steve).contains("уже что-то построено"));
        assertEquals(100_000, economy.getBalance(steve), 1e-9);
        assertNotNull(small);
    }

    @Test
    void disabledFunctionBlocksEveryoneAndPlayerBuildCanBeSwitchedOff() {
        plugin.getConfig().set("auto-build.player-build", false);
        run(steve, "build p1 shop-small confirm");
        assertTrue(drain(steve).contains("отключено"));
        run(boss, "build menu p1");
        assertEquals(0, solid(0, 14, 0, 12));
        plugin.getConfig().set("auto-build.player-build", true);
        plugin.modules().set(Module.AUTO_BUILD, false, "test");
        run(steve, "build p1 shop-small confirm");
        assertTrue(drain(steve).toLowerCase().contains("отключ"));
        run(boss, "build list");
        assertTrue(drain(boss).toLowerCase().contains("отключ"), "и для администрации");
        assertEquals(100_000, economy.getBalance(steve), 1e-9);
    }

    @Test
    void playersCannotUseAdminSubcommands() {
        for (String line : new String[] {"build reload", "build save x shop", "build import a.nbt x shop"}) {
            run(steve, line);
            assertTrue(drain(steve).contains("Недостаточно прав"), line);
        }
        assertNull(plugin.buildings().get("x"));
        run(steve, "build list");
        String list = drain(steve);
        assertTrue(list.contains("shop-small"), list);
        assertTrue(list.contains("/district build menu"), list);
        assertFalse(list.contains("build save"), "подсказки для администратора игроку не показываются");
    }

    @Test
    void failureDuringConstructionRefundsFromTheTreasury() {
        double treasury = plugin.treasury().balance();
        plugin.buildings().injectFailure(new IllegalStateException("сбой"));
        run(steve, "build p1 shop-small confirm");
        String out = drain(steve);
        assertTrue(out.contains("Не удалось построить") && out.contains("возвращены"), out);
        assertEquals(100_000, economy.getBalance(steve), 1e-6, "деньги вернулись");
        assertEquals(treasury, plugin.treasury().balance(), 1e-6);
        assertFalse(plot.busy, "участок разблокирован");
        // здание у верхней границы мира не заказывается, деньги не берутся
        Plot high = makePlot("high", Category.SHOP, new Box(30, 124, 0, 44, 140, 12), steve);
        run(steve, "build high shop-small confirm");
        assertTrue(drain(steve).contains("верхнюю границу"));
        assertEquals(100_000, economy.getBalance(steve), 1e-6);
        assertFalse(high.busy);
    }

    @Test
    void plotIsLockedWhileTheBuildingGrows() {
        plugin.getConfig().set("auto-build.synchronous", false);
        plugin.getConfig().set("auto-build.blocks-per-tick", 200);
        BuildingTemplate big = plugin.buildings().get("shop-small");
        run(steve, "build p1 " + big.name() + " confirm");
        assertTrue(plot.busy, "пока идёт стройка, участок заблокирован");
        double money = economy.getBalance(steve);
        run(steve, "build p1 " + big.name() + " confirm");
        assertTrue(drain(steve).contains("обрабатывается"));
        assertEquals(money, economy.getBalance(steve), 1e-9, "второй заказ не оплачен");
        for (int i = 0; i < 200 && plot.busy; i++) {
            server.getScheduler().performTicks(1);
        }
        assertFalse(plot.busy);
        assertTrue(solid(0, 14, 0, 12) > 50);
    }

    @Test
    void menuNeverShowsSomeoneElsesPlot() throws Exception {
        Plot other = makePlot("alexs", Category.SHOP, new Box(30, 0, 0, 44, 20, 12), alex);
        plugin.htmlMenus().buildView(steve).plot = other.id;
        assertTrue(plugin.htmlMenus().open(steve, ru.industryreborn.html.HtmlMenus.Kind.BUILD));
        String html = page(steve);
        assertTrue(html.contains("№ p1"), html);
        assertFalse(html.contains("alexs"), html);
    }

    @Test
    void adminBuildsForFreeOnFreePlots() {
        Plot free = makePlot("free", Category.SHOP, new Box(30, 0, 0, 44, 20, 12), null);
        double treasury = plugin.treasury().balance();
        run(boss, "build free shop-small");
        assertTrue(solid(30, 44, 0, 12) > 50);
        assertEquals(treasury, plugin.treasury().balance(), 1e-9, "администратор не платит");
        assertNotNull(free);
    }

    // ------------------------------------------------------------ меню на HTML

    private String page(Player p) throws Exception {
        return Files.readString(new File(tmp, "industryreborn/build-" + p.getUniqueId().toString().replace("-", "") + ".html").toPath());
    }

    @Test
    void menuListsBuildingsWithPreviewsAndPrices() throws Exception {
        run(steve, "build menu p1");
        assertTrue(gui.current.get(steve.getUniqueId()).startsWith("industryreborn/build-"));
        String html = page(steve);
        var offered = plugin.buildings().offered(plot);
        assertFalse(offered.isEmpty());
        var first = offered.get(0);
        assertTrue(html.contains("district build detail " + first.name()), html);
        assertTrue(html.contains("src=\"img/" + first.name() + ".jpg\""), html);
        assertTrue(new File(tmp, "industryreborn/img/" + first.name() + ".jpg").length() > 1000, "превью лежит рядом со страницами");
        assertTrue(html.contains(plugin.economy().format(plugin.buildings().price(first)).replace(" ", "")) || html.contains(plugin.economy().format(plugin.buildings().price(first))), html);
        assertTrue(html.contains("Участок") && html.contains("p1"));
        // только здания этого типа и не больше страницы
        for (var t : offered.subList(0, Math.min(6, offered.size()))) {
            assertEquals("shop", t.category());
        }
        assertFalse(html.contains("district build detail house-small"));
        assertEquals(Math.min(6, offered.size()), html.split("district build detail ", -1).length - 1, "на странице 6 карточек");
    }

    @Test
    void menuPagesAndDetail() throws Exception {
        run(steve, "build menu p1");
        var offered = plugin.buildings().offered(plot);
        assertTrue(offered.size() > 6, "зданий больше одной страницы");
        String p1 = page(steve);
        assertTrue(p1.contains("Дальше"), p1);
        assertFalse(p1.contains("◀"));
        run(steve, "build view - 1");
        server.getScheduler().performTicks(1);
        plugin.htmlMenus().tick();
        String p2 = page(steve);
        assertTrue(p2.contains("Страница 2"), p2);
        assertTrue(p2.contains("district build detail " + offered.get(6).name()), p2);
        assertFalse(p2.contains("district build detail " + offered.get(0).name()));
        // подробности: две картинки (снаружи и разрез), цена и кнопка заказа
        var t = offered.get(0);
        run(steve, "build detail " + t.name());
        plugin.htmlMenus().tick();
        String d = page(steve);
        assertTrue(d.contains("img/" + t.name() + ".jpg") && d.contains("img/" + t.name() + "-cut.jpg"), d);
        assertTrue(d.contains("district build p1 " + t.name() + " confirm"), d);
        assertTrue(new File(tmp, "industryreborn/img/" + t.name() + "-cut.jpg").isFile());
        assertTrue(d.contains("district build detail -"));
        // не хватает денег - кнопки нет
        economy.set(steve, 5);
        plugin.htmlMenus().tick();
        d = page(steve);
        assertFalse(d.contains("confirm"), d);
        assertTrue(d.contains("Не хватает"), d);
        // назад к списку
        run(steve, "build detail -");
        plugin.htmlMenus().tick();
        assertTrue(page(steve).contains("district build detail "));
    }

    @Test
    void menuWithoutPlotsAndMainPageLink() throws Exception {
        run(alex, "build menu");
        assertTrue(drain(alex).contains("Нет участка"));
        assertTrue(plugin.htmlMenus().open(alex, ru.industryreborn.html.HtmlMenus.Kind.BUILD));
        assertTrue(page(alex).contains("на своём участке"));
        assertTrue(Files.readString(new File(tmp, "industryreborn/main-" + alex.getUniqueId().toString().replace("-", "") + ".html").toPath())
                .contains("build-") == false, "без участка раздела на главной нет");
        assertTrue(plugin.htmlMenus().open(steve, ru.industryreborn.html.HtmlMenus.Kind.MAIN));
        String main = Files.readString(new File(tmp, "industryreborn/main-" + steve.getUniqueId().toString().replace("-", "") + ".html").toPath());
        assertTrue(main.contains("href=\"build-" + steve.getUniqueId().toString().replace("-", "") + ".html\""), main);
        plugin.getConfig().set("auto-build.player-build", false);
        plugin.htmlMenus().tick();
        assertTrue(plugin.htmlMenus().open(steve, ru.industryreborn.html.HtmlMenus.Kind.MAIN));
        assertFalse(Files.readString(new File(tmp, "industryreborn/main-" + steve.getUniqueId().toString().replace("-", "") + ".html").toPath())
                .contains("build-" + steve.getUniqueId().toString().replace("-", "")));
    }

    @Test
    void minePageHasOrderButton() throws Exception {
        assertTrue(plugin.htmlMenus().open(steve, ru.industryreborn.html.HtmlMenus.Kind.MINE));
        String mine = Files.readString(new File(tmp, "industryreborn/mine-" + steve.getUniqueId().toString().replace("-", "") + ".html").toPath());
        assertTrue(mine.contains("district build menu p1"), mine);
    }

    @Test
    void chatFallbackWithoutHtml() {
        gui.dir = null;       // MC GUI без папки страниц: HTML не откроется
        plugin.htmlMenus().setBackend(new GuiBackend() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public File pagesDir() {
                return null;
            }

            @Override
            public boolean exists(String page) {
                return false;
            }

            @Override
            public boolean open(Player player, String page) {
                return false;
            }

            @Override
            public void close(Player player) { }

            @Override
            public String pageOf(Player player) {
                return null;
            }
        });
        run(steve, "build menu p1");
        String out = drain(steve);
        assertTrue(out.contains("shop-small") || out.contains("shop-0"), out);
        assertTrue(out.contains("[выбрать]"), out);
    }

    @Test
    void adminMenuShowsAllFittingTypesForFree() throws Exception {
        makePlot("free", Category.SHOP, new Box(30, 0, 0, 52, 20, 18), null);
        run(boss, "build menu free");
        String html = page(boss);
        assertTrue(html.contains("бесплатно"), html);
        assertTrue(html.contains("district build view all"), html);
        run(boss, "build view house");
        plugin.htmlMenus().tick();
        html = page(boss);
        assertTrue(html.contains("district build detail house-"), html);
        assertFalse(html.contains("district build detail shop-"), html);
    }

    // ------------------------------------------------------------ превью

    @Test
    void previewsAreDrawnCachedAndRedrawnOnChange() throws Exception {
        var svc = plugin.buildings();
        File a = svc.preview(shop, false);
        File cut = svc.preview(shop, true);
        assertNotNull(a);
        assertNotNull(cut);
        assertNotEquals(a, cut);
        assertTrue(a.length() > 2000 && cut.length() > 2000);
        var image = javax.imageio.ImageIO.read(a);
        assertTrue(image.getWidth() > 200 && image.getHeight() > 100);
        // не однотонная картинка: есть и небо, и здание
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        for (int x = 0; x < image.getWidth(); x += 3) {
            for (int y = 0; y < image.getHeight(); y += 3) {
                colors.add(image.getRGB(x, y) & 0xF0F0F0);
            }
        }
        assertTrue(colors.size() > 8, "картинка нарисована: " + colors.size());
        assertNotEquals(Files.size(a.toPath()), Files.size(cut.toPath()), "разрез отличается от обычного вида");
        // повторный вызов - тот же файл без перерисовки
        long stamp = a.lastModified();
        assertEquals(a, svc.preview(shop, false));
        assertEquals(stamp, a.lastModified());
        // файл здания изменился - перерисовать
        File source = new File(svc.folder(), "shop-small.ir.json");
        assertTrue(a.setLastModified(source.lastModified() - 60_000));
        svc.preview(shop, false);
        assertTrue(a.lastModified() > source.lastModified() - 60_000);
    }

    @Test
    void savedBuildingGetsAPreviewToo() throws Exception {
        world.getBlockAt(10, 5, 10).setType(Material.STONE);
        world.getBlockAt(11, 5, 10).setType(Material.OAK_PLANKS);
        world.getBlockAt(10, 6, 10).setType(Material.GLASS);
        plugin.selection().setPoint(boss, 1, 10, 5, 10);
        plugin.selection().setPoint(boss, 2, 11, 6, 10);
        run(boss, "build save mini shop Мини");
        var t = plugin.buildings().get("mini");
        assertNotNull(t);
        File f = plugin.buildings().preview(t, false);
        assertNotNull(f);
        assertTrue(f.length() > 500);
    }
}
