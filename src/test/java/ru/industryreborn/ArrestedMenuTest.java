package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.industryreborn.model.StorageEntry;
import ru.industryreborn.service.Journal;
import ru.industryreborn.support.PluginTestBase;

/** Меню арестованных вещей: осмотр, решения по партии и по всем вещам игрока, причина обязательна. */
@SuppressWarnings("removal")
class ArrestedMenuTest extends PluginTestBase {

    private PlayerMock boss;
    private PlayerMock steve;
    private PlayerMock alex;

    @BeforeEach
    void setUp() {
        boss = admin("Boss");
        steve = player("Steve", 0);
        alex = player("Alex", 0);
    }

    private void arrest(UUID owner, String plot, Material m, int amount) {
        plugin.storage().add(owner, plot, List.of(new ItemStack(m, amount)), StorageEntry.State.ARRESTED, "нарушение", "b-" + plot);
    }

    private int count(UUID owner, StorageEntry.State state) {
        return plugin.storage().countItems(owner, state);
    }

    private static String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    private void answer(PlayerMock p, String text) {
        assertTrue(plugin.chatPrompt().isWaiting(p), "причина должна запрашиваться");
        assertTrue(plugin.chatPrompt().deliver(p, text));
        server.getScheduler().performTicks(2);
    }

    @Test
    void theListShowsPlayersWithArrestedItems() {
        arrest(steve.getUniqueId(), "a", Material.DIAMOND, 5);
        arrest(alex.getUniqueId(), "b", Material.GOLD_INGOT, 3);
        plugin.storage().add(UUID.randomUUID(), "c", List.of(new ItemStack(Material.DIRT)), StorageEntry.State.AVAILABLE, null, "x");
        boss.performCommand("district arrest");
        assertEquals(2, plugin.arrests().players().size());
        var inv = boss.getOpenInventory().getTopInventory();
        assertEquals(Material.PLAYER_HEAD, inv.getItem(0).getType());
        assertEquals(Material.PLAYER_HEAD, inv.getItem(1).getType());
        assertNotEquals(Material.PLAYER_HEAD, inv.getItem(2) == null ? Material.AIR : inv.getItem(2).getType());
    }

    @Test
    void anEmptyListSaysSo() {
        boss.performCommand("district arrest");
        assertEquals(Material.LIME_DYE, boss.getOpenInventory().getTopInventory().getItem(22).getType());
    }

    @Test
    void playersCannotOpenTheMenu() {
        arrest(steve.getUniqueId(), "a", Material.DIAMOND, 5);
        steve.performCommand("district arrest");
        assertTrue(drain(steve).contains("Недостаточно прав"));
        new ru.industryreborn.gui.ArrestedMenu(plugin, steve, 0).open();
        assertEquals(Material.BARRIER, steve.getOpenInventory().getTopInventory().getItem(22).getType());
        // прямой вызов решения без прав тоже ничего не меняет в меню игрока
        new ru.industryreborn.gui.ArrestedPlayerMenu(plugin, steve, steve.getUniqueId(), 0).open();
        assertTrue(steve.getOpenInventory().getTopInventory().getItem(0).getType().name().endsWith("_STAINED_GLASS_PANE"));
    }

    @Test
    void theMainMenuLinksToItForAdminsOnly() {
        arrest(steve.getUniqueId(), "a", Material.DIAMOND, 5);
        new ru.industryreborn.gui.MainMenu(plugin, boss).open();
        assertEquals(Material.IRON_BARS, boss.getOpenInventory().getTopInventory().getItem(23).getType());
        boss.simulateInventoryClick(23);
        assertEquals(Material.PLAYER_HEAD, boss.getOpenInventory().getTopInventory().getItem(0).getType());
        new ru.industryreborn.gui.MainMenu(plugin, steve).open();
        assertNotEquals(Material.IRON_BARS, steve.getOpenInventory().getTopInventory().getItem(23).getType());
    }

    @Test
    void thePlayerViewShowsBatchesWithContentsAndReason() {
        arrest(steve.getUniqueId(), "a", Material.DIAMOND, 5);
        arrest(steve.getUniqueId(), "b", Material.EMERALD, 2);
        new ru.industryreborn.gui.ArrestedPlayerMenu(plugin, boss, steve.getUniqueId(), 0).open();
        var inv = boss.getOpenInventory().getTopInventory();
        assertEquals(Material.DIAMOND, inv.getItem(0).getType());
        assertEquals(Material.EMERALD, inv.getItem(1).getType());
        String lore = inv.getItem(0).getItemMeta().lore().toString();
        assertTrue(lore.contains("нарушение"));
        assertTrue(lore.contains("DIAMOND"));
    }

