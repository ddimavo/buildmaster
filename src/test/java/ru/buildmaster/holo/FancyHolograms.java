package ru.buildmaster.holo;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramConfiguration;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.HologramStorage;
import de.oliver.fancyholograms.api.data.HologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Подмена плагина FancyHolograms для тестов: менеджер хранит голограммы в памяти, показы записываются. */
public class FancyHolograms extends JavaPlugin implements FancyHologramsPlugin {

    public static final class TestHologram extends Hologram {
        public final java.util.Set<java.util.UUID> forced = new java.util.HashSet<>();
        public int updates;

        TestHologram(HologramData data) {
            super(data);
        }

        @Override
        public int getEntityId() {
            return 1;
        }

        @Override
        public @Nullable Display getDisplayEntity() {
            return null;
        }

        @Override
        protected void create() { }

        @Override
        protected void delete() { }

        @Override
        protected void update() {
            updates++;
        }

        @Override
        protected boolean show(@NotNull Player player) {
            forced.add(player.getUniqueId());
            return true;
        }

        @Override
        protected boolean hide(@NotNull Player player) {
            forced.remove(player.getUniqueId());
            return true;
        }

        @Override
        protected void refresh(@NotNull Player player) { }
    }

    public static final class Manager implements HologramManager {
        public final Map<String, Hologram> holograms = new LinkedHashMap<>();
        public boolean loaded = true;

        @Override
        public Optional<Hologram> getHologram(String name) {
            return Optional.ofNullable(holograms.get(name));
        }

        @Override
        public Collection<Hologram> getPersistentHolograms() {
            return holograms.values().stream().filter(h -> h.getData().isPersistent()).toList();
        }

        @Override
        public Collection<Hologram> getHolograms() {
            return holograms.values();
        }

        @Override
        public void addHologram(Hologram hologram) {
            holograms.put(hologram.getName(), hologram);
        }

        @Override
        public void removeHologram(Hologram hologram) {
            holograms.remove(hologram.getName());
        }

        @Override
        public Hologram create(HologramData data) {
            return new TestHologram(data);
        }

        @Override
        public void loadHolograms() { }

        @Override
        public boolean isLoaded() {
            return loaded;
        }

        @Override
        public void saveHolograms() { }

        @Override
        public void reloadHolograms() { }
    }

    public static final Manager MANAGER = new Manager();

    public FancyHolograms() {
        super();
    }

    public FancyHolograms(String name, String version) {
        super();
    }

    protected FancyHolograms(org.bukkit.plugin.java.JavaPluginLoader loader, org.bukkit.plugin.PluginDescriptionFile description,
                                 java.io.File dataFolder, java.io.File file) {
        super(loader, description, dataFolder, file);
    }

    @Override
    public JavaPlugin getPlugin() {
        return this;
    }

    @Override
    public de.oliver.fancyanalytics.logger.ExtendedFancyLogger getFancyLogger() {
        return null;
    }

    @Override
    public HologramManager getHologramManager() {
        return MANAGER;
    }

    @Override
    public HologramConfiguration getHologramConfiguration() {
        return null;
    }

    @Override
    public void setHologramConfiguration(HologramConfiguration configuration, boolean reload) { }

    @Override
    public HologramStorage getHologramStorage() {
        return null;
    }

    @Override
    public ScheduledExecutorService getHologramThread() {
        return null;
    }

    @Override
    public void setHologramStorage(HologramStorage storage, boolean reload) { }
}
