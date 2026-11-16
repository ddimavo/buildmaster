package fr.maxlego08.zauctionhouse.api.event.events;

import fr.maxlego08.zauctionhouse.api.economy.EconomyManager;
import fr.maxlego08.zauctionhouse.api.event.AuctionEvent;

/** Подмножество: вызывается во время загрузки экономик, до чтения economies.yml. */
public class AuctionLoadEconomyEvent extends AuctionEvent {

    private final EconomyManager economyManager;

    public AuctionLoadEconomyEvent(EconomyManager economyManager) {
        this.economyManager = economyManager;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }
}
