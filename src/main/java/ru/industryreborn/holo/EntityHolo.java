package ru.industryreborn.holo;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import ru.industryreborn.util.Text;

/** Голограмма на встроенной сущности TextDisplay (внешние плагины не нужны). */
final class EntityHolo implements Holo {

    private final Plugin plugin;
    private final TextDisplay display;

    EntityHolo(Plugin plugin, TextDisplay display) {
        this.plugin = plugin;
        this.display = display;
    }

    /** Настройки сущности по описанию. */
    static void apply(TextDisplay d, HoloSpec spec, NamespacedKey markerKey) {
        d.setPersistent(false);
        d.setBillboard(spec.billboard());
        d.setAlignment(spec.left() ? TextDisplay.TextAlignment.LEFT : TextDisplay.TextAlignment.CENTER);
        d.setLineWidth(spec.lineWidth());
        d.setViewRange(spec.viewRange());
        d.setBackgroundColor(Color.fromARGB(spec.backgroundArgb()));
        d.setDefaultBackground(false);
        if (spec.scale() != 1f) {
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(spec.scale(), spec.scale(), spec.scale()),
                    new AxisAngle4f()));
        }
        if (spec.marker() != null && markerKey != null) {
            d.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, spec.marker());
        }
        d.text(Text.mm(spec.text()));
        if (spec.perViewer()) {
            try {
                d.setVisibleByDefault(false);
            } catch (RuntimeException ignored) {
                // сервер без поддержки индивидуальной видимости: голограмму увидят все рядом
            }
        }
    }

    @Override
    public void text(String miniMessage) {
        display.text(Text.mm(miniMessage));
    }

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
