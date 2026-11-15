package ru.buildmaster.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.Text;

/** При входе: выдаёт возвращённые предметы и предупреждает о скором окончании аренды. */
public final class JoinListener implements Listener {

    private final BuildMasterPlugin plugin;

    public JoinListener(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            int before = plugin.pending().count(player.getUniqueId());
            if (before > 0) {
                int left = plugin.pending().claim(player);
                if (left == 0) {
                    Text.ok(player, "Вам возвращены предметы с освобождённого участка: " + before + " шт.");
                } else {
                    Text.send(player, "<yellow>Вам возвращены не все предметы (осталось " + left
                            + " шт.): освободите место и введите <white>/district claim</white>.");
                }
            }
            long day = plugin.service().dayMillis();
            for (Plot plot : plugin.plots().ownedBy(player.getUniqueId())) {
                long left = plot.rentedUntil - System.currentTimeMillis();
                if (plot.status == Plot.Status.RENTED && left < day) {
                    Text.send(player, "<yellow>Аренда участка <white>" + plot.id + "</white> заканчивается через "
                            + Text.duration(left) + (plot.autoRenew ? " (будет продлена автоматически)." : "."));
                }
            }
        }, 40L);
    }
}
