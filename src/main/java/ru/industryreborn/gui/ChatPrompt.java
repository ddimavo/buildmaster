package ru.industryreborn.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashMap;
import java.util.List;
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
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.util.Text;

/** Ввод текста из чата (поиск блока в меню). Следующее сообщение игрока не попадает в общий чат. */
public final class ChatPrompt implements Listener {

    private final IndustryRebornPlugin plugin;
    private final Map<UUID, Consumer<String>> waiting = new HashMap<>();

    public ChatPrompt(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Запрос текста. Если есть диалоги Paper - окно с полем ввода (кнопка «Отмена» отдаёт слово «отмена», как в чате);
     * иначе следующее сообщение игрока из чата.
     */
    public void ask(Player player, String hint, Consumer<String> callback) {
        var dialogs = plugin.dialogs();
        if (dialogs != null && dialogs.available()) {
            String title = hint.replaceAll("<[^>]+>", "").replaceAll("[:：]\\s*$", "").trim();
            String shortTitle = title.length() > 60 ? title.substring(0, 57) + "…" : title;
            dialogs.text(player, shortTitle.isEmpty() ? "Ввод" : shortTitle, List.of(), "Значение", "", 200,
                    callback, () -> callback.accept("отмена"));
            return;
        }
        askChat(player, hint, callback);
    }

    /** Ввод только через чат (запасной вариант окон ввода). */
    void askChat(Player player, String hint, Consumer<String> callback) {
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
