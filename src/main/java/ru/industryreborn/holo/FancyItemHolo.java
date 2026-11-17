package ru.industryreborn.holo;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.ItemHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Vector3f;

/** Предмет в воздухе средствами FancyHolograms. Класс загружается только при установленном FancyHolograms. */
final class FancyItemHolo implements Holo {

    private final String name;
    private final Hologram hologram;
    private final ItemHologramData data;

    private FancyItemHolo(String name, Hologram hologram, ItemHologramData data) {
        this.name = name;
        this.hologram = hologram;
        this.data = data;
    }

    private static HologramManager manager() {
        return FancyHologramsPlugin.get().getHologramManager();
    }

    static FancyItemHolo create(String name, Location at, ItemStack item, float scale, float viewRange) {
        HologramManager manager = manager();
        manager.getHologram(name).ifPresent(manager::removeHologram);
        ItemHologramData d = new ItemHologramData(name, at);
        d.setItemStack(item);
        d.setBillboard(Display.Billboard.CENTER);
        d.setScale(new Vector3f(scale, scale, scale));
        d.setVisibilityDistance(Math.max(1, Math.round(viewRange * 64)));
        d.setPersistent(false);
        Hologram hologram = manager.create(d);
        manager.addHologram(hologram);
        return new FancyItemHolo(name, hologram, d);
    }

    @Override
    public void text(String miniMessage) { }

    @Override
    public void teleport(Location at) {
        data.setLocation(at);
        hologram.queueUpdate();
    }

    @Override
    public boolean valid() {
        return manager().getHologram(name).filter(h -> h == hologram).isPresent();
    }

    @Override
    public void remove() {
        manager().removeHologram(hologram);
    }

    @Override
    public void show(Player player) {
        hologram.forceShowHologram(player);
    }

    @Override
    public void hide(Player player) {
        hologram.forceHideHologram(player);
    }
}
