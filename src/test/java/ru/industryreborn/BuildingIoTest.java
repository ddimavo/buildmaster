package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.building.BuildingTemplate;
import ru.industryreborn.building.Nbt;
import ru.industryreborn.building.SchematicImporter;
import ru.industryreborn.model.Box;
import ru.industryreborn.service.Module;
import ru.industryreborn.support.PluginTestBase;

/** Сохранение здания из игры и импорт .schem / .nbt. */
class BuildingIoTest extends PluginTestBase {

    /** Минимальный писатель NBT для тестов. */
    private static final class W {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bos);

        W tag(int type, String name) throws IOException {
            out.writeByte(type);
            out.writeUTF(name);
            return this;
        }

        W root(String name) throws IOException {
            return tag(10, name);
        }

        W compound(String name) throws IOException {
            return tag(10, name);
        }

        W end() throws IOException {
            out.writeByte(0);
            return this;
        }

        W str(String name, String v) throws IOException {
            tag(8, name);
            out.writeUTF(v);
            return this;
        }

        W i(String name, int v) throws IOException {
            tag(3, name);
            out.writeInt(v);
            return this;
        }

        W s(String name, int v) throws IOException {
            tag(2, name);
            out.writeShort(v);
            return this;
        }

        W bytes(String name, int... v) throws IOException {
            tag(7, name);
            out.writeInt(v.length);
            for (int b : v) {
                out.writeByte(b);
            }
            return this;
        }

        W listInts(String name, int... v) throws IOException {
            tag(9, name);
            out.writeByte(3);
            out.writeInt(v.length);
            for (int x : v) {
                out.writeInt(x);
            }
            return this;
        }

        W listOfCompounds(String name, int count) throws IOException {
            tag(9, name);
            out.writeByte(10);
            out.writeInt(count);
            return this;
        }

