package fr.maxlego08.zauctionhouse.api.economy;

import fr.maxlego08.zauctionhouse.api.item.ItemType;
import java.util.Optional;

/** Подмножество. */
public interface EconomyManager {

    boolean registerEconomy(AuctionEconomy economy);

    Optional<AuctionEconomy> getEconomy(String economyName);

    AuctionEconomy getDefaultEconomy(ItemType itemType);
}
