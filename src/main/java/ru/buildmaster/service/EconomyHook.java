package ru.buildmaster.service;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Обёртка над Vault. Провайдер экономики ищется лениво - он может зарегистрироваться позже. */
public final class EconomyHook {

    private Economy economy;

    private Economy eco() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
            }
        }
        return economy;
    }

    public boolean available() {
        return eco() != null;
    }

    public String format(double amount) {
        Economy e = eco();
        return e == null ? String.format("%.2f", amount) : e.format(amount);
    }

    /** Баланс игрока или 0, если экономики нет. */
    public double balance(OfflinePlayer player) {
        Economy e = eco();
        return e == null ? 0 : e.getBalance(player);
    }

    public boolean has(OfflinePlayer player, double amount) {
        Economy e = eco();
        return e != null && e.has(player, amount);
    }

    /** Списывает сумму; false - не хватило денег или экономики нет. */
    public boolean withdraw(OfflinePlayer player, double amount) {
        Economy e = eco();
        if (e == null) {
            return false;
        }
        if (amount <= 0) {
            return true;
        }
        return e.has(player, amount) && e.withdrawPlayer(player, amount).transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        Economy e = eco();
        if (e == null) {
            return false;
        }
        return amount <= 0 || e.depositPlayer(player, amount).transactionSuccess();
    }
}
