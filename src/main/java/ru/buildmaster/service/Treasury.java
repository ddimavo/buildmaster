package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Казна: отдельный счёт, на который уходят оплата аренды и покупки административных участков,
 * комиссии и налоги. Деньги не создаются заново, а берутся из уже существующих в экономике.
 */
public final class Treasury {

    public record Tx(long time, double amount, String actor, String reason) { }

    private static final int MAX_LOG = 500;

    private double balance;
    private final List<Tx> log = new ArrayList<>();

    public synchronized double balance() {
        return balance;
    }

    public synchronized List<Tx> log() {
        return List.copyOf(log);
    }

    public synchronized void deposit(double amount, String reason) {
        if (amount <= 0) {
            return;
        }
        balance += amount;
        record(System.currentTimeMillis(), amount, "система", reason);
    }

    /** false - в казне не хватает средств. */
    public synchronized boolean withdraw(double amount, String actor, String reason) {
        if (amount <= 0 || amount > balance + 1e-9) {
            return false;
        }
        balance -= amount;
        record(System.currentTimeMillis(), -amount, actor, reason);
        return true;
    }

    private void record(long time, double amount, String actor, String reason) {
        log.add(new Tx(time, amount, actor, reason));
        while (log.size() > MAX_LOG) {
            log.remove(0);
        }
    }

    public synchronized void save(File file) throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("balance", balance);
        List<String> lines = new ArrayList<>();
        for (Tx tx : log) {
            lines.add(tx.time() + "|" + tx.amount() + "|" + esc(tx.actor()) + "|" + esc(tx.reason()));
        }
        yml.set("log", lines);
        file.getParentFile().mkdirs();
        yml.save(file);
    }

    public static Treasury load(File file) {
        Treasury t = new Treasury();
        if (!file.exists()) {
            return t;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        t.balance = yml.getDouble("balance");
        for (String line : yml.getStringList("log")) {
            String[] p = line.split("\\|", 4);
            if (p.length == 4) {
                t.log.add(new Tx(Long.parseLong(p[0]), Double.parseDouble(p[1]), unesc(p[2]), unesc(p[3])));
            }
        }
        return t;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("|", "\\p");
    }

    private static String unesc(String s) {
        return s.replace("\\p", "|").replace("\\\\", "\\");
    }
}
