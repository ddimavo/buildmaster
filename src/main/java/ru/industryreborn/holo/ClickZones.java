package ru.industryreborn.holo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import ru.industryreborn.IndustryRebornPlugin;

/**
 * Зоны кликов у голограмм: невидимые сущности Interaction. Клик по зоне вызывает обработчик (ПКМ или ЛКМ, с Shift или без).
 * Зоны не сохраняются в мире; при старте оставшиеся от прошлого запуска убираются по метке.
 */
public final class ClickZones implements Listener {

    /** Что делать при клике: игрок, держит ли он Shift, левая ли кнопка. */
    public interface Handler {
        void click(Player player, boolean sneaking, boolean left);
    }

    /** Созданная зона. */
    public static final class Zone {
        private final UUID id;
        private final Entity entity;
        private final ClickZones owner;

        Zone(UUID id, Entity entity, ClickZones owner) {
            this.id = id;
            this.entity = entity;
            this.owner = owner;
        }

        public UUID id() {
            return id;
        }

        public boolean valid() {
            return entity == null || entity.isValid();
        }

        public void remove() {
            owner.handlers.remove(id);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    private final IndustryRebornPlugin plugin;
    private final NamespacedKey marker;
    private final Map<UUID, Handler> handlers = new HashMap<>();
    private BiFunction<Location, double[], Entity> spawner;
    private long lastCleanup;

    public ClickZones(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "click-zone");
    }

    /** Только для тестов: подмена создания сущности (MockBukkit не умеет Interaction). Возвращает сущность-заглушку. */
    public void setSpawner(BiFunction<Location, double[], Entity> spawner) {
        this.spawner = spawner;
    }

    public Zone create(Location at, double width, double height, Handler handler) {
        Entity e;
        if (spawner != null) {
            e = spawner.apply(at, new double[] {width, height});
        } else {
            e = at.getWorld().spawn(at, Interaction.class, i -> {
                i.setInteractionWidth((float) width);
                i.setInteractionHeight((float) height);
                i.setResponsive(true);
                i.setPersistent(false);
                i.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            });
        }
        UUID id = e.getUniqueId();
        handlers.put(id, handler);
        return new Zone(id, e, this);
    }

    public int count() {
        return handlers.size();
    }

    /** Убирает зоны прошлых запусков (в загруженных чанках). */
    public void cleanLeftovers() {
        for (var world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntitiesByClass(Interaction.class)) {
                if (e.getPersistentDataContainer().has(marker, PersistentDataType.BYTE) && !handlers.containsKey(e.getUniqueId())) {
                    e.remove();
                }
            }
        }
    }

    public void removeAll() {
        for (UUID id : java.util.List.copyOf(handlers.keySet())) {
            Entity e = plugin.getServer().getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        handlers.clear();
    }

    /** Для тестов и обработчиков: обработать клик по зоне. */
    public boolean dispatch(UUID zone, Player player, boolean sneaking, boolean left) {
        Handler h = handlers.get(zone);
        if (h == null) {
            return false;
        }
        h.click(player, sneaking, left);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (dispatch(event.getRightClicked().getUniqueId(), event.getPlayer(), event.getPlayer().isSneaking(), false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeftClick(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && handlers.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
            dispatch(event.getEntity().getUniqueId(), player, player.isSneaking(), true);
        }
    }
}
