package fr.maxlego08.zauctionhouse.api.cluster;

import fr.maxlego08.zauctionhouse.api.item.Item;
import java.util.concurrent.CompletableFuture;

/** Подмножество. */
public interface AuctionClusterBridge {

    CompletableFuture<Void> notifyItemListed(Item item);

    default boolean isDistributed() {
        return false;
    }
}
