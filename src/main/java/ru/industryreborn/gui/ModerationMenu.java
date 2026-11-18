package ru.industryreborn.gui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Plot;
import ru.industryreborn.model.Request;
import ru.industryreborn.service.TextRules;
import ru.industryreborn.util.Text;

/** Заявки игроков, ожидающие проверки администратором. */
public final class ModerationMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private static final int FIRST = 9;

    public ModerationMenu(IndustryRebornPlugin plugin, Player player, int page) {
        super(plugin, player, "<dark_gray>Район › Модерация", 6);
        List<Request> list = plugin.moderation().pending();
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= list.size()) {
                break;
            }
            Request r = list.get(index);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Участок: <white>" + Text.esc(r.plotId));
            lore.add("<gray>Игрок: <white>" + Text.esc(r.requesterName));
            lore.add("<gray>Подано: <white>" + Text.date(r.createdAt));
            lore.add("<gray>Текст:");
            for (String l : TextRules.wrap(plugin.moderation().describe(r), 38)) {
                lore.add("<white>" + Text.esc(l));
            }
            lore.add("");
            lore.add("<green>ЛКМ - одобрить");
            lore.add("<red>ПКМ - отклонить (потребуется причина)");
            lore.add("<aqua>Shift+ЛКМ - телепорт к участку");
            if (r.type == Request.Type.HOLO) {
                lore.add("<aqua>Shift+ЛКМ - телепорт на предложенную позицию");
            }
            set(FIRST + i, Text.icon(r.type == Request.Type.NAME ? Material.NAME_TAG
                            : r.type == Request.Type.HOLO ? Material.ENDER_EYE
                            : r.type == Request.Type.ENCHANT ? Material.ENCHANTED_BOOK
                            : r.type == Request.Type.ROOM_CLASS ? Material.RED_BED : Material.WRITABLE_BOOK,
                    "<gold>" + r.type.title(), lore.toArray(new String[0])), e -> {
                        if (e.isShiftClick()) {
                            Plot plot = plugin.plots().get(r.plotId);
                            org.bukkit.Location proposed = r.type == Request.Type.HOLO
                                    ? plugin.moderation().holoLocation(r) : null;
                            if (proposed != null) {
                                player.closeInventory();
                                player.teleport(proposed);
                            } else if (plot != null) {
                                player.closeInventory();
                                plugin.service().teleport(player, plot);
                            }
                        } else if (e.isRightClick()) {
                            plugin.chatPrompt().ask(player, "Укажите причину отклонения (обязательно):", reason -> {
                                if (reason.equalsIgnoreCase("отмена") || reason.isBlank()) {
                                    Text.err(player, "Заявка не отклонена: причина обязательна.");
                                } else {
                                    plugin.moderation().reject(r, player.getName(), reason);
                                }
                                new ModerationMenu(plugin, player, current).open();
                            });
                        } else {
                            plugin.moderation().approve(r, player.getName());
                            new ModerationMenu(plugin, player, current).open();
                        }
                    });
        }
        if (list.isEmpty()) {
            set(22, Text.icon(Material.LIME_DYE, "<green>Новых заявок нет"));
        }
        set(4, Text.icon(Material.ANVIL, "<red>Модерация", "<gray>Ожидают проверки: <white>" + list.size(),
                "<gray>ЛКМ - одобрить, ПКМ - отклонить с причиной"));
        navBar(null, null, current, pages, p -> new ModerationMenu(plugin, player, p).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
