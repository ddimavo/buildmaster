package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.List;

/** Текст маленьких голограмм над станками мастерской: чистое форматирование без обращений к серверу. */
public final class MachineText {

    private MachineText() { }

    /** Полоска прогресса из 10 делений. */
    public static String bar(double ratio) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, ratio)) * 10);
        return "<green>" + "▮".repeat(filled) + "<dark_gray>" + "▯".repeat(10 - filled) + " <white>" + Math.round(Math.max(0, Math.min(1, ratio)) * 100) + "%";
    }

    /** Предмет с количеством либо «пусто». item - готовый фрагмент MiniMessage (название). */
    public static String slot(String label, String item, int amount) {
        return "<gray>" + label + ": " + (amount <= 0 || item == null ? "<dark_gray>пусто" : "<white>" + item + " × " + amount);
    }

    private static String status(boolean powered, boolean working, String offReason) {
        if (!powered) {
            return "<red>обесточен" + (offReason == null || offReason.isBlank() ? "" : " <gray>(" + offReason + ")");
        }
        return working ? "<green>работает" : "<gray>простаивает";
    }

    /** Печь, плавильная печь, коптильня. cookTotal <= 0 - ничего не плавится. */
    public static List<String> furnace(String title, boolean powered, String offReason, boolean burning, int cookTime,
                                       int cookTotal, String input, int inputAmount, String fuel, int fuelAmount,
                                       String output, int outputAmount, String cost) {
        List<String> lines = new ArrayList<>();
        lines.add("<gold>" + title);
        lines.add("<gray>Статус: " + status(powered, burning || (cookTotal > 0 && cookTime > 0), offReason));
        if (cookTotal > 0) {
            lines.add(bar((double) cookTime / cookTotal));
        }
        lines.add(slot("Сырьё", input, inputAmount));
        lines.add(slot("Топливо", fuel, fuelAmount));
        lines.add(slot("Продукция", output, outputAmount));
        if (cost != null) {
            lines.add("<yellow>" + cost);
        }
        return lines;
    }

    /** Варочная стойка: brewTime - тиков до конца варки (0 - не варит), brewTotal - полное время варки. */
    public static List<String> brewing(String title, boolean powered, String offReason, int brewTime, int brewTotal,
                                       String ingredient, int ingredientAmount, int fuelLevel, String cost) {
        List<String> lines = new ArrayList<>();
        lines.add("<gold>" + title);
        lines.add("<gray>Статус: " + status(powered, brewTime > 0, offReason));
        if (brewTime > 0 && brewTotal > 0) {
            lines.add(bar(1.0 - (double) brewTime / brewTotal));
        }
        lines.add(slot("Ингредиент", ingredient, ingredientAmount));
        lines.add("<gray>Топливо (порошок): <white>" + fuelLevel);
        if (cost != null) {
            lines.add("<yellow>" + cost);
        }
        return lines;
    }

    /** Наковальня и стол зачарований: состояния внутри нет, показываем статус, расход и счётчик операций. */
    public static List<String> simple(String title, boolean powered, String offReason, long operations, String spent,
                                      String cost) {
        List<String> lines = new ArrayList<>();
        lines.add("<gold>" + title);
        lines.add("<gray>Статус: " + status(powered, false, offReason).replace("простаивает", "готов к работе"));
        lines.add("<gray>Операций: <white>" + operations + (spent == null ? "" : " <gray>на <white>" + spent));
        if (cost != null) {
            lines.add("<yellow>" + cost);
        }
        return lines;
    }
}
