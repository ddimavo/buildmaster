package ru.buildmaster.auction;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;

/** Связь с аукционом. Отдельный интерфейс без классов zAuctionHouse: сервер работает и без этого плагина. */
public interface AuctionPort {

    /** Аукцион работает и принимает лоты от казны (на одном сервере, без кластера). */
    boolean ready();

    /** Сколько лотов казны выставлено сейчас. */
    int listedByTreasury();

    /** Выставляет предмет от имени казны. true - лот создан; исключение или false - нет, предмет нужно вернуть. */
    CompletableFuture<Boolean> list(ItemStack item, BigDecimal price, long expiredAt);
}
