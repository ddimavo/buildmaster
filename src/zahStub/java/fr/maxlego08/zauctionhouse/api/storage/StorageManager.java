package fr.maxlego08.zauctionhouse.api.storage;

import fr.maxlego08.zauctionhouse.api.economy.AuctionEconomy;
import fr.maxlego08.zauctionhouse.api.item.items.AuctionItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;

/** Подмножество. */
public interface StorageManager {

    void upsertPlayer(UUID uniqueId, String name);

    CompletableFuture<AuctionItem> createAuctionItem(UUID sellerUniqueId, String sellerName, BigDecimal price,
                                                     long expiredAt, List<ItemStack> itemStacks, AuctionEconomy auctionEconomy);
}
