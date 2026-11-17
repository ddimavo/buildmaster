package ru.industryreborn.auction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.Journal;

/**
 * Общий аукцион: вещи из хранилищ игроков, которые не были забраны вовремя или переданы администратором, выставляются
 * на аукцион zAuctionHouse от имени казны. Выручка идёт в казну. Игроки без zAuctionHouse обходятся без аукциона.
 */
public final class TreasuryAuction {

    /** Системный «продавец» казны в zAuctionHouse. */
    public static final UUID SYSTEM_ID = UUID.nameUUIDFromBytes("industryreborn:treasury".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    /** Имя экономики-обёртки, зачисляющей деньги продавцу-казне в казну. */
    public static final String ECONOMY_NAME = "industryreborn_treasury";

    private final IndustryRebornPlugin plugin;
    private AuctionPort port;

    public TreasuryAuction(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    public void setPort(AuctionPort port) {
        this.port = port;
    }

    public AuctionPort port() {
        return port;
    }

    public boolean enabled() {
        return plugin.modules().enabled(ru.industryreborn.service.Module.AUCTION) && plugin.getConfig().getBoolean("auction.enabled", true);
    }

    /** Можно ли сейчас выставлять лоты. */
    public boolean ready() {
        return enabled() && port != null && port.ready();
    }

    public String sellerName() {
        return plugin.getConfig().getString("auction.seller-name", "Казна");
    }

    /** Вещей в хранилищах, ожидающих выставления на аукцион. */
    public int waiting() {
        int total = 0;
        for (UUID owner : plugin.storage().playersWithState(StorageEntry.State.AUCTION)) {
            total += plugin.storage().countItems(owner, StorageEntry.State.AUCTION);
        }
        return total;
    }

    /**
     * Выставляет на аукцион ожидающие вещи, не больше лимита за запуск и лимита одновременных лотов казны.
     * Предмет сначала убирается из хранилища и только потом выставляется: при сбое возвращается обратно, дубликатов не будет.
     * Возвращает число начатых выставлений.
     */
    public int tick() {
        if (!ready()) {
            return 0;
        }
        int maxListings = plugin.getConfig().getInt("auction.max-listings", 500);
        int budget = plugin.getConfig().getInt("auction.per-run", 20);
        if (maxListings > 0) {
            budget = Math.min(budget, maxListings - port.listedByTreasury());
        }
        int started = 0;
        for (UUID owner : plugin.storage().playersWithState(StorageEntry.State.AUCTION)) {
            for (StorageEntry entry : new ArrayList<>(plugin.storage().entries(owner))) {
                if (entry.state != StorageEntry.State.AUCTION) {
                    continue;
                }
                for (byte[] ref : new ArrayList<>(entry.stacks)) {
                    if (started >= budget) {
                        return started;
                    }
                    ItemStack stack = plugin.storage().takeForAuction(owner, entry.id, ref);
                    if (stack == null) {
                        continue;
                    }
                    started++;
                    submit(owner, entry, stack);
                }
            }
        }
        return started;
    }

    private void submit(UUID owner, StorageEntry entry, ItemStack stack) {
        BigDecimal basePrice = AuctionPricing.price(plugin.getConfig().getConfigurationSection("auction"), stack);
        Double auctionScale = plugin.balance().applied("auction");
        final BigDecimal price = auctionScale == null ? basePrice
                : basePrice.multiply(BigDecimal.valueOf(auctionScale)).setScale(2, java.math.RoundingMode.HALF_UP);
        int days = plugin.getConfig().getInt("auction.listing-days", 0);
        long expiredAt = days > 0 ? System.currentTimeMillis() + days * 86_400_000L : 0;
        String what = stack.getType().name() + " x" + stack.getAmount();
        CompletableFuture<Boolean> future;
        try {
            future = port.list(stack.clone(), price, expiredAt);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || !Boolean.TRUE.equals(ok)) {
                plugin.getLogger().warning("Не удалось выставить на аукцион " + what + ": "
                        + (error == null ? "отказ" : error.toString()) + ". Предмет возвращён в очередь.");
                plugin.storage().add(owner, entry.plotId, List.of(stack), StorageEntry.State.AUCTION, entry.reason,
                        "auction-retry");
                return;
            }
            plugin.journal().add(entry.plotId, owner, "система", Journal.Action.AUCTION,
                    "На аукцион от казны: " + what + " за " + plugin.economy().format(price.doubleValue()));
        }));
    }
}
