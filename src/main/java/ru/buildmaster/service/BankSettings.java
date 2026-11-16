package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Настройки банка, которые меняет администратор: комиссии, штраф при изъятии, налоги, режим «только в банке».
 * null - значение берётся из config.yml. Хранятся в bank.yml (комментарии конфига не страдают).
 */
public final class BankSettings {

    private Double depositCommission;
    private Double withdrawCommission;
    private Double seizurePenalty;
    private Double rentTax;
    private Boolean onlyInBank;
    private final Map<String, Double> propertyTax = new LinkedHashMap<>();

    public Double depositCommission() {
        return depositCommission;
    }

    public void setDepositCommission(Double v) {
        depositCommission = v;
    }

    public Double withdrawCommission() {
        return withdrawCommission;
    }

    public void setWithdrawCommission(Double v) {
        withdrawCommission = v;
    }

    public Double seizurePenalty() {
        return seizurePenalty;
    }

    public void setSeizurePenalty(Double v) {
        seizurePenalty = v;
    }

    public Double rentTax() {
        return rentTax;
    }

    public void setRentTax(Double v) {
        rentTax = v;
    }

    public Boolean onlyInBank() {
        return onlyInBank;
    }

    public void setOnlyInBank(Boolean v) {
        onlyInBank = v;
    }

    public Double propertyTax(String category) {
        return propertyTax.get(category);
    }

    public void setPropertyTax(String category, Double amount) {
        if (amount == null) {
            propertyTax.remove(category);
        } else {
            propertyTax.put(category, amount);
        }
    }

    public void save(File file) throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("deposit-commission", depositCommission);
        yml.set("withdraw-commission", withdrawCommission);
        yml.set("seizure-penalty", seizurePenalty);
        yml.set("rent-tax", rentTax);
        yml.set("only-in-bank", onlyInBank);
        for (Map.Entry<String, Double> e : propertyTax.entrySet()) {
            yml.set("property-tax." + e.getKey(), e.getValue());
        }
        file.getParentFile().mkdirs();
        yml.save(file);
    }

    public static BankSettings load(File file) {
        BankSettings b = new BankSettings();
        if (!file.exists()) {
            return b;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        b.depositCommission = yml.contains("deposit-commission") ? yml.getDouble("deposit-commission") : null;
        b.withdrawCommission = yml.contains("withdraw-commission") ? yml.getDouble("withdraw-commission") : null;
        b.seizurePenalty = yml.contains("seizure-penalty") ? yml.getDouble("seizure-penalty") : null;
        b.rentTax = yml.contains("rent-tax") ? yml.getDouble("rent-tax") : null;
        b.onlyInBank = yml.contains("only-in-bank") ? yml.getBoolean("only-in-bank") : null;
        ConfigurationSection tax = yml.getConfigurationSection("property-tax");
        if (tax != null) {
            for (String key : tax.getKeys(false)) {
                b.propertyTax.put(key, tax.getDouble(key));
            }
        }
        return b;
    }
}
