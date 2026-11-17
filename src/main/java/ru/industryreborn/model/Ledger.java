package ru.industryreborn.model;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Книга учёта блоков участка: кто какой блок поставил. По ней при сбросе здания
 * предметы возвращаются именно тем игрокам, которые их разместили.
 * Исходные блоки здания в книге не числятся.
 */
public final class Ledger {

    /** Кто, что и сколько раз поставил в этой позиции (плиты, свечи и т.п. ставятся в один блок несколько раз). */
    public record Entry(UUID player, String material, int amount) { }

    private final Map<Long, Entry> entries = new HashMap<>();

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int unpackX(long key) {
        return (int) (key >> 38);
    }

    public static int unpackZ(long key) {
        return (int) (key << 26 >> 38);
    }

    public static int unpackY(long key) {
        return (int) (key << 52 >> 52);
    }

    /**
     * Записывает установку блока. stacking - предмет добавлен к уже стоящему блоку того же вида
     * (вторая плита, ещё одна свеча): тогда количество растёт, иначе запись заменяется.
     */
    public void place(int x, int y, int z, UUID player, String material, boolean stacking) {
        long key = pack(x, y, z);
        Entry old = entries.get(key);
        int amount = 1;
        if (stacking && old != null && old.player().equals(player) && old.material().equals(material)) {
            amount = old.amount() + 1;
        }
        entries.put(key, new Entry(player, material, amount));
    }

    public Entry remove(int x, int y, int z) {
        return entries.remove(pack(x, y, z));
    }

    public Entry get(int x, int y, int z) {
        return entries.get(pack(x, y, z));
    }

    public boolean contains(int x, int y, int z) {
        return entries.containsKey(pack(x, y, z));
    }

    public int size() {
        return entries.size();
    }

    public Map<Long, Entry> entries() {
        return entries;
    }

    public void clear() {
        entries.clear();
    }

    public void write(DataOutput out) throws IOException {
        out.writeInt(entries.size());
        for (Map.Entry<Long, Entry> e : entries.entrySet()) {
            out.writeLong(e.getKey());
            out.writeLong(e.getValue().player().getMostSignificantBits());
            out.writeLong(e.getValue().player().getLeastSignificantBits());
            out.writeUTF(e.getValue().material());
            out.writeInt(e.getValue().amount());
        }
    }

    public static Ledger read(DataInput in) throws IOException {
        Ledger ledger = new Ledger();
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            long key = in.readLong();
            UUID player = new UUID(in.readLong(), in.readLong());
            ledger.entries.put(key, new Entry(player, in.readUTF(), in.readInt()));
        }
        return ledger;
    }
}
