package com.Acrobot.ChestShop.Events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** Заглушка события ChestShop для тестов (настоящий ChestShop в тестах не загружается). */
public class PreTransactionEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player client;
    private final ItemStack[] stock;
    private boolean cancelled;

    public PreTransactionEvent(Player client, ItemStack[] stock) {
        this.client = client;
        this.stock = stock;
    }

    public Player getClient() {
        return client;
    }

    public ItemStack[] getStock() {
        return stock;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
