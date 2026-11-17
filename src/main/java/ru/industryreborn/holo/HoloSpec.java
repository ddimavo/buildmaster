package ru.industryreborn.holo;

import org.bukkit.Location;
import org.bukkit.entity.Display;

/**
 * Описание голограммы.
 *
 * @param viewRange  дальность видимости как множитель (1.0 = 64 блока), как у Display.setViewRange
 * @param scale      размер текста (1 - обычный)
 * @param perViewer  голограмма скрыта от всех, пока ей не вызовут show(игрок)
 * @param marker     метка в данных сущности (для поиска своих голограмм); null - без метки
 */
public record HoloSpec(Location at, String text, boolean left, Display.Billboard billboard, int lineWidth, float viewRange,
                       int backgroundArgb, float scale, boolean perViewer, String marker) {

    public HoloSpec(Location at, String text) {
        this(at, text, false, Display.Billboard.CENTER, 200, 0.5f, 0x78000000, 1f, false, null);
    }

    public HoloSpec withText(String newText) {
        return new HoloSpec(at, newText, left, billboard, lineWidth, viewRange, backgroundArgb, scale, perViewer, marker);
    }

    public HoloSpec left(boolean value) {
        return new HoloSpec(at, text, value, billboard, lineWidth, viewRange, backgroundArgb, scale, perViewer, marker);
    }

    public HoloSpec billboard(Display.Billboard value) {
        return new HoloSpec(at, text, left, value, lineWidth, viewRange, backgroundArgb, scale, perViewer, marker);
    }

    public HoloSpec style(int lineWidth, float viewRange, int backgroundArgb) {
        return new HoloSpec(at, text, left, billboard, lineWidth, viewRange, backgroundArgb, scale, perViewer, marker);
    }

    public HoloSpec scale(float value) {
        return new HoloSpec(at, text, left, billboard, lineWidth, viewRange, backgroundArgb, value, perViewer, marker);
    }

    public HoloSpec perViewer(boolean value) {
        return new HoloSpec(at, text, left, billboard, lineWidth, viewRange, backgroundArgb, scale, value, marker);
    }

    public HoloSpec marker(String value) {
        return new HoloSpec(at, text, left, billboard, lineWidth, viewRange, backgroundArgb, scale, perViewer, value);
    }
}