    @Test
    void releasingOneBatchLeavesTheOthersArrested() {
        arrest(steve.getUniqueId(), "a", Material.DIAMOND, 5);
        arrest(steve.getUniqueId(), "b", Material.EMERALD, 2);
        new ru.industryreborn.gui.ArrestedPlayerMenu(plugin, boss, steve.getUniqueId(), 0).open();
        boss.simulateInventoryClick(0);                       // партия a
        var decision = boss.getOpenInventory().getTopInventory();
        assertEquals(Material.DIAMOND, decision.getItem(0).getType());
        assertTrue(decision.getItem(1).getType().name().endsWith("_STAINED_GLASS_PANE"), "только вещи этой партии");
        boss.simulateInventoryClick(46);                      // вернуть
        answer(boss, "ошибка ареста");
        assertEquals(5, count(steve.getUniqueId(), StorageEntry.State.AVAILABLE));
        assertEquals(2, count(steve.getUniqueId(), StorageEntry.State.ARRESTED));
        assertTrue(plugin.journal().forPlot("-").stream().anyMatch(e -> e.action() == Journal.Action.STORAGE_RELEASED
                && e.reason().equals("ошибка ареста") && e.actor().equals("Boss")));
        assertTrue(drain(steve).contains("ошибка ареста"));
    }

    @Test
    void deletingAllOfAPlayersItemsAsksForAReason() {
        arrest(steve.getUniqueId(), "a", Material.DIAMOND, 5);
        arrest(steve.getUniqueId(), "b", Material.EMERALD, 2);
        new ru.industryreborn.gui.ArrestedPlayerMenu(plugin, boss, steve.getUniqueId(), 0).open();
        boss.simulateInventoryClick(49);                      // по всем вещам
        boss.simulateInventoryClick(52);                      // удалить
        answer(boss, "   ");
        assertEquals(7, count(steve.getUniqueId(), StorageEntry.State.ARRESTED), "пустая причина - действие отменено");
        new ru.industryreborn.gui.ArrestDecisionMenu(plugin, boss, steve.getUniqueId(), null).open();
        boss.simulateInventoryClick(52);
        answer(boss, "отмена");
        assertEquals(7, count(steve.getUniqueId(), StorageEntry.State.ARRESTED));
        new ru.industryreborn.gui.ArrestDecisionMenu(plugin, boss, steve.getUniqueId(), null).open();
        boss.simulateInventoryClick(52);
        answer(boss, "читерские вещи");
        assertEquals(0, count(steve.getUniqueId(), StorageEntry.State.ARRESTED));
        assertEquals(0, count(steve.getUniqueId(), StorageEntry.State.AVAILABLE), "вещи удалены, а не возвращены");
        assertTrue(plugin.journal().forPlot("-").stream().anyMatch(e -> e.action() == Journal.Action.STORAGE_DELETED));
    }

    @Test
    void sendingToTheAuctionQueuesTheItems() {
        arrest(alex.getUniqueId(), "a", Material.GOLD_INGOT, 3);
        new ru.industryreborn.gui.ArrestDecisionMenu(plugin, boss, alex.getUniqueId(), null).open();
        boss.simulateInventoryClick(49);
        answer(boss, "конфискация");
        assertEquals(3, count(alex.getUniqueId(), StorageEntry.State.AUCTION));
        assertEquals(0, count(alex.getUniqueId(), StorageEntry.State.ARRESTED));
        assertEquals(3, plugin.treasuryAuction().waiting());
    }

    @Test
    void theCommandKeepsWorkingThroughTheSameService() {
        arrest(steve.getUniqueId(), "a", Material.DIAMOND, 5);
        boss.performCommand("district arrest release Steve ошибка");
        assertEquals(5, count(steve.getUniqueId(), StorageEntry.State.AVAILABLE));
        boss.performCommand("district arrest delete Steve причина");
        assertTrue(drain(boss).contains("нет арестованных"));
    }

    @Test
    void decideRequiresAReasonAndExistingItems() {
        arrest(steve.getUniqueId(), "a", Material.DIAMOND, 5);
        assertNotNull(plugin.arrests().decide("Boss", steve.getUniqueId(), null,
                ru.industryreborn.service.ArrestService.Decision.DELETE, " "));
        assertNotNull(plugin.arrests().decide("Boss", alex.getUniqueId(), null,
                ru.industryreborn.service.ArrestService.Decision.DELETE, "причина"));
        assertNotNull(plugin.arrests().decide("Boss", steve.getUniqueId(), UUID.randomUUID(),
                ru.industryreborn.service.ArrestService.Decision.DELETE, "причина"), "чужой id партии");
        assertEquals(5, count(steve.getUniqueId(), StorageEntry.State.ARRESTED));
    }
}
