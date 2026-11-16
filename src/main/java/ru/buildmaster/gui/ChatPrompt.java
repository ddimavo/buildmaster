package ru.buildmaster.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.util.Text;

/** Ввод текста из чата (поиск блока в меню). Следующее сообщение игрока не попадает в общий чат. */
public final class ChatPrompt implements Listener {

    private final BuildMasterPlugin plugin;
    private final Map<UUID, Consumer<String>> waiting = new HashMap<>();

    public ChatPrompt(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player player, String hint, Consumer<String> callback) {
        player.closeInventory();
        waiting.put(player.getUniqueId(), callback);
        Text.send(player, "<yellow>" + hint + " <gray>(напишите <white>отмена</white>, чтобы вернуться)");
    }

    /** Игрок ждёт ввода. */
    public boolean isWaiting(Player player) {
        return waiting.containsKey(player.getUniqueId());
    }

    /** Передаёт текст ожидающему вводу (для чата и тестов). false - игрок ничего не вводил. */
    public boolean deliver(Player player, String text) {
        Consumer<String> callback = waiting.remove(player.getUniqueId());
        if (callback == null) {
            return false;
        }
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(text.trim()));
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (deliver(event.getPlayer(), text)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }
}
