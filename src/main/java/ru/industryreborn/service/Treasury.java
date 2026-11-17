package ru.industryreborn.service;

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
    /** Приход и расход по часам (индекс часа - время / 3 600 000): для расчётов, не зависит от длины журнала. */
    private final java.util.TreeMap<Long, double[]> hourly = new java.util.TreeMap<>();
    private static final int MAX_HOURS = 24 * 14;

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

    /** Приход в казну с момента since (мс). */
    public synchronized double incomeSince(long since) {
        long from = Math.floorDiv(since, 3_600_000L);
        double sum = 0;
        for (var e : hourly.tailMap(from, true).entrySet()) {
            sum += e.getValue()[0];
        }
        return sum;
    }

    /** Расход из казны с момента since (мс). */
    public synchronized double expenseSince(long since) {
        long from = Math.floorDiv(since, 3_600_000L);
        double sum = 0;
        for (var e : hourly.tailMap(from, true).entrySet()) {
            sum += e.getValue()[1];
        }
        return sum;
    }

    private void record(long time, double amount, String actor, String reason) {
        double[] bucket = hourly.computeIfAbsent(Math.floorDiv(time, 3_600_000L), k -> new double[2]);
        bucket[amount >= 0 ? 0 : 1] += Math.abs(amount);
        while (hourly.size() > MAX_HOURS) {
            hourly.pollFirstEntry();
        }
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
        List<String> hours = new ArrayList<>();
        hourly.forEach((h, v) -> hours.add(h + "|" + v[0] + "|" + v[1]));
        yml.set("hourly", hours);
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
        for (String line : yml.getStringList("hourly")) {
            String[] p = line.split("\\|");
            if (p.length == 3) {
                t.hourly.put(Long.parseLong(p[0]), new double[] {Double.parseDouble(p[1]), Double.parseDouble(p[2])});
            }
        }
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
