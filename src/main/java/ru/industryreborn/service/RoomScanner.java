package ru.industryreborn.service;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import ru.industryreborn.model.Box;

/**
 * Поиск интерьера номера: заливка свободного пространства от клетки за дверью. Область замкнута, если заливка
 * не вышла за пределы отеля и не превысила лимит клеток. Чистая логика: проходимость клетки задаёт вызывающий.
 */
public final class RoomScanner {

    @FunctionalInterface
    public interface Fillable {
        boolean test(int x, int y, int z);
    }

    /** Итог: область (если нашлась), число клеток либо причина неудачи. */
    public record Result(Box box, int cells, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private RoomScanner() { }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * @param bounds  область отеля: выход за неё означает, что комната не замкнута
     * @param maxCells предельное число клеток интерьера
     */
    public static Result scan(Fillable fillable, int sx, int sy, int sz, Box bounds, int maxCells) {
        if (!bounds.contains(sx, sy, sz)) {
            return new Result(null, 0, "За дверью находится не отель: вы стоите с неправильной стороны или дверь снаружи здания.");
        }
        if (!fillable.test(sx, sy, sz)) {
            return new Result(null, 0, "За дверью нет свободного места (стена или мебель вплотную).");
        }
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {sx, sy, sz});
        seen.add(key(sx, sy, sz));
        int minX = sx;
        int minY = sy;
        int minZ = sz;
        int maxX = sx;
        int maxY = sy;
        int maxZ = sz;
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] d : dirs) {
                int x = c[0] + d[0];
                int y = c[1] + d[1];
                int z = c[2] + d[2];
                if (!fillable.test(x, y, z)) {
                    continue;
                }
                if (!bounds.contains(x, y, z)) {
                    return new Result(null, seen.size(), "Комната не замкнута: свободное пространство выходит за пределы отеля (окно, дыра или открытая дверь).");
                }
                if (!seen.add(key(x, y, z))) {
                    continue;
                }
                if (seen.size() > maxCells) {
                    return new Result(null, seen.size(), "Слишком большое или незамкнутое пространство: больше " + maxCells
                            + " клеток. Закройте проёмы или создайте номер вручную.");
                }
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
                queue.add(new int[] {x, y, z});
            }
        }
        return new Result(new Box(minX, minY, minZ, maxX, maxY, maxZ), seen.size(), null);
    }
}
