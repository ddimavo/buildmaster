package ru.industryreborn.model;

/** Неизменяемый параллелепипед в координатах блоков (границы включительно). */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(Box o) {
        return o.minX >= minX && o.maxX <= maxX && o.minY >= minY && o.maxY <= maxY
                && o.minZ >= minZ && o.maxZ <= maxZ;
    }

    public boolean intersects(Box o) {
        return minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    /** Пересечение двух областей или null, если они не пересекаются. */
    public Box intersection(Box o) {
        if (!intersects(o)) {
            return null;
        }
        return new Box(Math.max(minX, o.minX), Math.max(minY, o.minY), Math.max(minZ, o.minZ),
                Math.min(maxX, o.maxX), Math.min(maxY, o.maxY), Math.min(maxZ, o.maxZ));
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public double centerX() {
        return (minX + maxX + 1) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ + 1) / 2.0;
    }

    public String serialize() {
        return minX + "," + minY + "," + minZ + "," + maxX + "," + maxY + "," + maxZ;
    }

    public static Box parse(String s) {
        String[] p = s.split(",");
        if (p.length != 6) {
            throw new IllegalArgumentException("Неверный формат области: " + s);
        }
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) {
            v[i] = Integer.parseInt(p[i].trim());
        }
        return new Box(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    public String describe() {
        return sizeX() + "×" + sizeY() + "×" + sizeZ();
    }
}
