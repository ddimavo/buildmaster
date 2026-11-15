package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ArmorStand;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import ru.buildmaster.model.Box;

/**
 * Исходный вид здания хранится как структуры Minecraft (по одному файлу на чанк).
 * В отличие от простого списка блоков структуры сохраняют данные блоков:
 * текст табличек, баннеры, головы, горшки, спавнеры и т.д.
 */
public class SnapshotStore {

    private final Random random = new Random();

    private StructureManager manager() {
        return Bukkit.getStructureManager();
    }

    private static File chunkFile(File dir, int cx, int cz) {
        return new File(dir, cx + "_" + cz + ".nbt");
    }

    /** Сохраняет блоки области (внутри одного чанка) в файл структуры. */
    public void capture(File dir, World world, int cx, int cz, Box clip) throws IOException {
        dir.mkdirs();
        Structure structure = manager().createStructure();
        structure.fill(new Location(world, clip.minX(), clip.minY(), clip.minZ()),
                new Location(world, clip.maxX(), clip.maxY(), clip.maxZ()), false);
        manager().saveStructure(chunkFile(dir, cx, cz), structure);
    }

    /** Вставляет сохранённый кусок обратно. false - файла для этого чанка нет. */
    public boolean place(File dir, World world, int cx, int cz, Box clip) throws IOException {
        File file = chunkFile(dir, cx, cz);
        if (!file.exists()) {
            return false;
        }
        Structure structure = manager().loadStructure(file);
        structure.place(new Location(world, clip.minX(), clip.minY(), clip.minZ()), false,
                StructureRotation.NONE, Mirror.NONE, -1, 1.0f, random);
        return true;
    }

    /** Исходные рамки, картины и стойки: при сбросе они остаются, а добавленные игроками убираются. */
    public static Set<UUID> originalEntities(World world, Box box) {
        Set<UUID> ids = new HashSet<>();
        for (Entity e : world.getNearbyEntities(new org.bukkit.util.BoundingBox(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1), x -> x instanceof Hanging || x instanceof ArmorStand)) {
            ids.add(e.getUniqueId());
        }
        return ids;
    }

    public void saveMeta(File dir, Box box, Set<UUID> originalEntities) throws IOException {
        dir.mkdirs();
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("box", box.serialize());
        yml.set("entities", originalEntities.stream().map(UUID::toString).toList());
        yml.save(new File(dir, "meta.yml"));
    }

    public Set<UUID> loadOriginalEntities(File dir) {
        Set<UUID> ids = new HashSet<>();
        File f = new File(dir, "meta.yml");
        if (f.exists()) {
            List<String> list = YamlConfiguration.loadConfiguration(f).getStringList("entities");
            for (String s : list) {
                ids.add(UUID.fromString(s));
            }
        }
        return ids;
    }

    public boolean exists(File dir) {
        return new File(dir, "meta.yml").exists();
    }

    public void delete(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }
}
