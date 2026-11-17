package ru.industryreborn.auction;

import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import fr.maxlego08.zauctionhouse.api.cache.PlayerCacheKey;
import fr.maxlego08.zauctionhouse.api.economy.AuctionEconomy;
import fr.maxlego08.zauctionhouse.api.economy.EconomyManager;
import fr.maxlego08.zauctionhouse.api.event.events.AuctionLoadEconomyEvent;
import fr.maxlego08.zauctionhouse.api.item.ItemType;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;

/**
 * Единственное место, где используются классы zAuctionHouse. Загружается только если плагин установлен.
 * Подписывается на загрузку экономик аукциона, чтобы зарегистрировать экономику казны ещё до чтения лотов из базы.
 */
public final class ZahBridge implements AuctionPort, Listener {

    private final IndustryRebornPlugin plugin;
    private AuctionEconomy treasuryEconomy;

    public ZahBridge(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    /** Регистрирует слушатель и, если аукцион уже загружен, экономику казны. */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        AuctionPlugin zah = zah();
        if (zah != null) {
            registerEconomy(zah.getEconomyManager());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoadEconomies(AuctionLoadEconomyEvent event) {
        // события вызывается при загрузке и при перезагрузке: экономики очищаются, регистрируем заново
        registerEconomy(event.getEconomyManager());
    }

    private void registerEconomy(EconomyManager manager) {
        if (manager.getEconomy(TreasuryAuction.ECONOMY_NAME).isPresent()) {
            return;
        }
        if (treasuryEconomy == null) {
            treasuryEconomy = TreasuryEconomy.create(plugin, () -> baseEconomy(manager));
        }
        manager.registerEconomy(treasuryEconomy);
    }

    /** Настоящая экономика, валюту которой использует казна: из настроек либо экономика аукциона по умолчанию. */
    private AuctionEconomy baseEconomy(EconomyManager manager) {
        String name = plugin.getConfig().getString("auction.economy", "");
        if (name != null && !name.isBlank() && !name.equalsIgnoreCase(TreasuryAuction.ECONOMY_NAME)) {
            var configured = manager.getEconomy(name);
            if (configured.isPresent()) {
                return configured.get();
            }
        }
        return manager.getDefaultEconomy(ItemType.AUCTION);
    }

    private AuctionPlugin zah() {
        try {
            return Bukkit.getServicesManager().load(AuctionPlugin.class);
        } catch (LinkageError e) {
            return null;
        }
    }

    @Override
    public boolean ready() {
        AuctionPlugin zah = zah();
        if (zah == null || !zah.isEnabled() || zah.getAuctionClusterBridge() == null
                || zah.getAuctionClusterBridge().isDistributed()) {
            return false;
        }
        return zah.getEconomyManager().getEconomy(TreasuryAuction.ECONOMY_NAME).isPresent();
    }

    @Override
    public int listedByTreasury() {
        AuctionPlugin zah = zah();
        return zah == null ? 0 : zah.getAuctionManager()
                .getItems(StorageType.LISTED, item -> TreasuryAuction.SYSTEM_ID.equals(item.getSellerUniqueId())).size();
    }

    @Override
    public CompletableFuture<Boolean> list(ItemStack item, BigDecimal price, long expiredAt) {
        AuctionPlugin zah = zah();
        if (zah == null) {
            return CompletableFuture.completedFuture(false);
        }
        var economy = zah.getEconomyManager().getEconomy(TreasuryAuction.ECONOMY_NAME);
        if (economy.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        AuctionEconomy eco = economy.get();
        BigDecimal finalPrice = AuctionPricing.clamp(price, eco.getMinPrice(ItemType.AUCTION), eco.getMaxPrice(ItemType.AUCTION));
        String seller = plugin.treasuryAuction().sellerName();
        zah.getStorageManager().upsertPlayer(TreasuryAuction.SYSTEM_ID, seller);
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        zah.getStorageManager().createAuctionItem(TreasuryAuction.SYSTEM_ID, seller, finalPrice, expiredAt, List.of(item), eco)
                .whenComplete((created, error) -> {
                    if (error != null || created == null) {
                        done.completeExceptionally(error != null ? error : new IllegalStateException("лот не создан"));
                        return;
                    }
                    // как после обычной продажи: публикация в памяти и обновление меню - в основном потоке
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            zah.getCategoryManager().applyCategories(created);
                            zah.getAuctionManager().addItem(StorageType.LISTED, created);
                            zah.getAuctionManager().clearPlayersCache(PlayerCacheKey.ITEMS_LISTED, PlayerCacheKey.ITEMS_SEARCH);
                            zah.getAuctionManager().updateListedItems(created, true, null);
                            zah.getAuctionClusterBridge().notifyItemListed(created);
                            done.complete(true);
                        } catch (RuntimeException e) {
                            // лот уже в базе: считаем выставленным, чтобы не создать дубликат; он появится после перезагрузки
                            plugin.getLogger().warning("Лот казны создан, но не опубликован в памяти аукциона: " + e);
                            done.complete(true);
                        }
                    });
                });
        return done;
    }
}
