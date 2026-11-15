package net.milkbowl.vault.economy;

/** Заглушка для компиляции. Настоящий класс предоставляет плагин Vault. */
public class EconomyResponse {
    public final double amount;
    public final double balance;
    public final String errorMessage;

    public EconomyResponse(double amount, double balance, Object type, String errorMessage) {
        this.amount = amount;
        this.balance = balance;
        this.errorMessage = errorMessage;
    }

    public boolean transactionSuccess() {
        return false;
    }
}
