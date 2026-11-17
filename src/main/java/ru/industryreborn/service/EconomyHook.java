package ru.industryreborn.service;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Обёртка над Vault. Провайдер (ExcellentEconomy, Essentials и т.п.) запрашивается при каждом обращении: он может
 * зарегистрироваться позже или быть заменён (например, при перезагрузке экономического плагина или смене валюты).
 */
public final class EconomyHook {

    private Economy eco() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    /** Название плагина-провайдера экономики (для сообщений и диагностики) либо null. */
    public String providerName() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getPlugin().getName();
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