        byte[] gz() throws IOException {
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(z)) {
                g.write(bos.toByteArray());
            }
            return z.toByteArray();
        }

        byte[] raw() {
            return bos.toByteArray();
        }
    }

    private PlayerMock boss;
    private World world;

    @BeforeEach
    void setUp() {
        boss = admin("Boss");
        world = Bukkit.getWorld("world");
        boss.teleport(new org.bukkit.Location(world, 1600, 10, 1600));
    }

    private void write(String name, byte[] data) throws IOException {
        Files.write(new File(plugin.buildings().importFolder(), name).toPath(), data);
    }

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ формат

    @Test
    void roundTripThroughJson() throws IOException {
        String[][][] g = new String[2][2][3];
        g[0][0][0] = "minecraft:stone";
        g[0][1][2] = "minecraft:oak_stairs[facing=north,half=bottom]";
        g[1][0][1] = "minecraft:stone";
        BuildingTemplate t = BuildingTemplate.fromGrid("rt", "Тест", "shop", "описание", null, g);
        assertEquals(3, t.sizeX());
        assertEquals(2, t.sizeY());
        assertEquals(2, t.sizeZ());
        BuildingTemplate back = BuildingTemplate.parse(new StringReader(t.toJson()));
        assertEquals("Тест", back.title());
        assertEquals("shop", back.category());
        assertEquals("minecraft:stone_bricks", back.foundation());
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 3; x++) {
                    assertEquals(g[y][z][x], back.blockAt(x, y, z), x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void emptyTopLayersAreDroppedAndEmptyBuildingRejected() throws IOException {
        String[][][] g = new String[5][1][1];
        g[1][0][0] = "minecraft:stone";
        assertEquals(2, BuildingTemplate.fromGrid("a", null, null, null, null, g).sizeY());
        assertThrows(IOException.class, () -> BuildingTemplate.fromGrid("a", null, null, null, null, new String[3][2][2]));
        assertThrows(IOException.class, () -> BuildingTemplate.fromGrid("a", null, null, null, null, new String[1][1][65]));
    }

    @Test
    void manyDistinctBlocksUseTheWholePalettePool() throws IOException {
        String[][][] g = new String[1][20][30];
        for (int z = 0; z < 20; z++) {
            for (int x = 0; x < 30; x++) {
                g[0][z][x] = "minecraft:block_" + (z * 30 + x);   // 600 разных блоков
            }
        }
        // больше символов в палитре, чем отведено - отказ, а не порча файла
        assertThrows(IOException.class, () -> BuildingTemplate.fromGrid("a", null, null, null, null, g));
        String[][][] ok = new String[1][10][30];
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x < 30; x++) {
                ok[0][z][x] = "minecraft:block_" + (z * 30 + x);   // 300 разных: нужны не только ASCII-символы
            }
        }
        BuildingTemplate t = BuildingTemplate.fromGrid("a", null, null, null, null, ok);
        BuildingTemplate back = BuildingTemplate.parse(new StringReader(t.toJson()));
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x < 30; x++) {
                assertEquals("minecraft:block_" + (z * 30 + x), back.blockAt(x, 0, z));
            }
        }
    }

    // ------------------------------------------------------------ NBT

    @Test
    void nbtReaderHandlesGzipAndRaw() throws IOException {
        W w = new W().root("").str("a", "привет").i("n", 7).s("s", 3).listInts("l", 1, 2, 3).bytes("b", 1, 2).compound("c").i("x", 1).end().end();
        for (byte[] data : new byte[][] {w.raw(), w.gz()}) {
            Map<String, Object> m = Nbt.read(data);
            assertEquals("привет", m.get("a"));
            assertEquals(7, Nbt.intOf(m.get("n"), -1));
            assertEquals(3, Nbt.intOf(m.get("s"), -1));
            assertEquals(java.util.List.of(1, 2, 3), m.get("l"));
            assertArrayEquals(new byte[] {1, 2}, (byte[]) m.get("b"));
            assertEquals(1, Nbt.intOf(Nbt.map(m.get("c")).get("x"), -1));
        }
        assertThrows(IOException.class, () -> Nbt.read(new byte[] {1, 2, 3}));
        assertThrows(IOException.class, () -> Nbt.read(new byte[] {10, 0, 0, 1}), "оборванный файл");
    }

    // ------------------------------------------------------------ импорт

    /** 3x2x3: нижний слой - камень с дыркой в середине, на верхнем - ступенька в углу. Индекс = x + z*W + y*W*L. */
    private byte[] spongeBlocks() {
        int[] data = new int[18];
        for (int i = 0; i < 9; i++) {
            data[i] = 1;
        }
        data[1 + 3] = 0;        // (1,0,1) - воздух
        data[9] = 2;            // (0,1,0) - ступенька
        return toBytes(data);
    }

    private static byte[] toBytes(int[] v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    private static int[] ints(byte[] b) {
        int[] v = new int[b.length];
        for (int i = 0; i < b.length; i++) {
            v[i] = b[i];
        }
        return v;
    }

    private byte[] spongeV2() throws IOException {
        return new W().root("Schematic").i("Version", 2).s("Width", 3).s("Height", 2).s("Length", 3)
                .compound("Palette").i("minecraft:air", 0).i("minecraft:stone", 1).i("minecraft:oak_stairs[facing=north]", 2).end()
                .bytes("BlockData", ints(spongeBlocks())).end().gz();
    }

    private byte[] spongeV3() throws IOException {
        return new W().root("").compound("Schematic").i("Version", 3).s("Width", 3).s("Height", 2).s("Length", 3)
                .compound("Blocks").compound("Palette").i("minecraft:air", 0).i("minecraft:stone", 1).i("minecraft:oak_stairs[facing=north]", 2).end()
                .bytes("Data", ints(spongeBlocks())).end().end().end().gz();
    }

    private void checkSponge(BuildingTemplate t) {
        assertEquals(3, t.sizeX());
        assertEquals(2, t.sizeY());
        assertEquals(3, t.sizeZ());
        assertEquals("minecraft:stone", t.blockAt(0, 0, 0));
        assertEquals("minecraft:stone", t.blockAt(2, 0, 2));
        assertNull(t.blockAt(1, 0, 1), "воздух");
        assertEquals("minecraft:oak_stairs[facing=north]", t.blockAt(0, 1, 0));
        assertNull(t.blockAt(1, 1, 1));
        assertEquals(9 - 1 + 1, t.blockCount());
    }

    @Test
    void importSpongeV2AndV3() throws IOException {
        write("a.schem", spongeV2());
        write("b.schem", spongeV3());
        assertNull(plugin.buildings().importFile("a.schem", "imp-a", "shop", "Импорт А"));
        assertNull(plugin.buildings().importFile("b.schem", "imp-b", "HOUSE", null));
        checkSponge(plugin.buildings().get("imp-a"));
        checkSponge(plugin.buildings().get("imp-b"));
        assertEquals("Импорт А", plugin.buildings().get("imp-a").title());
        assertEquals("shop", plugin.buildings().get("imp-a").category());
        assertEquals("house", plugin.buildings().get("imp-b").category(), "тип нормализуется");
        // записанный файл читается заново
        File f = new File(plugin.buildings().folder(), "imp-a.ir.json");
        assertTrue(f.isFile());
        plugin.buildings().load();
        checkSponge(plugin.buildings().get("imp-a"));
    }

    @Test
    void spongeVarintPaletteIndexesAbove127() throws IOException {
        // индекс 300 кодируется двумя байтами: 0xAC 0x02
        byte[] file = new W().root("Schematic").i("Version", 2).s("Width", 2).s("Height", 1).s("Length", 1)
                .compound("Palette").i("minecraft:air", 0).i("minecraft:dirt", 300).end().bytes("BlockData", 0xAC, 0x02, 0).end().raw();
        write("v.schem", file);
        assertNull(plugin.buildings().importFile("v.schem", "varint", "house", null));
        BuildingTemplate t = plugin.buildings().get("varint");
        assertEquals("minecraft:dirt", t.blockAt(0, 0, 0));
        assertNull(t.blockAt(1, 0, 0));
    }

    @Test
    void spongeAxisOrderWithNonSquareSize() throws IOException {
        // 3 в ширину, 2 в глубину, 2 в высоту: индекс = x + z*3 + y*6; блок только в (2,1,1) - индекс 2 + 3 + 6 = 11
        int[] data = new int[12];
        data[11] = 1;
        data[0] = 2;
        write("n.schem", new W().root("Schematic").i("Version", 2).s("Width", 3).s("Height", 2).s("Length", 2)
                .compound("Palette").i("minecraft:air", 0).i("minecraft:stone", 1).i("minecraft:dirt", 2).end().bytes("BlockData", data).end().raw());
        assertNull(plugin.buildings().importFile("n.schem", "axes", "house", null));
        BuildingTemplate t = plugin.buildings().get("axes");
        assertEquals(3, t.sizeX());
        assertEquals(2, t.sizeY());
        assertEquals(2, t.sizeZ());
        assertEquals("minecraft:stone", t.blockAt(2, 1, 1));
        assertEquals("minecraft:dirt", t.blockAt(0, 0, 0));
        assertEquals(2, t.blockCount());
    }

    @Test
    void structureBlockOutsideItsSizeIsRejected() throws IOException {
        W w = new W().root("").listInts("size", 2, 2, 2);
        w.tag(9, "palette");
        w.out.writeByte(10);
        w.out.writeInt(1);
        w.str("Name", "minecraft:stone").end();
        w.listOfCompounds("blocks", 1);
        w.listInts("pos", 5, 0, 0).i("state", 0).end();
        write("o.nbt", w.end().raw());
        String error = plugin.buildings().importFile("o.nbt", "outside", "house", null);
        assertNotNull(error);
        assertTrue(error.contains("вне размера"), error);
    }

    @Test
    void siblingFolderWithSimilarNameIsNotReachable() throws IOException {
        File evil = new File(plugin.getDataFolder(), "import-evil");
        evil.mkdirs();
        Files.write(new File(evil, "e.nbt").toPath(), structure());
        String error = plugin.buildings().importFile("../import-evil/e.nbt", "evil", "house", null);
        assertNotNull(error);
        assertTrue(error.contains("не найден"), error);
        assertNull(plugin.buildings().get("evil"));
    }

    private byte[] structure() throws IOException {
        W w = new W().root("").i("DataVersion", 3955).listInts("size", 3, 2, 3);
        w.tag(9, "palette");
        w.out.writeByte(10);
        w.out.writeInt(3);
        w.str("Name", "minecraft:stone").end();
        w.str("Name", "minecraft:oak_stairs").compound("Properties").str("half", "bottom").str("facing", "north").end().end();
        w.str("Name", "minecraft:structure_void").end();
        w.listOfCompounds("blocks", 4);
        w.listInts("pos", 0, 0, 0).i("state", 0).end();
        w.listInts("pos", 2, 0, 2).i("state", 0).end();
        w.listInts("pos", 0, 1, 0).i("state", 1).end();
        w.listInts("pos", 1, 0, 1).i("state", 2).end();
        return w.end().gz();
    }

    @Test
    void importVanillaStructure() throws IOException {
        write("s.nbt", structure());
        assertNull(plugin.buildings().importFile("s.nbt", "from-nbt", "workshop", "Из структуры"));
        BuildingTemplate t = plugin.buildings().get("from-nbt");
        assertEquals(3, t.sizeX());
        assertEquals(2, t.sizeY());
        assertEquals("minecraft:stone", t.blockAt(0, 0, 0));
        assertEquals("minecraft:stone", t.blockAt(2, 0, 2));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", t.blockAt(0, 1, 0), "свойства собираются в blockdata");
        assertNull(t.blockAt(1, 0, 1), "structure_void - пустота");
        assertNull(t.blockAt(1, 0, 0), "клетки без записи - воздух");
        assertEquals(3, t.blockCount());
    }

    @Test
    void importRefusals() throws IOException {
        write("ok.nbt", structure());
        write("junk.nbt", new byte[] {1, 2, 3, 4, 5});
        write("old.schematic", new byte[] {0});
        write("txt.txt", new byte[] {0});
        write("big.nbt", bigStructure());
        var svc = plugin.buildings();
        assertTrue(svc.importFile("ok.nbt", "Bad Name", "house", null).contains("латиница"));
        assertTrue(svc.importFile("ok.nbt", "x", "замок", null).contains("Неизвестный тип"));
        assertTrue(svc.importFile("net.nbt", "x", "house", null).contains("не найден"));
        assertTrue(svc.importFile("../ok.nbt", "x", "house", null).contains("не найден"), "выход из папки запрещён");
        assertTrue(svc.importFile("junk.nbt", "x", "house", null).contains("Не удалось"));
        assertTrue(svc.importFile("old.schematic", "x", "house", null).contains("schem"));
        assertTrue(svc.importFile("txt.txt", "x", "house", null).contains("Не удалось"));
        assertTrue(svc.importFile("big.nbt", "x", "house", null).contains("64"));
        assertNull(svc.get("x"));
        assertNull(svc.importFile("ok.nbt", "first", "house", null));
        assertTrue(svc.importFile("ok.nbt", "first", "house", null).contains("уже есть"), "имя занято");
        assertTrue(svc.importFile("ok.nbt", "bank", "house", null).contains("уже есть"), "встроенное имя занято");
        // оборванные данные блоков
        write("cut.schem", new W().root("Schematic").i("Version", 2).s("Width", 3).s("Height", 2).s("Length", 3)
                .compound("Palette").i("minecraft:air", 0).i("minecraft:stone", 1).end().bytes("BlockData", 1, 1, 1).end().raw());
        assertTrue(svc.importFile("cut.schem", "cut", "house", null).contains("меньше"));
    }

    private byte[] bigStructure() throws IOException {
        W w = new W().root("").listInts("size", 65, 2, 3);
        w.tag(9, "palette");
        w.out.writeByte(10);
        w.out.writeInt(1);
        w.str("Name", "minecraft:stone").end();
        w.listOfCompounds("blocks", 0);
        return w.end().raw();
    }

    @Test
    void importedFileListAndCommand() throws IOException {
        write("zz.nbt", structure());
        assertEquals(java.util.List.of("zz.nbt"), plugin.buildings().importable());
        boss.performCommand("district build import list");
        assertTrue(drain(boss).contains("zz.nbt"));
        boss.performCommand("district build import zz.nbt cmd-imp house Мой импорт");
        String out = drain(boss);
        assertTrue(out.contains("импортировано"), out);
        assertEquals("Мой импорт", plugin.buildings().get("cmd-imp").title());
        boss.performCommand("district build import zz.nbt cmd-imp house");
        assertTrue(drain(boss).contains("уже есть"));
        boss.performCommand("district build import zz.nbt");
        assertTrue(drain(boss).contains("Использование"));
        PlayerMock steve = player("Steve", 0);
        steve.performCommand("district build import zz.nbt other house");
        assertNull(plugin.buildings().get("other"), "без прав администратора - нельзя");
        plugin.modules().set(Module.AUTO_BUILD, false, "test");
        drain(boss);
        boss.performCommand("district build import zz.nbt third house");
        assertTrue(drain(boss).toLowerCase().contains("отключ"));
        assertNull(plugin.buildings().get("third"));
    }

    // ------------------------------------------------------------ сохранение

    private void select(int x1, int y1, int z1, int x2, int y2, int z2) {
        plugin.selection().setPoint(boss, 1, x1, y1, z1);
        plugin.selection().setPoint(boss, 2, x2, y2, z2);
    }

    @Test
    void saveBuiltStructure() {
        world.getBlockAt(10, 5, 10).setType(Material.STONE);
        world.getBlockAt(12, 5, 12).setType(Material.OAK_PLANKS);
        world.getBlockAt(11, 6, 11).setType(Material.GLASS);
        select(10, 5, 10, 12, 7, 12);
        boss.performCommand("district build save my-house house Мой домик");
        String out = drain(boss);
        assertTrue(out.contains("сохранено"), out);
        BuildingTemplate t = plugin.buildings().get("my-house");
        assertEquals("Мой домик", t.title());
        assertEquals("house", t.category());
        assertEquals(3, t.sizeX());
        assertEquals(2, t.sizeY(), "пустой верхний слой отброшен");
        assertEquals(3, t.sizeZ());
        assertEquals("minecraft:stone", t.blockAt(0, 0, 0));
        assertEquals("minecraft:oak_planks", t.blockAt(2, 0, 2));
        assertEquals("minecraft:glass", t.blockAt(1, 1, 1));
        assertNull(t.blockAt(1, 0, 1));
        assertEquals(3, t.blockCount());
        assertTrue(new File(plugin.buildings().folder(), "my-house.ir.json").isFile());
        plugin.buildings().load();
        assertEquals("minecraft:glass", plugin.buildings().get("my-house").blockAt(1, 1, 1));
    }

    @Test
    void saveRefusals() {
        world.getBlockAt(10, 5, 10).setType(Material.STONE);
        select(10, 5, 10, 12, 7, 12);
        var svc = plugin.buildings();
        boss.performCommand("district build save bank house");
        assertTrue(drain(boss).contains("уже есть"));
        boss.performCommand("district build save Name house");        // имя приводится к нижнему регистру
        assertTrue(drain(boss).contains("сохранено"));
        boss.performCommand("district build save bad_имя house");
        assertTrue(drain(boss).contains("латиница"));
        boss.performCommand("district build save okay замок");
        assertTrue(drain(boss).contains("Неизвестный тип"));
        boss.performCommand("district build save okay");
        assertTrue(drain(boss).contains("Использование"));
        select(30, 5, 30, 32, 7, 32);                                // пустая область
        boss.performCommand("district build save empty house");
        assertTrue(drain(boss).contains("нет ни одного блока"));
        select(0, 5, 0, 64, 7, 5);                                    // 65 в ширину
        boss.performCommand("district build save wide house");
        assertTrue(drain(boss).contains("слишком велика"));
        assertNull(svc.get("empty"));
        assertNull(svc.get("wide"));
        assertNull(svc.get("okay"));
        PlayerMock steve = player("Steve", 0);
        steve.performCommand("district build save by-steve house");
        assertNull(svc.get("by-steve"));
        PlayerMock noSel = admin("NoSel");
        noSel.performCommand("district build save nosel house");
        assertTrue(drain(noSel).contains("выделите"));
        plugin.modules().set(Module.AUTO_BUILD, false, "test");
        select(10, 5, 10, 12, 7, 12);
        boss.performCommand("district build save blocked house");
        assertNull(svc.get("blocked"));
    }

    @Test
    void savedBuildingCanBeBuilt() {
        world.getBlockAt(10, 5, 10).setType(Material.STONE);
        world.getBlockAt(11, 5, 10).setType(Material.STONE);
        select(10, 5, 10, 11, 5, 10);
        boss.performCommand("district build save tiny2 shop");
        var t = plugin.buildings().get("tiny2");
        assertNotNull(t);
        world.getBlockAt(10, 5, 10).setType(Material.AIR);
        world.getBlockAt(11, 5, 10).setType(Material.AIR);
        var placement = new ru.industryreborn.building.BuildingService.Placement(20, 5, 20, 0, t.sizeX(), t.sizeZ());
        plugin.getConfig().set("auto-build.synchronous", true);
        plugin.buildings().build(world, t, placement, () -> { }, e -> fail(e));
        assertEquals(Material.STONE, world.getBlockAt(20, 5, 20).getType());
        assertEquals(Material.STONE, world.getBlockAt(21, 5, 20).getType());
    }

    @Test
    void schematicGridDirectReadErrors() {
        assertThrows(IOException.class, () -> SchematicImporter.read("a.txt", new java.io.ByteArrayInputStream(new byte[0])));
        assertThrows(IOException.class, () -> SchematicImporter.read("a.nbt", new java.io.ByteArrayInputStream(new W().root("").i("x", 1).end().raw())));
    }
}
