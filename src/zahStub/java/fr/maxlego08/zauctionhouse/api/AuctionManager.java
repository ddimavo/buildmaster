package fr.maxlego08.zauctionhouse.api;

import fr.maxlego08.zauctionhouse.api.cache.PlayerCacheKey;
import fr.maxlego08.zauctionhouse.api.item.Item;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.entity.Player;

/** Подмножество. */
public interface AuctionManager {

    void addItem(StorageType storageType, Item item);

    void clearPlayersCache(PlayerCacheKey... keys);

    void updateListedItems(Item item, boolean added, Player ignoredPlayer);

    List<Item> getItems(StorageType storageType, Predicate<Item> predicate);
}
