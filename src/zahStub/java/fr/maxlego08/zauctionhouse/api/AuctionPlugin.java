package fr.maxlego08.zauctionhouse.api;

import fr.maxlego08.zauctionhouse.api.category.CategoryManager;
import fr.maxlego08.zauctionhouse.api.cluster.AuctionClusterBridge;
import fr.maxlego08.zauctionhouse.api.economy.EconomyManager;
import fr.maxlego08.zauctionhouse.api.storage.StorageManager;
import org.bukkit.plugin.Plugin;

/** Подмножество: точка входа, регистрируется в ServicesManager. */
public interface AuctionPlugin extends Plugin {

    StorageManager getStorageManager();

    AuctionManager getAuctionManager();

    EconomyManager getEconomyManager();

    CategoryManager getCategoryManager();

    AuctionClusterBridge getAuctionClusterBridge();
}
