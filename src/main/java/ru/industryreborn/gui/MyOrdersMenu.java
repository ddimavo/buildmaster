package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.ServiceOrder;
import ru.industryreborn.service.ServiceCatalog;
import ru.industryreborn.util.Text;

/** Заказы игрока во всех мастерских: готовые - забрать, ожидающие - отменить. */
public final class MyOrdersMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    public MyOrdersMenu(IndustryRebornPlugin plugin, Player player, int page) {
        super(plugin, player, "<dark_gray>Мои заказы", 6);
        List<ServiceOrder> orders = plugin.orders().ofClient(player.getUniqueId());
        int pages = Math.max(1, (orders.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= orders.size()) {
                break;
            }
            ServiceOrder o = orders.get(index);
            ServiceCatalog.Offer def = plugin.catalog().get(o.offerKey);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Мастерская: <white>" + Text.esc(o.plotId));
            lore.add("<gray>Объём: <white>" + o.units + ", оплачено " + plugin.economy().format(o.paid));
            switch (o.state) {
                case PENDING -> {
                    lore.add("<gray>В очереди, примерно: <white>" + plugin.orders().eta(o));
                    lore.add("<yellow>Нажмите, чтобы отменить (деньги и материалы вернутся)");
                }
                case ACTIVE -> {
                    lore.add("<yellow>Выполняется: " + o.unitsDone + "/" + o.units);
                    lore.add("<gray>Осталось: <white>" + plugin.orders().eta(o));
                }
                case READY -> lore.add("<green>Готов! Нажмите, чтобы забрать");
            }
            Material icon = o.state == ServiceOrder.State.READY ? Material.CHEST
                    : o.state == ServiceOrder.State.ACTIVE ? Material.FURNACE : Material.CLOCK;
            set(i, Text.icon(icon, "<gold>" + Text.esc(def == null ? o.offerKey : def.title()) + " <gray>№ " + o.shortId(),
                    lore.toArray(new String[0])), e -> {
                        String error = switch (o.state) {
                            case READY -> plugin.orders().collect(player, o.id);
                            case PENDING -> plugin.orders().cancel(player, o.id);
                            case ACTIVE -> "Заказ уже выполняется: отменить нельзя.";
                        };
                        if (error != null) {
                            Text.err(player, error);
                        }
                        new MyOrdersMenu(plugin, player, current).open();
                    });
        }
        if (orders.isEmpty()) {
            set(22, Text.icon(Material.BARRIER, "<gray>Заказов нет", "<gray>Откройте мастерскую и выберите «Услуги»"));
        }
        if (current > 0) {
            set(45, Ui.prev(current, pages), e -> new MyOrdersMenu(plugin, player, current - 1).open());
        }
        set(49, Ui.home(), e -> new MainMenu(plugin, player).open());
        if (current < pages - 1) {
            set(53, Ui.next(current, pages), e -> new MyOrdersMenu(plugin, player, current + 1).open());
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
