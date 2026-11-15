package ru.buildmaster.model;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.block.data.BlockData;

/** Исходный вид здания: палитра блоков + индексы по областям. Хранится в файле. */
public final class Snapshot {

    private static final int VERSION = 1;

    public final Box box;
    public final String[] palette;
    private final int[] data;

    private Snapshot(Box box, String[] palette, int[] data) {
        this.box = box;
        this.palette = palette;
        this.data = data;
    }

    /** Индекс палитры для блока; координаты должны лежать внутри области. */
    public int paletteIndex(int x, int y, int z) {
        return data[index(box, x, y, z)];
    }

    private static int index(Box box, int x, int y, int z) {
        return ((y - box.minY()) * box.sizeZ() + (z - box.minZ())) * box.sizeX() + (x - box.minX());
    }

    public void write(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(tmp))))) {
            out.writeInt(VERSION);
            out.writeUTF(box.serialize());
            out.writeInt(palette.length);
            for (String s : palette) {
                out.writeUTF(s);
            }
            out.writeInt(data.length);
            for (int v : data) {
                out.writeInt(v);
            }
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("Не удалось заменить " + file);
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("Не удалось переименовать " + tmp);
        }
    }

    public static Snapshot read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file))))) {
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("Неизвестная версия снимка: " + version);
            }
            Box box = Box.parse(in.readUTF());
            String[] palette = new String[in.readInt()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = in.readUTF();
            }
            int[] data = new int[in.readInt()];
            for (int i = 0; i < data.length; i++) {
                data[i] = in.readInt();
            }
            if (data.length != box.volume()) {
                throw new IOException("Повреждённый снимок: размер не совпадает с областью");
            }
            return new Snapshot(box, palette, data);
        }
    }

    /** Накопитель блоков при создании снимка. */
    public static final class Builder {
        private final Box box;
        private final int[] data;
        private final Map<BlockData, Integer> ids = new HashMap<>();
        private final List<BlockData> list = new ArrayList<>();

        public Builder(Box box) {
            if (box.volume() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Слишком большая область");
            }
            this.box = box;
            this.data = new int[(int) box.volume()];
        }

        public void set(int x, int y, int z, BlockData block) {
            Integer id = ids.get(block);
            if (id == null) {
                id = list.size();
                ids.put(block, id);
                list.add(block);
            }
            data[index(box, x, y, z)] = id;
        }

        public Snapshot build() {
            String[] palette = new String[list.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = list.get(i).getAsString();
            }
            return new Snapshot(box, palette, data);
        }
    }
}
