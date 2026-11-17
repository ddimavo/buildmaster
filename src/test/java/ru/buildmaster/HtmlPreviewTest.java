package ru.buildmaster;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.html.GuiBackend;
import ru.buildmaster.html.HtmlMenus;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.JobRole;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Station;
import ru.buildmaster.service.Journal;
import ru.buildmaster.support.PluginTestBase;

/**
 * Не проверка, а генератор примеров: при заданной переменной BM_PREVIEW_DIR записывает HTML-страницы демо-сценария
 * в эту папку, чтобы посмотреть их в браузере. Без переменной тест пропускается.
 */
class HtmlPreviewTest extends PluginTestBase {

    @Test
    void writeSamples() throws Exception {
        String dir = System.getenv("BM_PREVIEW_DIR");
        Assumptions.assumeTrue(dir != null && !dir.isBlank());
        File out = new File(dir);
        out.mkdirs();
        GuiBackend gui = new GuiBackend() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public File pagesDir() {
                return out;
            }

            @Override
            public boolean exists(String page) {
                return true;
            }

            @Override
            public boolean open(org.bukkit.entity.Player player, String page) {
                return true;
            }

            @Override
            public void close(org.bukkit.entity.Player player) { }

            @Override
            public String pageOf(org.bukkit.entity.Player player) {
                return null;
            }
        };
        plugin.htmlMenus().setBackend(gui);
        plugin.getConfig().set("limits.total", 0);
        for (String c : new String[] {"workshop", "shop", "hotel", "house", "room", "restaurant"}) {
            plugin.getConfig().set("limits.categories." + c, 0);
        }
        plugin.getConfig().set("bank.rent-tax-percent", 5);
        plugin.getConfig().set("bank.deposit-commission-percent", 1);
        plugin.getConfig().set("goods.sales-tax-percent", 3);
        server.getWorld("world").loadChunk(0, 0);
        PlayerMock felya = player("Felya_", 441_365.91);
        PlayerMock boss = admin("Boss");
        PlayerMock mia = player("Mia", 5000);
        plugin.recipes().setSource(() -> List.<org.bukkit.inventory.Recipe>of(
                new org.bukkit.inventory.FurnaceRecipe(new org.bukkit.NamespacedKey(plugin, "a"), new ItemStack(Material.IRON_INGOT), Material.RAW_IRON, 0.7f, 200),
                new org.bukkit.inventory.FurnaceRecipe(new org.bukkit.NamespacedKey(plugin, "b"), new ItemStack(Material.GOLD_INGOT), Material.RAW_GOLD, 0.7f, 200),
                new org.bukkit.inventory.FurnaceRecipe(new org.bukkit.NamespacedKey(plugin, "c"), new ItemStack(Material.COPPER_INGOT), Material.RAW_COPPER, 0.7f, 200)).iterator());

