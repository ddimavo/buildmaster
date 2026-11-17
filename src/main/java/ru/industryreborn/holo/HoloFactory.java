package ru.industryreborn.holo;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import ru.industryreborn.IndustryRebornPlugin;

/**
 * Создаёт голограммы. Если на сервере есть FancyHolograms (и это не выключено в config.yml: holograms.backend), используется он,
 * иначе голограммы рисуются встроенными сущностями TextDisplay.
 */
public final class HoloFactory {

    private final IndustryRebornPlugin plugin;
    private final NamespacedKey markerKey;
    private BiFunction<Location, Consumer<TextDisplay>, TextDisplay> spawner;
    private Boolean fancyOverride;
    private boolean fancyBroken;           // API не подошёл (LinkageError): до перезапуска
    private long fancyRetryAt;              // после ошибки создания пробуем снова не раньше этого времени

    public HoloFactory(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "hologram");
    }

    /** Только для тестов: подмена создания сущностей (MockBukkit не умеет TextDisplay). */
    public void setSpawner(BiFunction<Location, Consumer<TextDisplay>, TextDisplay> spawner) {
        this.spawner = spawner;
    }

    /** Только для тестов: принудительно включить или выключить FancyHolograms (null - по настройкам). */
    public void forceFancy(Boolean value) {
        this.fancyOverride = value;
        this.fancyBroken = false;
        this.fancyRetryAt = 0;
    }

    public NamespacedKey markerKey() {
        return markerKey;
    }

    /** Режим из config.yml: auto, fancy или entity. */
    public String mode() {
        String m = plugin.getConfig().getString("holograms.backend", "auto");
        return m == null ? "auto" : m.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Голограммы сейчас рисует FancyHolograms. */
    public boolean fancyActive() {
        if (fancyBroken || System.currentTimeMillis() < fancyRetryAt) {
            return false;
        }
        if (fancyOverride != null) {
            return fancyOverride;
        }
        if (mode().equals("entity")) {
            return false;
        }
        Plugin p = Bukkit.getPluginManager().getPlugin("FancyHolograms");
        if (p == null || !p.isEnabled()) {
            return false;
        }
        try {
            return FancyHolo.ready();
        } catch (LinkageError e) {
            fancyBroken = true;
            plugin.getLogger().log(Level.WARNING, "FancyHolograms найден, но его API не подошёл: голограммы остаются на сущностях", e);
            return false;
        } catch (RuntimeException e) {
            return false;       // плагин ещё включается
        }
    }

    /** Создаёт голограмму. name - уникальное имя (для FancyHolograms оно попадает в имя голограммы). */
    public Holo spawn(String name, HoloSpec spec) {
        if (fancyActive()) {
            try {
                return FancyHolo.create(fancyName(name), spec);
            } catch (LinkageError e) {
                fancyBroken = true;
                plugin.getLogger().log(Level.WARNING, "API FancyHolograms не подошёл: голограммы остаются на сущностях", e);
            } catch (RuntimeException e) {
                fancyRetryAt = System.currentTimeMillis() + 60_000L;
                plugin.getLogger().log(Level.WARNING, "Не удалось создать голограмму в FancyHolograms, на минуту переключаюсь на сущности", e);
            }
        }
        Location at = spec.at();
        Consumer<TextDisplay> init = d -> EntityHolo.apply(d, spec, markerKey);
        TextDisplay display = spawner != null ? spawner.apply(at, init) : at.getWorld().spawn(at, TextDisplay.class, init);
        return new EntityHolo(plugin, display);
    }

    /** Имя для FancyHolograms: только латиница, цифры, - и _; одинаковые имена не пересекаются. */
    public static String fancyName(String key) {
        String safe = key.replaceAll("[^A-Za-z0-9_-]", "_");
        return "bm_" + safe + (safe.equals(key) ? "" : "_" + Integer.toHexString(key.hashCode()));
    }
}
