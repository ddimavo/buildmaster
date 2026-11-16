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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.service.PowerService;
import ru.buildmaster.util.Text;

/**
 * Станки мастерских и электричество.
 * DAILY: без электричества станки не работают. USAGE: каждая операция списывает деньги со счёта.
 * Вне мастерских станки обычные.
 */
public final class StationListener implements Listener {

    private final BuildMasterPlugin plugin;

    public StationListener(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    private Plot plotAt(Block block) {
        return block == null ? null : plugin.plots().at(block.getLocation());
    }

    private boolean usage() {
        return plugin.power().mode() == PowerService.Mode.USAGE;
    }

    /** DAILY: станок обесточен. В режиме USAGE работа проверяется операцией, а не состоянием. */
    private boolean off(Block block) {
        if (usage()) {
            return false;
        }
        Plot plot = plotAt(block);
        return plot != null && !plugin.power().isPowered(plot);
    }

    private void tell(HumanEntity who) {
        if (!(who instanceof Player p)) {
            return;
        }
        Plot plot = plotAt(p.getTargetBlockExact(6));
        String gridReason = plot == null ? null : plugin.power().gridOffReason(plot);
        if (gridReason != null) {
            p.sendActionBar(Text.mm("<red>Предприятие отключено от электростанции: " + Text.esc(gridReason)));
            return;
        }
        p.sendActionBar(Text.mm(usage()
                ? "<red>На счёте мастерской недостаточно средств для этой операции."
                : "<red>Мастерская обесточена: станки не работают."));
    }

    /** Операция на станке: false - не разрешена (нет электричества или денег). */
    private boolean operation(Block block, String tariffKey) {
        Plot plot = plotAt(block);
        return plot == null || plugin.power().useStation(plot, tariffKey);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        if (off(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        if (!operation(event.getBlock(), "smelter")) {
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
        if (!operation(event.getBlock(), "alchemy")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        var loc = event.getInventory().getLocation();
        if (loc != null && off(loc.getBlock())) {
            event.setResult(null);
            event.getViewers().forEach(this::tell);
        }
    }

    /** Ремонт на наковальне: операция считается, когда игрок забирает результат. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvilTake(InventoryClickEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL || !(event.getInventory() instanceof AnvilInventory anvil)
                || event.getSlot() != 2 || event.getClickedInventory() != anvil) {
            return;
        }
        var result = event.getCurrentItem();
        var loc = anvil.getLocation();
        if (result == null || result.getType().isAir() || loc == null) {
            return;
        }
        if (!operation(loc.getBlock(), "anvil")) {
            event.setCancelled(true);
            tell(event.getWhoClicked());
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
        if (!operation(event.getEnchantBlock(), "enchanting")) {
            event.setCancelled(true);
            tell(event.getEnchanter());
        }
    }
}
