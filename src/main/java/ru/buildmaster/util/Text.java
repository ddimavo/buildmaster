package ru.buildmaster.util;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<gold>[Участки]</gold> ";

    private Text() { }

    public static Component mm(String s) {
        return MM.deserialize(s);
    }

    /** Без курсива - для названий и описаний предметов в меню. */
    public static Component item(String s) {
        return MM.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    public static String esc(String s) {
        return MM.escapeTags(s);
    }

    public static void send(CommandSender to, String s) {
        to.sendMessage(mm(PREFIX + s));
    }

    public static void ok(CommandSender to, String s) {
        send(to, "<green>" + s);
    }

    public static void err(CommandSender to, String s) {
        send(to, "<red>" + s);
    }

    public static ItemStack icon(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (name != null) {
            meta.displayName(item(name));
        }
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(item(l));
            }
            meta.lore(lines);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** Остаток времени в виде "2 д. 5 ч." */
    public static String duration(long millis) {
        if (millis <= 0) {
            return "0 мин.";
        }
        long minutes = millis / 60_000;
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(" д. ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append(" ч. ");
        }
        if (days == 0) {
            sb.append(mins).append(" мин.");
        }
        return sb.toString().trim();
    }
}
