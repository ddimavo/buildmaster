package ru.industryreborn.util;

import java.util.ArrayList;
import java.util.List;
import ru.industryreborn.model.Box;

/** Точки контура области (12 рёбер по границам блоков) для частиц. Чистая геометрия. */
public final class BoxOutline {

    private BoxOutline() { }

    /** Длина всех рёбер области. */
    public static double edgeLength(Box box) {
        double x = box.sizeX();
        double y = box.sizeY();
        double z = box.sizeZ();
        return 4 * (x + y + z);
    }

    /**
     * Точки по рёбрам области. Шаг растёт для больших областей, чтобы точек было не больше maxPoints (но не меньше одной на блок).
     * Координаты - границы блоков: от minX до maxX + 1.
     */
    public static List<double[]> edgePoints(Box box, int maxPoints) {
        double step = Math.max(1.0, edgeLength(box) / Math.max(12, maxPoints));
        double x0 = box.minX();
        double y0 = box.minY();
        double z0 = box.minZ();
        double x1 = box.maxX() + 1.0;
        double y1 = box.maxY() + 1.0;
        double z1 = box.maxZ() + 1.0;
        List<double[]> points = new ArrayList<>();
        for (double y : new double[] {y0, y1}) {
            for (double z : new double[] {z0, z1}) {
                line(points, x0, y, z, x1, y, z, step);
            }
        }
        for (double x : new double[] {x0, x1}) {
            for (double z : new double[] {z0, z1}) {
                line(points, x, y0, z, x, y1, z, step);
            }
        }
        for (double x : new double[] {x0, x1}) {
            for (double y : new double[] {y0, y1}) {
                line(points, x, y, z0, x, y, z1, step);
            }
        }
        return points;
    }

    /** Восемь углов области. */
    public static List<double[]> corners(Box box) {
        List<double[]> list = new ArrayList<>();
        for (double x : new double[] {box.minX(), box.maxX() + 1.0}) {
            for (double y : new double[] {box.minY(), box.maxY() + 1.0}) {
                for (double z : new double[] {box.minZ(), box.maxZ() + 1.0}) {
                    list.add(new double[] {x, y, z});
                }
            }
        }
        return list;
    }

    private static void line(List<double[]> out, double ax, double ay, double az, double bx, double by, double bz, double step) {
        double length = Math.abs(bx - ax) + Math.abs(by - ay) + Math.abs(bz - az);
        int n = Math.max(1, (int) Math.round(length / step));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            out.add(new double[] {ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t});
        }
    }
}
