package ru.buildmaster.service;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ru.buildmaster.model.Plot;

/** Уровни улучшений мастерской из config.yml (upgrades.base и upgrades.levels.N). */
public final class UpgradeCatalog {

    /** Эффекты уровня: число линий, скорость, множители вместимости и бонус к очереди заказов. */
    public record Level(int level, double price, int maxLines, double speed, double stock, double buffer, int orderQueue) { }

    private final java.util.function.Supplier<FileConfiguration> config;

    public UpgradeCatalog(java.util.function.Supplier<FileConfiguration> config) {
        this.config = config;
    }

    private Level read(int level, ConfigurationSection s, Level fallback) {
        if (s == null) {
            return fallback;
        }
        return new Level(level, s.getDouble("price", 0), s.getInt("max-lines", fallback.maxLines()),
                s.getDouble("speed", fallback.speed()), s.getDouble("stock", fallback.stock()),
                s.getDouble("buffer", fallback.buffer()), s.getInt("order-queue", fallback.orderQueue()));
    }

    public Level base() {
        ConfigurationSection s = config.get().getConfigurationSection("upgrades.base");
        Level def = new Level(0, 0, 1, 1.0, 1.0, 1.0, 0);
        return s == null ? def : read(0, s, def);
    }

    /** Все уровни от 1 до максимального, по возрастанию. */
    public List<Level> levels() {
        List<Level> list = new ArrayList<>();
        ConfigurationSection root = config.get().getConfigurationSection("upgrades.levels");
        if (root == null) {
            return list;
        }
        Level previous = base();
        List<Integer> numbers = new ArrayList<>();
        for (String key : root.getKeys(false)) {
            try {
                numbers.add(Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
                // посторонний ключ
            }
        }
        java.util.Collections.sort(numbers);
        for (int n : numbers) {
            // не заданные значения наследуются от предыдущего уровня
            Level level = read(n, root.getConfigurationSection(String.valueOf(n)), previous);
            list.add(level);
            previous = level;
        }
        return list;
    }

    public int maxLevel() {
        List<Level> list = levels();
        return list.isEmpty() ? 0 : list.get(list.size() - 1).level();
    }

    /** Эффекты текущего уровня участка (уровень выше максимума приравнивается к максимальному). */
    public Level of(Plot plot) {
        return at(plot.level);
    }

    public Level at(int level) {
        Level result = base();
        for (Level l : levels()) {
            if (l.level() <= level) {
                result = l;
            }
        }
        return result;
    }

    /** Следующий уровень или null, если достигнут максимум. */
    public Level next(Plot plot) {
        for (Level l : levels()) {
            if (l.level() > plot.level) {
                return l;
            }
        }
        return null;
    }
}
