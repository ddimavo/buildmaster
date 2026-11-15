package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

/** Заглушка для компиляции: только методы, которые использует BuildMaster. */
public interface Economy {
    String format(double amount);

    double getBalance(OfflinePlayer player);

    boolean has(OfflinePlayer player, double amount);

    EconomyResponse withdrawPlayer(OfflinePlayer player, double amount);

    EconomyResponse depositPlayer(OfflinePlayer player, double amount);
}
