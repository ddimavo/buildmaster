package fr.maxlego08.zauctionhouse.api.economy;

import fr.maxlego08.zauctionhouse.api.item.ItemType;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Подмножество: IndustryReborn не реализует интерфейс напрямую, а оборачивает настоящую экономику через java.lang.reflect.Proxy,
 * поэтому полный набор методов здесь не нужен.
 */
public interface AuctionEconomy {

    String getName();

    String getDisplayName();

    CompletableFuture<BigDecimal> get(UUID playerId);

    CompletableFuture<Boolean> has(UUID playerId, BigDecimal price);

    void deposit(UUID playerId, BigDecimal value, String reason);

    void withdraw(UUID playerId, BigDecimal value, String reason);

    default boolean withdrawChecked(UUID playerId, BigDecimal value, String reason) {
        withdraw(playerId, value, reason);
        return true;
    }

    default boolean depositChecked(UUID playerId, BigDecimal value, String reason) {
        deposit(playerId, value, reason);
        return true;
    }

    default boolean supportsOfflineDeposit() {
        return true;
    }

    boolean isAutoClaim();

    boolean mustBeOnline();

    BigDecimal getMaxPrice(ItemType itemType);

    BigDecimal getMinPrice(ItemType itemType);
}
