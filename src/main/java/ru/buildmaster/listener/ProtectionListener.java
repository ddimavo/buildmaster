package ru.buildmaster.listener;

import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.ItemStack;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Ledger;
import ru.buildmaster.model.Plot;
import ru.buildmaster.model.Station;
import ru.buildmaster.service.StationLimits;
import ru.buildmaster.util.Text;

/**
 * Защита участков: строить и ломать могут только владелец и доверенные.
 * Вне участков плагин ничего не запрещает (улицы и общие зоны защищайте WorldGuard).
 */
public final class ProtectionListener implements Listener {

    private final BuildMasterPlugin plugin;

    public ProtectionListener(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    private Plot plotAt(Block block) {
        return plugin.plots().at(block.getLocation());
    }

    private Plot plotAt(Entity entity) {
        return plugin.plots().at(entity.getLocation());
    }

    /** true - игроку здесь нельзя ничего менять (сообщение отправляется). */
    private boolean denied(Player player, Plot plot) {
        if (plot == null || player.hasPermission("buildmaster.bypass")) {
            return false;
        }
        String reason = null;
        if (plugin.service().isLocked(plot)) {
            reason = "Участок сейчас обрабатывается.";
        } else if (plot.isFree()) {
            reason = "Участок свободен: чтобы строить, его нужно купить или арендовать.";
        } else if (!plot.isMember(player.getUniqueId())) {
            reason = "Это чужой участок.";
        } else if (plot.isFrozen()) {
            reason = "Участок заморожен до погашения платежа: изменять его нельзя, торговля работает.";
        }
        if (reason != null) {
            player.sendActionBar(Text.mm("<red>" + reason));
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- блоки

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Plot plot = plotAt(block);
        if (denied(event.getPlayer(), plot)) {
            event.setCancelled(true);
            return;
        }
        if (plot == null) {
            return;
        }
        Station broken = Station.fromName(block.getType().name());
        if (broken != null) {
            plugin.stations().dec(plot, broken);
            if (broken.tariffKey() != null) {
                plugin.power().recalc(plot);
            }
        }
        Ledger ledger = plugin.ledgers().get(plot.id);
        if (ledger.remove(block.getX(), block.getY(), block.getZ()) != null) {
            plugin.ledgers().markDirty(plot.id);
        } else if (plugin.getConfig().getBoolean("original-blocks-drop-nothing", true)) {
            // Антидюп: исходный блок здания при сбросе восстановится, поэтому предмет из него не выпадает
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Plot plot = plotAt(event.getBlock());
        if (denied(player, plot)) {
            event.setCancelled(true);
            return;
        }
        if (plot != null && !player.hasPermission("buildmaster.bypass")
                && !plugin.rules().canPlace(event.getBlockPlaced().getType())) {
            player.sendActionBar(Text.mm("<red>Этот блок нельзя ставить на участке."));
            event.setCancelled(true);
            return;
        }
        Station station = plot == null ? null : Station.fromName(event.getBlockPlaced().getType().name());
        if (station != null && !player.hasPermission("buildmaster.bypass")) {
            int limit = plugin.stations().limit(plot.category, station);
            if (limit >= 0) {
                String refusal = null;
                if (!plugin.stations().ready(plot)) {
                    refusal = "Идёт подсчёт станков на участке, попробуйте через минуту.";
                } else {
                    StationLimits.Result r = StationLimits.check(plot.category, station,
                            plugin.stations().groupCount(plot, station), limit);
                    if (!r.allowed()) {
                        refusal = r.message();
                    }
                }
                if (refusal != null) {
                    event.setCancelled(true);
                    plugin.service().warn(player.getUniqueId(), plot.id, "<red>" + Text.esc(refusal)
                            + " <click:run_command:/district><aqua>[Свободные мастерские]</aqua></click>");
                    return;
                }
            }
        }
        if (station != null) {
            plugin.stations().inc(plot, station);
            if (station.tariffKey() != null) {
                plugin.power().recalc(plot);
            }
        }
        if (plot != null && plot.isMember(player.getUniqueId())) {
            // запоминаем, кто поставил блок: при сбросе здания предмет вернётся именно ему
            Block placed = event.getBlockPlaced();
            Material item = event.getItemInHand().getType();
            boolean stacking = event.getBlockReplacedState().getType() == placed.getType()
                    && !placed.getType().isAir();
            plugin.ledgers().get(plot.id).place(placed.getX(), placed.getY(), placed.getZ(),
                    player.getUniqueId(), item.name(), stacking);
            plugin.ledgers().markDirty(plot.id);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Plot plot = plotAt(event.getBlock());
        if (denied(player, plot)) {
            event.setCancelled(true);
        } else if (plot != null && !player.hasPermission("buildmaster.bypass")
                && !plugin.rules().canPlace(event.getBucket())) {
            player.sendActionBar(Text.mm("<red>Выливать это на участке нельзя."));
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (denied(event.getPlayer(), plotAt(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- взаимодействие

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        Action action = event.getAction();
        if (block == null || action == Action.PHYSICAL) {
            return;
        }
        Plot room = plugin.hotels().roomAtDoor(block);
        if (room != null && (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK)) {
            // дверь номера: занятый номер открывается только гостю, его доверенным и управляющему отеля
            if (!plugin.hotels().canUseDoor(event.getPlayer(), room)) {
                event.getPlayer().sendActionBar(Text.mm("<red>Дверь заперта: номер занят."));
                event.setCancelled(true);
            }
            return;
        }
        Plot plot = plotAt(block);
        if (plot == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("buildmaster.bypass")) {
            return;
        }
        ItemStack item = event.getItem();
        UUID id = player.getUniqueId();
        boolean usable = !plugin.service().isLocked(plot) && !plot.isFree();
        boolean rightClick = action == Action.RIGHT_CLICK_BLOCK;
        boolean special = item != null && placesThings(item.getType());
        Station station = Station.fromName(block.getType().name());

        boolean allowed;
        if (!usable) {
            allowed = isPublic(block) && !special;
        } else if (station != null && station != Station.CRAFTING) {
            allowed = plot.can(id, Plot.Perm.STATIONS) && !special;
        } else if (isContainer(block)) {
            allowed = plot.can(id, Plot.Perm.CONTAINERS) || !plugin.getConfig().getBoolean("protect-containers", true);
        } else if (plot.can(id, Plot.Perm.BUILD)) {
            allowed = true;
        } else {
            allowed = isPublic(block) && !special;
        }

        if (allowed) {
            if (usable && rightClick && item != null && !plugin.rules().canPlace(item.getType())) {
                player.sendActionBar(Text.mm("<red>Этим предметом нельзя пользоваться на участке."));
                event.setCancelled(true);
            }
            return;
        }
        if (rightClick || action == Action.LEFT_CLICK_BLOCK) {
            denied(player, plot);
            if (usable && !plot.isMember(id)) {
                player.sendActionBar(Text.mm("<red>У вас нет права пользоваться этим на участке."));
            }
            event.setCancelled(true);
        }
    }

    private boolean isContainer(Block block) {
        if (!(block.getType().isInteractable())) {
            return false;
        }
        BlockState state = block.getState(false);
        return state instanceof BlockInventoryHolder;
    }

    /** Блоки, которыми могут пользоваться все посетители магазина. */
    private boolean isPublic(Block block) {
        Material m = block.getType();
        return Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)
                || Tag.BUTTONS.isTagged(m) || Tag.ALL_SIGNS.isTagged(m)
                || m == Material.LEVER || m == Material.CRAFTING_TABLE || m == Material.BELL;
    }

    private boolean placesThings(Material m) {
        String name = m.name();
        return name.endsWith("_BUCKET") || name.endsWith("_SPAWN_EGG") || name.endsWith("_BOAT")
                || name.endsWith("_RAFT") || name.contains("MINECART")
                || m == Material.ARMOR_STAND || m == Material.ITEM_FRAME || m == Material.GLOW_ITEM_FRAME
                || m == Material.PAINTING || m == Material.FLINT_AND_STEEL || m == Material.FIRE_CHARGE
                || m == Material.END_CRYSTAL;
    }

    // ---------------------------------------------------------------- рамки, картины, стойки

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && denied(event.getPlayer(), plotAt(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Plot plot = plotAt(event.getEntity());
        if (plot == null) {
            return;
        }
        Entity remover = event.getRemover();
        if (remover instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            remover = shooter;
        }
        if (remover instanceof Player player) {
            if (denied(player, plot)) {
                event.setCancelled(true);
            }
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreakOther(HangingBreakEvent event) {
        if (event instanceof HangingBreakByEntityEvent) {
            return;
        }
        if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION && plotAt(event.getEntity()) != null
                && plugin.getConfig().getBoolean("explosion-protection", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if ((entity instanceof Hanging || entity instanceof ArmorStand)
                && denied(event.getPlayer(), plotAt(entity))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (denied(event.getPlayer(), plotAt(event.getRightClicked()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (!(victim instanceof Hanging || victim instanceof ArmorStand)) {
            return;
        }
        Plot plot = plotAt(victim);
        if (plot == null) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (damager instanceof Player player) {
            if (denied(player, plot)) {
                event.setCancelled(true);
            }
        } else {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- стихии

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (plugin.getConfig().getBoolean("fire-protection", true) && plotAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (plugin.getConfig().getBoolean("fire-protection", true) && plotAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (plugin.getConfig().getBoolean("fire-protection", true)
                && event.getSource().getType() == Material.FIRE && plotAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (plugin.getConfig().getBoolean("explosion-protection", true)) {
            event.blockList().removeIf(b -> plotAt(b) != null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (plugin.getConfig().getBoolean("explosion-protection", true)) {
            event.blockList().removeIf(b -> plotAt(b) != null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLiquid(BlockFromToEvent event) {
        if (!plugin.getConfig().getBoolean("liquid-protection", true)) {
            return;
        }
        Plot to = plotAt(event.getToBlock());
        if (to != null && to != plotAt(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player) && plotAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        Plot origin = plugin.plots().at(event.getLocation());
        for (BlockState state : event.getBlocks()) {
            if (plugin.plots().at(state.getLocation()) != origin) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- поршни

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        checkPiston(event, event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        checkPiston(event, event.getBlocks(), event.getDirection().getOppositeFace());
    }

    private void checkPiston(BlockPistonEvent event, List<Block> moved, BlockFace moveDirection) {
        Plot origin = plotAt(event.getBlock());
        if (plotAt(event.getBlock().getRelative(event.getDirection())) != origin) {
            event.setCancelled(true);
            return;
        }
        for (Block block : moved) {
            if (plotAt(block) != origin || plotAt(block.getRelative(moveDirection)) != origin) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
