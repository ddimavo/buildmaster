package ru.buildmaster.support;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

/** Экономика в памяти для тестов. */
public final class FakeEconomy implements Economy {

    private final Map<UUID, Double> balances = new HashMap<>();

    public void set(OfflinePlayer p, double amount) {
        balances.put(p.getUniqueId(), amount);
    }

    @Override
    public String format(double amount) {
        return String.format("%.0f$", amount);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return balances.getOrDefault(player.getUniqueId(), 0.0);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (!has(player, amount)) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "нет денег");
        }
        balances.put(player.getUniqueId(), getBalance(player) - amount);
        return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        balances.put(player.getUniqueId(), getBalance(player) + amount);
        return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }
}
