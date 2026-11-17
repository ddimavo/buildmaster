package ru.industryreborn.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.util.Text;

/** Решения администратора по арестованным вещам: вернуть игроку, удалить или отдать на аукцион. Всегда с причиной. */
public final class ArrestService {

    public enum Decision {
        RELEASE("Вернуть игроку", StorageEntry.State.AVAILABLE, Journal.Action.STORAGE_RELEASED),
        DELETE("Удалить", null, Journal.Action.STORAGE_DELETED),
        AUCTION("На аукцион", StorageEntry.State.AUCTION, Journal.Action.STORAGE_AUCTION);

        private final String title;
        private final StorageEntry.State state;
        private final Journal.Action action;

        Decision(String title, StorageEntry.State state, Journal.Action action) {
            this.title = title;
            this.state = state;
            this.action = action;
        }

        public String title() {
            return title;
        }

        public Journal.Action action() {
            return action;
        }
    }

    private final IndustryRebornPlugin plugin;

    public ArrestService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    /** Игроки с арестованными вещами. */
    public List<UUID> players() {
        return plugin.storage().playersWithState(StorageEntry.State.ARRESTED);
    }

    /** Арестованные записи игрока. */
    public List<StorageEntry> entries(UUID owner) {
        List<StorageEntry> list = new ArrayList<>();
        for (StorageEntry e : plugin.storage().entries(owner)) {
            if (e.state == StorageEntry.State.ARRESTED) {
                list.add(e);
            }
        }
        return list;
    }

    /**
     * Решение по одной записи (entryId) либо по всем арестованным вещам игрока (entryId == null).
     * Возвращает сообщение об ошибке или null.
     */
    public String decide(String actor, UUID target, UUID entryId, Decision decision, String reason) {
        if (reason == null || reason.isBlank()) {
            return "Причина обязательна.";
        }
        int n = plugin.storage().decideArrested(target, entryId, decision.state);
        if (n == 0) {
            return "Арестованных вещей не найдено.";
        }
        plugin.journal().add("-", target, actor, decision.action, reason);
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            Text.send(online, "<yellow>" + decision.action.title() + ". Причина: " + Text.esc(reason));
        }
        return null;
    }

    public int count(UUID owner) {
        return plugin.storage().countItems(owner, StorageEntry.State.ARRESTED);
    }
}