        Plot shop = addPlot("s1", Category.SHOP, 0, -1, 80);
        shop.name = "Лавка Феи";
        plugin.service().rent(felya, shop, 7);
        shop.goods.put(Material.IRON_INGOT, 64);
        shop.prices.put(Material.IRON_INGOT, 9.5);
        shop.goods.put(Material.GOLD_INGOT, 12);
        shop.prices.put(Material.GOLD_INGOT, 21.0);
        Plot w = addPlot("w1", Category.WORKSHOP, 20, -1, 150);
        w.name = "Кузница";
        plugin.service().rent(felya, w, 5);
        for (int i = 0; i < 2; i++) {
            server.getWorld("world").getBlockAt(21 + i, 1, 1).setType(Material.FURNACE);
            plugin.stations().addMachine(w, 21 + i, 1, 1, Station.fromName("FURNACE"));
            plugin.stations().inc(w, Station.fromName("FURNACE"));
        }
        server.getWorld("world").getBlockAt(24, 1, 1).setType(Material.ANVIL);
        plugin.stations().addMachine(w, 24, 1, 1, Station.fromName("ANVIL"));
        plugin.power().recalc(w);
        plugin.power().deposit(felya, w, 25_000);
        w.stock.put(Material.RAW_IRON, 320);
        w.stock.put(Material.RAW_GOLD, 96);
        plugin.factory().assign(felya, w, "21,1,1", "furnace:IRON_INGOT");
        Plot hotel = addPlot("hot", Category.HOTEL, 40, -1, 200);
        hotel.name = "Отель «Заря»";
        plugin.service().rent(felya, hotel, 10);
        hotel.maxRoomRate = 300;
        for (int i = 0; i < 4; i++) {
            Plot r = new Plot("hot-" + (101 + i), "world", new Box(41 + i * 2, 1, 1, 42 + i * 2, 3, 3));
            r.category = Category.ROOM;
            r.parentId = "hot";
            r.rentPerDay = 90 + i * 20;
            plugin.plots().add(r);
        }
        plugin.service().rent(mia, plugin.plots().get("hot-101"), 3);
        plugin.power().deposit(felya, hotel, 3000);
        addPlot("h1", Category.HOUSE, 60, 4500, 60).name = "Домик у озера";
        addPlot("sh2", Category.SHOP, 80, 9000, 120).description = "Угловой магазин рядом с банком.";
        addPlot("r1", Category.RESTAURANT, 100, -1, 200);
        addPlot("wh1", Category.WAREHOUSE, 120, 6000, 70);
        plugin.treasury().deposit(48_250, "Аренда участков");
        plugin.treasury().deposit(12_300, "Электричество участка w1");
        plugin.treasury().deposit(2_480, "Налог на аренду, участок hot");
        plugin.payouts().prize("Boss", mia, 1500, "Турнир строителей");
        plugin.payouts().subsidy("Boss", shop, 2000, "Открытие лавки");
        plugin.journal().add("s1", felya.getUniqueId(), "Boss", Journal.Action.RENTED, "7 сут., 560");
        plugin.journal().add("w1", felya.getUniqueId(), "система", Journal.Action.RENTED, "5 сут., 750");
        plugin.warnings().add(felya.getUniqueId(), "s1", "<yellow>Аренда участка <white>s1</white> закончится через 2 суток.");
        plugin.warnings().add(felya.getUniqueId(), "w1", "<green>Администрация выделила участку <white>w1</white> субсидию 2000.");
        plugin.storage().add(felya.getUniqueId(), "old", List.of(new ItemStack(Material.DIAMOND_PICKAXE, 1), new ItemStack(Material.COBBLESTONE, 128)),
                ru.buildmaster.model.StorageEntry.State.AVAILABLE, null, "demo");
        plugin.jobs().post(felya, shop, JobRole.SELLER, 2, 60, 5, null);
        plugin.jobs().post(felya, w, JobRole.MASTER, 1, 120, 0, null);
        plugin.jobs().apply(mia, plugin.jobs().vacanciesOf("s1").get(0));
        plugin.orders().configureOffer(felya, w, "smelt", 2.0, true);

        // записываем все страницы для Felya_ (включая режим админа для казны)
        PlayerMock target = felya;
        target.addAttachment(plugin, "buildmaster.bank", true);
        target.addAttachment(plugin, "buildmaster.admin", true);
        target.addAttachment(plugin, "buildmaster.grid", true);
        Plot plant = addPlot("pp1", Category.POWER_PLANT, 140, -1, -1);
        plant.name = "ТЭС «Север»";
        plant.plantCapacity = 1000;
        plugin.grid().connect(w, plant, "Boss");
        plugin.grid().connect(hotel, plant, "Boss");
        Plot extra = addPlot("w2", Category.WORKSHOP, 160, -1, 150);
        plugin.service().rent(mia, extra, 3);
        plugin.grid().disconnect(extra, "Просрочена оплата", "Boss");
        mia.performCommand("district name w2 Мастерская Мии");
        mia.performCommand("district desc w2 Чиним и зачаровываем всё на свете");
        plugin.storage().add(mia.getUniqueId(), "w2", List.of(new ItemStack(Material.DIAMOND, 5)),
                ru.buildmaster.model.StorageEntry.State.ARRESTED, "арест", "demo");
        for (HtmlMenus.Kind kind : HtmlMenus.Kind.values()) {
            plugin.htmlMenus().open(target, kind);
        }
        File[] files = new File(out, "buildmaster").listFiles();
        if (files != null) {
            for (File f : files) {
                Files.move(f.toPath(), new File(out, f.getName().replaceAll("-[0-9a-f]{32}", "")).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
