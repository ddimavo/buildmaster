package ru.buildmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Category;
import ru.buildmaster.model.Plot;
import ru.buildmaster.support.PluginTestBase;

/** Голограммы над дверями номеров отелей. */
class DoorHologramsTest extends PluginTestBase {

    private static final class Fake implements java.lang.reflect.InvocationHandler {
        boolean valid = true;
        net.kyori.adventure.text.Component text;
        Location at;

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) {
            return switch (m.getName()) {
                case "isValid" -> valid;
                case "remove" -> {
                    valid = false;
                    yield null;
                }
                case "text" -> {
                    if (a != null && a.length == 1) {
                        text = (net.kyori.adventure.text.Component) a[0];
                        yield null;
                    }
                    yield text;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == a[0];
                case "toString" -> "Fake";
                default -> m.getReturnType() == boolean.class ? (Object) false : null;
            };
        }
    }

    private final List<Fake> fakes = new ArrayList<>();
    private PlayerMock steve;
    private PlayerMock alex;
    private Plot hotel;
    private Plot room;

    private TextDisplay spawn(Location at, java.util.function.Consumer<TextDisplay> init) {
        Fake f = new Fake();
        f.at = at;
        fakes.add(f);
        TextDisplay d = (TextDisplay) java.lang.reflect.Proxy.newProxyInstance(TextDisplay.class.getClassLoader(),
                new Class<?>[] {TextDisplay.class}, f);
        init.accept(d);
        return d;
    }

    private String shown(int i) {
        return PlainTextComponentSerializer.plainText().serialize(fakes.get(i).text);
    }

    private void stand(PlayerMock p, int x) {
        p.teleport(new Location(server.getWorld("world"), x, 1, 1));
    }

    @BeforeEach
    void setUp() {
        plugin.doorHolograms().setSpawner(this::spawn);
        server.getWorld("world").loadChunk(0, 0);
        plugin.getConfig().set("limits.total", 0);
        plugin.getConfig().set("limits.categories.room", 0);
        steve = player("Steve", 10_000);
        alex = player("Alex", 10_000);
        stand(steve, 5000);
        stand(alex, 5000);
        hotel = new Plot("h", "world", new Box(0, 0, 0, 19, 5, 9));
        hotel.category = Category.HOTEL;
        plugin.plots().add(hotel);
        room = new Plot("h-101", "world", new Box(4, 1, 4, 7, 3, 7));
        room.category = Category.ROOM;
        room.parentId = "h";
        room.rentPerDay = 60;
        room.doors.add(new int[] {5, 1, 3});
        plugin.plots().add(room);
    }

    @AfterEach
    void tearDown() {
        plugin.doorHolograms().removeAll();
    }

    @Test
    void aHologramAppearsOnlyWhenSomeoneIsNear() {
        plugin.doorHolograms().tick();
        assertEquals(0, plugin.doorHolograms().spawnedCount());
        stand(alex, 6);
        plugin.doorHolograms().tick();
        assertEquals(1, plugin.doorHolograms().spawnedCount());
        assertTrue(shown(0).contains("Номер 101") && shown(0).contains("Свободен"), shown(0));
        stand(alex, 5000);
        plugin.doorHolograms().tick();
        assertEquals(0, plugin.doorHolograms().spawnedCount());
    }

    @Test
    void itIsPlacedOnTheCorridorSideAboveTheDoor() {
        stand(alex, 6);
        plugin.doorHolograms().tick();
        Location at = fakes.get(0).at;
        assertEquals(5.5, at.getX(), 1e-9);
        assertTrue(at.getZ() < 3.5, "коридор с южной стороны: " + at.getZ());
        assertTrue(at.getY() > 3);
    }

    @Test
    void theStatusFollowsTheRentWithoutRespawning() {
        stand(alex, 6);
        plugin.doorHolograms().tick();
        assertTrue(plugin.service().rent(steve, room, 3));
        plugin.doorHolograms().tick();
        assertEquals(1, fakes.size(), "обновлена та же голограмма");
        assertTrue(shown(0).contains("Занят") && shown(0).contains("Гость: Steve"), shown(0));
    }

    @Test
    void everyDoorOfARoomGetsItsOwnHologram() {
        room.doors.add(new int[] {6, 1, 8});
        stand(alex, 6);
        plugin.doorHolograms().tick();
        assertEquals(2, plugin.doorHolograms().spawnedCount());
    }

    @Test
    void roomsWithoutDoorsAndOtherPlotsHaveNone() {
        room.doors.clear();
        stand(alex, 6);
        plugin.doorHolograms().tick();
        assertEquals(0, plugin.doorHolograms().spawnedCount());
    }

    @Test
    void aDeletedRoomOrDoorRemovesTheHologram() {
        stand(alex, 6);
        plugin.doorHolograms().tick();
        assertEquals(1, plugin.doorHolograms().spawnedCount());
        plugin.plots().remove(room);
        plugin.doorHolograms().tick();
        assertEquals(0, plugin.doorHolograms().spawnedCount());
        assertFalse(fakes.get(0).valid);
    }

    @Test
    void canBeDisabledAndRespectsTheViewDistance() {
        plugin.getConfig().set("holograms.door-view-distance", 4);
        stand(alex, 14);
        plugin.doorHolograms().tick();
        assertEquals(0, plugin.doorHolograms().spawnedCount(), "дальше заданной дистанции");
        stand(alex, 6);
        plugin.doorHolograms().tick();
        assertEquals(1, plugin.doorHolograms().spawnedCount());
        plugin.getConfig().set("holograms.doors", false);
        plugin.doorHolograms().tick();
        assertEquals(0, plugin.doorHolograms().spawnedCount());
    }
}
