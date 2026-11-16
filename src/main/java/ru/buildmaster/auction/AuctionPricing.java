package ru.buildmaster.auction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/** Цена лота казны: цена за единицу (по материалу или общая) × количество × множитель, не ниже минимума. */
public final class AuctionPricing {

    private AuctionPricing() { }

    public static BigDecimal price(ConfigurationSection auction, ItemStack stack) {
        double unit = unitPrice(auction, stack.getType());
        double multiplier = auction.getDouble("price-multiplier", 1.0);
        double min = auction.getDouble("min-lot-price", 1.0);
        double total = Math.max(min, unit * Math.max(1, stack.getAmount()) * Math.max(0, multiplier));
        return BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
    }

    public static double unitPrice(ConfigurationSection auction, Material material) {
        double fallback = auction.getDouble("default-price", 10);
        ConfigurationSection prices = auction.getConfigurationSection("prices");
        if (prices == null || !prices.contains(material.name())) {
            return Math.max(0, fallback);
        }
        return Math.max(0, prices.getDouble(material.name(), fallback));
    }

    /** Цена, приведённая к пределам экономики аукциона (границы могут отсутствовать). */
    public static BigDecimal clamp(BigDecimal price, BigDecimal min, BigDecimal max) {
        BigDecimal result = price;
        if (min != null && result.compareTo(min) < 0) {
            result = min;
        }
        if (max != null && max.signum() > 0 && result.compareTo(max) > 0) {
            result = max;
        }
        return result;
    }
}
