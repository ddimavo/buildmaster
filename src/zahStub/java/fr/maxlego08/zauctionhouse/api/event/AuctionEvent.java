package fr.maxlego08.zauctionhouse.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class AuctionEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    public AuctionEvent() {
        super();
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
}
