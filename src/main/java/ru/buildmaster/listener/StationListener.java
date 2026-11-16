package ru.buildmaster.listener;

import org.bukkit.block.Block;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

/** Обесточенная мастерская: станки не работают. Вне мастерских станки обычные. */
public final class StationListener implements Listener {

    private final BuildMasterPlugin plugin;

    public StationListener(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** true - станок в этом блоке сейчас работать не должен. */
    private boolean off(Block block) {
        Plot plot = plugin.plots().at(block.getLocation());
        return plot != null && !plugin.power().isPowered(plot);
    }

    private void tell(HumanEntity who) {
        if (who instanceof Player p) {
            p.sendActionBar(Text.mm("<red>Мастерская обесточена: станки не работают."));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        if (off(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        if (off(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewFuel(BrewingStandFuelEvent event) {
        if (off(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (off(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvil(PrepareAnvilEvent event) {
        var loc = event.getInventory().getLocation();
        if (loc != null && off(loc.getBlock())) {
            event.setResult(null);
            event.getViewers().forEach(this::tell);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (off(event.getEnchantBlock())) {
            event.setCancelled(true);
            tell(event.getEnchanter());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (off(event.getEnchantBlock())) {
            event.setCancelled(true);
            tell(event.getEnchanter());
        }
    }
}
