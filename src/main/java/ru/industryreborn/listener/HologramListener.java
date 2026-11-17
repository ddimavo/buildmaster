package ru.industryreborn.listener;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;

/** Голограммы не сохраняются в мире, поэтому при загрузке чанка их нужно создать заново. */
public final class HologramListener implements Listener {

    private final IndustryRebornPlugin plugin;

    public HologramListener(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        String world = event.getWorld().getName();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        for (Plot plot : plugin.plots().all()) {
            if (plot.hasHolo && plot.world.equals(world)
                    && ((int) Math.floor(plot.holoX) >> 4) == cx && ((int) Math.floor(plot.holoZ) >> 4) == cz) {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.holograms().update(plot));
            }
        }
    }
}
