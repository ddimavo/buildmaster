package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.JobRole;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Station;
import ru.buildmaster.service.ManagerPanels;
import ru.buildmaster.service.StructureText;
import ru.buildmaster.support.PluginTestBase;

/** Голограммы станков и панели характеристик предприятий для управляющего. */
class HologramsTest extends PluginTestBase {

    private PlayerMock steve;
    private PlayerMock alex;
    private PlayerMock boss;
    private Plot workshop;

    /** Поддельная голограмма: MockBukkit не реализует TextDisplay. Помнит текст и признак «жива». */
    private static final class FakeDisplay implements java.lang.reflect.InvocationHandler {
        boolean valid = true;
        net.kyori.adventure.text.Component text;
        Location at;
        final java.util.Set<java.util.UUID> shownTo = new java.util.HashSet<>();

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) {
            switch (m.getName()) {
                case "isValid":
                    return valid;
                case "remove":
                    valid = false;
                    return null;
                case "text":
                    if (a != null && a.length == 1) {
                        text = (net.kyori.adventure.text.Component) a[0];
                        return null;
                    }
                    return text;
                case "teleport":
                    at = (Location) a[0];
                    return true;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == a[0];
                case "toString":
                    return "FakeDisplay";
                default:
                    return m.getReturnType() == boolean.class ? (Object) false : null;
            }
        }
    }

    private final java.util.List<FakeDisplay> fakes = new java.util.ArrayList<>();

    private org.bukkit.entity.TextDisplay fakeDisplay(Location at, java.util.function.Consumer<org.bukkit.entity.TextDisplay> init) {
        FakeDisplay h = new FakeDisplay();
        h.at = at;
        fakes.add(h);
        org.bukkit.entity.TextDisplay d = (org.bukkit.entity.TextDisplay) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.entity.TextDisplay.class.getClassLoader(), new Class<?>[] {org.bukkit.entity.TextDisplay.class}, h);
        init.accept(d);
        return d;
    }

    @BeforeEach
    void setUp() {
        plugin.machineHolograms().setSpawner(this::fakeDisplay);
        plugin.managerPanels().setSpawner(this::fakeDisplay);
        plugin.getConfig().set("limits.total", 0);
        for (String c : new String[] {"workshop", "shop", "hotel", "house", "restaurant"}) {
            plugin.getConfig().set("limits.categories." + c, 0);
        }
        server.getWorld("world").loadChunk(0, 0);
        steve = player("Steve", 100_000);
        alex = player("Alex", 100_000);
        boss = admin("Boss");
        for (PlayerMock p : new PlayerMock[] {steve, alex, boss}) {
            standNear(p, 5000);      // все далеко от участков, пока тест не поставит их рядом
        }
        workshop = addPlot("w", Category.WORKSHOP, 0, -1, 10);
        plugin.service().rent(steve, workshop, 10);
    }

    private String text(Plot plot) {
        return String.join("\n", StructureText.lines(plugin, plot)).replaceAll("<[^>]+>", "");
    }

    private void standNear(PlayerMock p, int x) {
        p.teleport(new Location(server.getWorld("world"), x, 1, 2));
    }

    private Block machine(Material m, int x) {
        Block b = server.getWorld("world").getBlockAt(x, 1, 1);
        b.setType(m);
        plugin.stations().addMachine(workshop, x, 1, 1, Station.fromName(m.name()));
        return b;
    }

    // ------------------------------------------------------------ панель предприятия

    @Test
    void theWorkshopPanelShowsPowerStationsProductionAndStats() {
        machine(Material.FURNACE, 1);
        plugin.stations().inc(workshop, Station.FURNACE);
        plugin.stations().inc(workshop, Station.ANVIL);
        plugin.power().recalc(workshop);
        workshop.account.deposit(1000);
        workshop.revenue = 345;
        String text = text(workshop);
        assertTrue(text.contains("Панель управляющего"));
        assertTrue(text.contains("Потребление"));
        assertTrue(text.contains("V/сут"));
        assertTrue(text.contains("Стоимость"));
        assertTrue(text.contains("Станки") && text.contains("Всего"));
        assertTrue(text.contains("плавилен 1") && text.contains("наковален 1"));
        assertTrue(text.contains("Линии") && text.contains("Сырьё / продукция"));
        assertTrue(text.contains("Выручка всего") && text.contains("345"));
        assertTrue(text.contains("Баланс счёта"));
    }

    @Test
    void theWorkshopPanelCountsProductionAndOrders() {
        Plot.Line line = new Plot.Line();
        line.input = 30;
        line.output = 7;
        workshop.lines.put("iron-ingots", line);
        assertTrue(text(workshop).contains("30 / 7"));
    }

    @Test
    void aShopPanelShowsAccountRevenueAndStaff() {
        Plot shop = addPlot("s", Category.SHOP, 20, 100, -1);
        plugin.service().buy(steve, shop);
        shop.account.deposit(500);
        assertNull(plugin.jobs().post(steve, shop, JobRole.SELLER, 1, 50, 10, null));
        plugin.jobs().apply(alex, plugin.jobs().vacanciesOf("s").get(0));
        plugin.jobs().accept(steve, plugin.jobs().applicationsBy(alex.getUniqueId()).get(0));
        plugin.jobs().onSale(shop, 200);
        String text = text(shop);
        assertTrue(text.contains("Счёт предприятия") && text.contains("500"), text);
        assertTrue(text.contains("Выручка всего") && text.contains("200"));
        assertTrue(text.contains("Работники: 1"));
        assertTrue(text.contains("к выплате"));
    }

    @Test
    void aHotelPanelShowsRoomsAndIncome() {
        Plot hotel = addPlot("hot", Category.HOTEL, 20, 100, -1);
        plugin.service().buy(steve, hotel);
        Plot r1 = new Plot("hot-1", "world", new Box(21, 1, 1, 23, 3, 3));
        r1.category = Category.ROOM;
        r1.parentId = "hot";
        r1.rentPerDay = 40;
        plugin.plots().add(r1);
        Plot r2 = new Plot("hot-2", "world", new Box(25, 1, 1, 27, 3, 3));
        r2.category = Category.ROOM;
        r2.parentId = "hot";
        r2.rentPerDay = 60;
        plugin.plots().add(r2);
        plugin.service().rent(alex, r2, 2);
        String text = text(hotel);
        assertTrue(text.contains("Номера: 1 занято из 2"), text);
        assertTrue(text.contains("60"), "доход по занятым номерам");
        assertTrue(text.contains("Выручка всего"));
    }

    @Test
    void hotelRevenueGrowsWithRoomPayments() {
        Plot hotel = addPlot("hot", Category.HOTEL, 20, 100, -1);
        plugin.service().buy(steve, hotel);
        Plot r = new Plot("hot-1", "world", new Box(21, 1, 1, 23, 3, 3));
        r.category = Category.ROOM;
        r.parentId = "hot";
        r.rentPerDay = 40;
        plugin.plots().add(r);
        plugin.service().rent(alex, r, 3);
        assertEquals(120, hotel.revenue, 1e-9);
    }

    @Test
    void aHousePanelShowsRightsAndRent() {
        Plot house = addPlot("h", Category.HOUSE, 20, 100, 5);
        plugin.service().rent(steve, house, 3);
        house.grant(alex.getUniqueId(), Plot.Perm.CONTAINERS);
        String text = text(house);
        assertTrue(text.contains("Игроков с правами: 1"));
        assertTrue(text.contains("Аренда до"));
    }

    @Test
    void aRoomPanelShowsTheGuest() {
        Plot hotel = addPlot("hot", Category.HOTEL, 20, 100, -1);
        Plot r = new Plot("hot-1", "world", new Box(21, 1, 1, 23, 3, 3));
        r.category = Category.ROOM;
        r.parentId = "hot";
        r.rentPerDay = 40;
        plugin.plots().add(r);
        assertTrue(text(r).contains("свободен"));
        plugin.service().rent(alex, r, 1);
        assertTrue(text(r).contains("Alex"));
    }

    @Test
    void thePowerPlantPanelShowsCapacityLoadAndConnections() {
        Plot plant = addPlot("p", Category.POWER_PLANT, 40, -1, -1);
        plant.plantCapacity = 1000;
        String text = text(plant);
        assertTrue(text.contains("Мощность") && text.contains("1000"));
        assertTrue(text.contains("Нагрузка"));
        assertTrue(text.contains("Подключено"));
        plant.plantMultiplier = 1.5;
        assertTrue(text(plant).contains("Перегрузка"));
    }

    @Test
    void theBankPanelSumsAccountsAndDebts() {
        Plot bank = addPlot("b", Category.BANK, 60, -1, -1);
        workshop.account.deposit(300);
        workshop.taxDebt = 40;
        String text = text(bank);
        assertTrue(text.contains("Счетов с деньгами: 1"), text);
        assertTrue(text.contains("300"));
        assertTrue(text.contains("Долги по налогам") && text.contains("40"));
        assertTrue(text.contains("Казна"));
    }

    // ------------------------------------------------------------ кто видит панель

    @Test
    void onlyManagersLandlordsAndAdminsSeeThePanel() {
        assertTrue(ManagerPanels.canSee(workshop, steve));
        assertFalse(ManagerPanels.canSee(workshop, alex));
        assertTrue(ManagerPanels.canSee(workshop, boss));
        Plot pp = new Plot("pp", "world", new Box(80, 0, 0, 89, 9, 9));
        pp.kind = Plot.Kind.PLAYER;
        pp.landlord = alex.getUniqueId();
        assertTrue(ManagerPanels.canSee(pp, alex));
        assertFalse(ManagerPanels.canSee(pp, steve));
    }

    @Test
    void thePanelAppearsOnlyNearItsManagerAndOnlyWhenAHologramExists() {
        standNear(steve, 3);
        plugin.managerPanels().tick();
        assertEquals(0, plugin.managerPanels().spawnedCount(), "нет общей голограммы - нет и панели");
        workshop.hasHolo = true;
        workshop.holoX = 3;
        workshop.holoY = 3;
        workshop.holoZ = 3;
        plugin.managerPanels().tick();
        assertEquals(1, plugin.managerPanels().spawnedCount());
        standNear(steve, 300);
        plugin.managerPanels().tick();
        assertEquals(0, plugin.managerPanels().spawnedCount(), "менеджер ушёл - панель убрана");
        standNear(alex, 3);
        standNear(steve, 300);
        plugin.managerPanels().tick();
        assertEquals(0, plugin.managerPanels().spawnedCount(), "чужой игрок рядом панель не создаёт");
    }

    @Test
    void panelsCanBeDisabled() {
        workshop.hasHolo = true;
        workshop.holoX = 3;
        workshop.holoY = 3;
        workshop.holoZ = 3;
        standNear(steve, 3);
        plugin.managerPanels().tick();
        assertEquals(1, plugin.managerPanels().spawnedCount());
        plugin.getConfig().set("holograms.manager-panels", false);
        plugin.managerPanels().tick();
        assertEquals(0, plugin.managerPanels().spawnedCount());
    }

    // ------------------------------------------------------------ голограммы станков

    @Test
    void aMachineHologramAppearsNearPlayersAndDisappearsWhenTheyLeave() {
        machine(Material.FURNACE, 2);
        standNear(steve, 3);
        plugin.machineHolograms().tick();
        assertEquals(1, plugin.machineHolograms().spawnedCount());
        standNear(steve, 300);
        plugin.machineHolograms().tick();
        assertEquals(0, plugin.machineHolograms().spawnedCount());
    }

    @Test
    void aDestroyedMachineLosesItsRecordAndHologram() {
        Block b = machine(Material.ANVIL, 2);
        standNear(steve, 3);
        plugin.machineHolograms().tick();
        assertEquals(1, plugin.machineHolograms().spawnedCount());
        b.setType(Material.AIR);                    // взрыв или поршень: события нет
        plugin.machineHolograms().tick();
        assertTrue(plugin.stations().machines(workshop).isEmpty());
        assertEquals(0, plugin.machineHolograms().spawnedCount());
    }

    @Test
    void theMachineLinesDescribeTheStation() {
        Block anvil = machine(Material.ANVIL, 2);
        String text = String.join("\n", plugin.machineHolograms().lines(workshop, anvil, Station.ANVIL)).replaceAll("<[^>]+>", "");
        assertTrue(text.contains("Наковальня"));
        assertTrue(text.contains("V/сут"));
        plugin.getConfig().set("electricity.mode", "USAGE");
        String usage = String.join("\n", plugin.machineHolograms().lines(workshop, anvil, Station.ANVIL));
        assertTrue(usage.contains("Операция"), usage);
    }

    @Test
    void anUnpoweredWorkshopShowsItOnItsMachines() {
        Block furnace = machine(Material.FURNACE, 2);
        plugin.stations().inc(workshop, Station.FURNACE);
        plugin.power().recalc(workshop);
        plugin.power().touch(workshop);
        workshop.account.restore(0, workshop.account.ratePerDay(), workshop.account.updatedAtSec());
        String text = String.join("\n", plugin.machineHolograms().lines(workshop, furnace, Station.FURNACE));
        assertTrue(text.contains("обесточен"), text);
    }

    @Test
    void machineHologramsCanBeDisabled() {
        machine(Material.FURNACE, 2);
        standNear(steve, 3);
        plugin.machineHolograms().tick();
        assertEquals(1, plugin.machineHolograms().spawnedCount());
        plugin.getConfig().set("holograms.machines", false);
        plugin.machineHolograms().tick();
        assertEquals(0, plugin.machineHolograms().spawnedCount());
    }

    @Test
    void aFreedWorkshopDropsItsMachineHolograms() {
        machine(Material.FURNACE, 2);
        standNear(steve, 3);
        plugin.machineHolograms().tick();
        assertEquals(1, plugin.machineHolograms().spawnedCount());
        ru.buildmaster.util.ChunkJob.setLoader((world, cx, cz, cb) -> cb.accept(world.getChunkAt(cx, cz), null));
        ru.buildmaster.util.ChunkAccess.setSource(new ru.buildmaster.util.ChunkAccess.Source() {
            @Override
            public List<org.bukkit.block.BlockState> tileEntities(org.bukkit.Chunk chunk) {
                return List.of();
            }

            @Override
            public java.util.Collection<org.bukkit.entity.Entity> entities(org.bukkit.Chunk chunk) {
                return List.of();
            }
        });
        try {
            plugin.service().release(steve, workshop);
            server.getScheduler().performTicks(20);
        } finally {
            ru.buildmaster.util.ChunkJob.setLoader(null);
            ru.buildmaster.util.ChunkAccess.setSource(null);
        }
        plugin.machineHolograms().tick();
        assertEquals(0, plugin.machineHolograms().spawnedCount());
    }

    @Test
    void theMachineHologramTextIsUpdatedInPlaceWithoutRespawning() {
        machine(Material.ANVIL, 2);
        standNear(steve, 3);
        plugin.machineHolograms().tick();
        assertEquals(1, fakes.size());
        assertNotNull(fakes.get(0).text);
        String before = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(fakes.get(0).text);
        assertTrue(before.contains("Наковальня"), before);
        plugin.power().stats().record("w", "anvil", 5);
        plugin.machineHolograms().tick();
        assertEquals(1, fakes.size(), "та же голограмма обновлена, новая не создана");
        String after = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(fakes.get(0).text);
        assertTrue(after.contains("1"), after);
    }

    @Test
    void theMachineHologramSitsAboveTheBlockAndPanelAboveThePublicHologram() {
        machine(Material.ANVIL, 2);
        workshop.hasHolo = true;
        workshop.holoX = 3;
        workshop.holoY = 3;
        workshop.holoZ = 3;
        standNear(steve, 3);
        plugin.machineHolograms().tick();
        plugin.managerPanels().tick();
        assertEquals(2, fakes.size());
        assertEquals(2.5, fakes.get(0).at.getX(), 1e-9);
        assertTrue(fakes.get(0).at.getY() > 2, "над блоком станка");
        assertTrue(fakes.get(1).at.getY() > workshop.holoY, "панель выше общей голограммы");
    }

    @AfterEach
    void cleanup() {
        plugin.machineHolograms().removeAll();
        plugin.managerPanels().removeAll();
    }
}
