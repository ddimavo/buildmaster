package ru.industryreborn.building;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;

/**
 * Изометрическое превью здания (для HTML-меню): кубы с тремя оттенками граней, цвета - карта цветов блока.
 * Обычный вид либо разрез (убран ближний угол, виден интерьер). Рисуется на Java2D без окна, работает на сервере без экрана.
 */
public final class PreviewRenderer {

    private PreviewRenderer() {
    }

    /** Цвета, которых нет в карте цветов (стекло и т.п.) или которые на ней слишком блеклые. */
    private static final Map<String, Color> OVERRIDES = new HashMap<>();

    static {
        OVERRIDES.put("glass", new Color(170, 210, 230));
        OVERRIDES.put("glass_pane", new Color(170, 210, 230));
        OVERRIDES.put("lantern", new Color(255, 200, 90));
        OVERRIDES.put("iron_bars", new Color(140, 140, 145));
        OVERRIDES.put("oak_fence", new Color(176, 140, 85));
        OVERRIDES.put("bookshelf", new Color(150, 115, 70));
        OVERRIDES.put("barrel", new Color(140, 100, 55));
    }

    private static final Map<String, Color> CACHE = new HashMap<>();

    static Color colorOf(String data) {
        String id = data.split("\\[")[0].toLowerCase(Locale.ROOT).replace("minecraft:", "");
        Color c = OVERRIDES.get(id);
        if (c != null) {
            return c;
        }
        return CACHE.computeIfAbsent(id, key -> {
            try {
                Material m = Material.matchMaterial("minecraft:" + key);
                if (m != null && m.isBlock()) {
                    org.bukkit.Color mc = Bukkit.createBlockData(m).getMapColor();
                    if (mc != null && (mc.getRed() + mc.getGreen() + mc.getBlue()) > 0) {
                        return new Color(mc.getRed(), mc.getGreen(), mc.getBlue());
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                // нет данных о блоке - ниже цвет по названию
            }
            return byName(key);
        });
    }

    /** Цвет по названию блока - когда карта цветов недоступна. */
    private static final String[][] NAMES = {
        {"dark_oak", "70,50,30"}, {"spruce", "114,84,48"}, {"birch", "192,175,121"}, {"cherry", "226,178,172"}, {"mangrove", "118,54,52"},
        {"acacia", "168,90,50"}, {"jungle", "160,115,80"}, {"oak", "196,158,98"}, {"deepslate", "70,70,74"}, {"copper", "192,107,80"},
        {"nether_brick", "70,28,32"}, {"mud_brick", "137,104,79"}, {"brick", "150,85,70"}, {"quartz", "235,230,222"}, {"sandstone", "218,203,145"},
        {"terracotta", "190,120,90"}, {"concrete", "200,200,200"}, {"wool", "220,220,220"}, {"glass", "170,210,230"}, {"grass", "95,150,60"},
        {"dirt", "134,96,67"}, {"iron", "216,216,216"}, {"gold", "246,208,61"}, {"redstone", "170,20,10"}, {"stone", "125,125,125"},
        {"cobble", "110,110,110"}, {"andesite", "132,136,132"}, {"hay", "200,170,40"}, {"fence", "176,140,85"}, {"furnace", "120,120,120"},
        {"lantern", "255,200,90"}, {"door", "140,100,60"}, {"barrel", "140,100,55"}, {"chest", "170,120,50"}, {"bookshelf", "150,115,70"},
    };

    private static Color byName(String key) {
        for (String[] e : NAMES) {
            if (key.contains(e[0])) {
                String[] rgb = e[1].split(",");
                return new Color(Integer.parseInt(rgb[0]), Integer.parseInt(rgb[1]), Integer.parseInt(rgb[2]));
            }
        }
        int h = key.hashCode();
        return new Color(100 + Math.floorMod(h, 100), 100 + Math.floorMod(h >> 8, 100), 100 + Math.floorMod(h >> 16, 100));
    }

    private static Color shade(Color c, double f) {
        return new Color(Math.max(0, Math.min(255, (int) (c.getRed() * f))), Math.max(0, Math.min(255, (int) (c.getGreen() * f))),
                Math.max(0, Math.min(255, (int) (c.getBlue() * f))));
    }

    /** Рисует здание; maxWidth - примерная ширина картинки в пикселях. */
    public static BufferedImage render(BuildingTemplate t, boolean cut, int maxWidth) {
        int sx = t.sizeX();
        int sy = t.sizeY();
        int sz = t.sizeZ();
        int cw = Math.max(4, Math.min(24, (maxWidth - 40) * 2 / Math.max(1, sx + sz)));
        cw -= cw % 2;
        int ch = cw / 2;
        int hh = (int) (cw * 0.58);
        int w = (sx + sz) * cw / 2 + 40;
        int h = (sx + sz) * ch / 2 + sy * hh + 60;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int y = 0; y < h; y++) {
                double k = (double) y / h;
                g.setColor(new Color((int) (120 + 70 * k), (int) (165 + 50 * k), (int) (215 - 20 * k)));
                g.drawLine(0, y, w, y);
            }
            int ox = sz * cw / 2 + 20;
            int oy = sy * hh + 30;
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    for (int y = 0; y < sy; y++) {
                        String data = t.blockAt(x, y, z);
                        if (data == null || cut && x >= sx / 2 + 1 && z >= sz / 2 + 1) {
                            continue;
                        }
                        String name = data.split("\\[")[0];
                        if (name.endsWith("pressure_plate") || name.endsWith("carpet")) {
                            continue;
                        }
                        boolean thin = name.contains("fence") || name.contains("iron_bars") || name.contains("lantern") || name.contains("lightning_rod");
                        double top = name.endsWith("_slab") && data.contains("type=bottom") ? 0.5 : 1.0;
                        int px = ox + (x - z) * cw / 2;
                        int py = oy + (x + z) * ch / 2 - y * hh;
                        int bw = thin ? Math.max(2, cw / 3) : cw;
                        int hgt = (int) (hh * top);
                        Color base = colorOf(data);
                        if (thin) {
                            int cy = py + ch / 4;
                            poly(g, shade(base, 1.12), px, cy - bw / 4 - hgt, px + bw / 2, cy - hgt, px, cy + bw / 4 - hgt, px - bw / 2, cy - hgt);
                            poly(g, shade(base, 0.82), px - bw / 2, cy - hgt, px, cy + bw / 4 - hgt, px, cy + bw / 4, px - bw / 2, cy);
                            poly(g, shade(base, 0.62), px + bw / 2, cy - hgt, px, cy + bw / 4 - hgt, px, cy + bw / 4, px + bw / 2, cy);
                        } else {
                            poly(g, shade(base, 1.12), px, py - hgt, px + cw / 2, py - hgt + ch / 2, px, py - hgt + ch, px - cw / 2, py - hgt + ch / 2);
                            poly(g, shade(base, 0.82), px - cw / 2, py - hgt + ch / 2, px, py - hgt + ch, px, py + ch, px - cw / 2, py + ch / 2);
                            poly(g, shade(base, 0.62), px + cw / 2, py - hgt + ch / 2, px, py - hgt + ch, px, py + ch, px + cw / 2, py + ch / 2);
                        }
                    }
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void poly(Graphics2D g, Color fill, int... xy) {
        int n = xy.length / 2;
        int[] xs = new int[n];
        int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = xy[2 * i];
            ys[i] = xy[2 * i + 1];
        }
        g.setColor(fill);
        g.fillPolygon(xs, ys, n);
        g.setColor(shade(fill, 0.78));
        g.drawPolygon(xs, ys, n);
    }
}
