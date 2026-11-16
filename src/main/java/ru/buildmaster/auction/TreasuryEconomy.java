package ru.buildmaster.auction;

import fr.maxlego08.zauctionhouse.api.economy.AuctionEconomy;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import ru.buildmaster.BuildMasterPlugin;

/**
 * Экономика для лотов казны. Всё делегируется настоящей экономике аукциона (валюта, формат, налоги, пределы цен), кроме
 * счёта продавца-казны: деньги ему идут в казну BuildMaster, а не в кошелёк несуществующего игрока.
 * Реализована через Proxy, чтобы не зависеть от полного списка методов интерфейса конкретной версии zAuctionHouse.
 */
public final class TreasuryEconomy {

    private TreasuryEconomy() { }

    public static AuctionEconomy create(BuildMasterPlugin plugin, Supplier<AuctionEconomy> base) {
        InvocationHandler handler = (proxy, method, args) -> handle(plugin, base, proxy, method, args);
        return (AuctionEconomy) Proxy.newProxyInstance(AuctionEconomy.class.getClassLoader(),
                new Class<?>[] {AuctionEconomy.class}, handler);
    }

    private static boolean isTreasury(Object[] args) {
        return args != null && args.length > 0 && TreasuryAuction.SYSTEM_ID.equals(args[0]);
    }

    private static Object handle(BuildMasterPlugin plugin, Supplier<AuctionEconomy> base, Object proxy, Method method,
                                 Object[] args) throws Throwable {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            return switch (name) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "TreasuryEconomy";
            };
        }
        switch (name) {
            case "getName" -> {
                return TreasuryAuction.ECONOMY_NAME;
            }
            // деньги за лот казны зачисляются сразу и всегда: отложенной выплаты и ожидания продавца нет
            case "isAutoClaim", "supportsOfflineDeposit" -> {
                return true;
            }
            case "mustBeOnline" -> {
                return false;
            }
            default -> { }
        }
        if (isTreasury(args)) {
            switch (name) {
                case "get" -> {
                    return CompletableFuture.completedFuture(BigDecimal.valueOf(plugin.treasury().balance()));
                }
                case "has" -> {
                    return CompletableFuture.completedFuture(
                            BigDecimal.valueOf(plugin.treasury().balance()).compareTo((BigDecimal) args[1]) >= 0);
                }
                case "deposit", "depositChecked" -> {
                    BigDecimal amount = (BigDecimal) args[1];
                    plugin.treasury().deposit(amount.doubleValue(), "Продажа лота казны на аукционе");
                    plugin.saveTreasury();
                    return name.equals("depositChecked") ? Boolean.TRUE : null;
                }
                case "withdraw", "withdrawChecked" -> {
                    // из казны через аукцион ничего не списывается
                    if (name.equals("withdrawChecked")) {
                        return Boolean.FALSE;
                    }
                    throw new IllegalStateException("Казну нельзя списывать через аукцион");
                }
                default -> { }
            }
        }
        AuctionEconomy delegate = base.get();
        if (delegate == null) {
            throw new IllegalStateException("Базовая экономика аукциона не загружена");
        }
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
