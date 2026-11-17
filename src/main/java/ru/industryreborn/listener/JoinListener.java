package ru.industryreborn.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.util.Text;

/** При входе: выдаёт возвращённые предметы и предупреждает о скором окончании аренды. */
public final class JoinListener implements Listener {

    private final IndustryRebornPlugin plugin;

    public JoinListener(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            int stored = plugin.storage().countItems(player.getUniqueId(), ru.industryreborn.model.StorageEntry.State.AVAILABLE);
            if (stored > 0) {
                Text.send(player, "<yellow>В вашем хранилище ждут вещи: " + stored + " шт. Откройте <white>/district storage</white>.");
            }
            int arrested = plugin.storage().countItems(player.getUniqueId(), ru.industryreborn.model.StorageEntry.State.ARRESTED);
            if (arrested > 0) {
                Text.send(player, "<red>Часть ваших вещей арестована до решения администрации (" + arrested + " шт.).");
            }
            plugin.moderation().deliverMissed(player);
            int unread = plugin.warnings().unread(player.getUniqueId());
            if (unread > 0) {
                Text.send(player, "<gold>У вас непрочитанных предупреждений: " + unread + ". Откройте <white>/district warnings</white>.");
            }
            long day = plugin.service().dayMillis();
            for (Plot plot : plugin.plots().ownedBy(player.getUniqueId())) {
                long left = plot.rentedUntil - System.currentTimeMillis();
                if (plot.isFrozen()) {
                    Text.send(player, "<red>Участок <white>" + plot.id + "</white> заморожен: не хватило средств на продление. "
                            + "Осталось времени на погашение: " + Text.duration(plot.graceUntil - System.currentTimeMillis()));
                } else if (plot.status == Plot.Status.RENTED && left < day) {
                    Text.send(player, "<yellow>Аренда участка <white>" + plot.id + "</white> заканчивается через "
                            + Text.duration(left) + (plot.autoRenew ? " (будет продлена автоматически)." : "."));
                }
            }
        }, 40L);
    }
}
