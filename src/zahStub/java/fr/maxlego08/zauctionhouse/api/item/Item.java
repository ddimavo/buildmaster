package fr.maxlego08.zauctionhouse.api.item;

import java.util.UUID;

/** Подмножество. */
public interface Item {

    int getId();

    UUID getSellerUniqueId();
}
