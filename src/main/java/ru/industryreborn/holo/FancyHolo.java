package ru.industryreborn.holo;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.data.property.Visibility;
import de.oliver.fancyholograms.api.hologram.Hologram;
import java.util.Arrays;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

/**
 * Голограмма плагина FancyHolograms (на пакетах). Создаётся непостоянной: плагин её не сохраняет, IndustryReborn пересоздаёт
 * голограммы сам. Класс загружается только при установленном FancyHolograms.
 */
final class FancyHolo implements Holo {

    private final String name;
    private final Hologram hologram;
    private final TextHologramData data;

    private FancyHolo(String name, Hologram hologram, TextHologramData data) {
        this.name = name;
        this.hologram = hologram;
        this.data = data;
    }

    static boolean ready() {
        if (!FancyHologramsPlugin.isEnabled()) {
            return false;
        }
        FancyHologramsPlugin api = FancyHologramsPlugin.get();
        return api != null && api.getHologramManager() != null && api.getHologramManager().isLoaded();
    }

    private static HologramManager manager() {
        return FancyHologramsPlugin.get().getHologramManager();
    }

    private static java.util.List<String> lines(String text) {
        return Arrays.asList(text.split("\n", -1));
    }

    static FancyHolo create(String name, HoloSpec spec) {
        HologramManager manager = manager();
        // имя занято (например, после неудачной очистки): старую голограмму убираем
        manager.getHologram(name).ifPresent(manager::removeHologram);
        TextHologramData d = new TextHologramData(name, spec.at());
        d.setText(lines(spec.text()));
        d.setBackground(Color.fromARGB(spec.backgroundArgb()));
        d.setBillboard(spec.billboard());
        d.setTextAlignment(spec.left() ? TextDisplay.TextAlignment.LEFT : TextDisplay.TextAlignment.CENTER);
        d.setScale(new Vector3f(spec.scale(), spec.scale(), spec.scale()));
        d.setVisibilityDistance(Math.max(1, Math.round(spec.viewRange() * 64)));
        d.setPersistent(false);
        if (spec.perViewer()) {
            d.setVisibility(Visibility.MANUAL);
        }
        Hologram hologram = manager.create(d);
        manager.addHologram(hologram);
        return new FancyHolo(name, hologram, d);
    }

    @Override
    public void text(String miniMessage) {
        data.setText(lines(miniMessage));
        hologram.queueUpdate();
    }

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
        Visibility.ManualVisibility.remove(name);
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
