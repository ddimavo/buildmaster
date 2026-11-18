package ru.industryreborn.holo;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/** Предмет в воздухе: сущность ItemDisplay. */
final class EntityItemHolo implements Holo {

    private final Plugin plugin;
    private final ItemDisplay display;

    EntityItemHolo(Plugin plugin, ItemDisplay display) {
        this.plugin = plugin;
        this.display = display;
    }

    static void apply(ItemDisplay d, org.bukkit.inventory.ItemStack item, float scale, float viewRange, boolean flat) {
        d.setPersistent(false);
        d.setBillboard(flat ? Display.Billboard.FIXED : Display.Billboard.CENTER);
        if (flat) {
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);      // плоский значок, как в инвентаре
        }
        d.setViewRange(viewRange);
        d.setItemStack(item);
        d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
    }

    @Override
    public void text(String miniMessage) { }

    @Override
    public void teleport(Location at) {
        display.teleport(at);
    }

    @Override
    public boolean valid() {
        return display.isValid();
    }

    @Override
    public void remove() {
        display.remove();
    }

    @Override
    public void show(Player player) {
        player.showEntity(plugin, display);
    }

    @Override
    public void hide(Player player) {
        player.hideEntity(plugin, display);
    }
}
