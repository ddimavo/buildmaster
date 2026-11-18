package ru.industryreborn.gui;

import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.industryreborn.util.Text;

/** Общие элементы оформления меню: цвета разделов, кнопки навигации, звуки. */
final class Ui {

    private static final Map<String, Material> THEMES = Map.ofEntries(
            // участки и здания
            Map.entry("PlotListMenu", Material.LIGHT_BLUE_STAINED_GLASS_PANE),
            Map.entry("PlotMenu", Material.LIGHT_BLUE_STAINED_GLASS_PANE),
            Map.entry("RoomsMenu", Material.LIGHT_BLUE_STAINED_GLASS_PANE),
            Map.entry("RightsMenu", Material.LIGHT_BLUE_STAINED_GLASS_PANE),
            Map.entry("PlayerRightsMenu", Material.LIGHT_BLUE_STAINED_GLASS_PANE),
            // деньги
            Map.entry("BankMenu", Material.YELLOW_STAINED_GLASS_PANE),
            Map.entry("AccountsMenu", Material.YELLOW_STAINED_GLASS_PANE),
            Map.entry("AccountMenu", Material.YELLOW_STAINED_GLASS_PANE),
            Map.entry("BankSettingsMenu", Material.YELLOW_STAINED_GLASS_PANE),
            // мастерские и услуги
            Map.entry("FactoryMenu", Material.ORANGE_STAINED_GLASS_PANE),
            Map.entry("ServicesMenu", Material.ORANGE_STAINED_GLASS_PANE),
            Map.entry("OffersMenu", Material.ORANGE_STAINED_GLASS_PANE),
            Map.entry("MyOrdersMenu", Material.ORANGE_STAINED_GLASS_PANE),
            Map.entry("GoodsMenu", Material.ORANGE_STAINED_GLASS_PANE),
            Map.entry("StorefrontMenu", Material.ORANGE_STAINED_GLASS_PANE),
            // электросеть
            Map.entry("GridMenu", Material.PURPLE_STAINED_GLASS_PANE),
            Map.entry("PlantMenu", Material.PURPLE_STAINED_GLASS_PANE),
            Map.entry("ReportMenu", Material.PURPLE_STAINED_GLASS_PANE),
            // работа
            Map.entry("JobBoardMenu", Material.LIME_STAINED_GLASS_PANE),
            Map.entry("MyJobsMenu", Material.LIME_STAINED_GLASS_PANE),
            Map.entry("StaffMenu", Material.LIME_STAINED_GLASS_PANE),
            // личное
            Map.entry("StorageMenu", Material.CYAN_STAINED_GLASS_PANE),
            Map.entry("JournalMenu", Material.CYAN_STAINED_GLASS_PANE),
            Map.entry("WarningsMenu", Material.CYAN_STAINED_GLASS_PANE),
            // администрирование
            Map.entry("ModerationMenu", Material.RED_STAINED_GLASS_PANE),
            Map.entry("ArrestedMenu", Material.RED_STAINED_GLASS_PANE),
            Map.entry("ArrestedPlayerMenu", Material.RED_STAINED_GLASS_PANE),
            Map.entry("ArrestDecisionMenu", Material.RED_STAINED_GLASS_PANE),
            Map.entry("ConfirmMenu", Material.RED_STAINED_GLASS_PANE));

    private Ui() { }

    /** Цвет края окна для раздела (по умолчанию тёмный). */
    static Material theme(Class<?> menu) {
        return THEMES.getOrDefault(menu.getSimpleName(), Material.BLACK_STAINED_GLASS_PANE);
    }

    static ItemStack prev(int current, int pages) {
        ItemStack icon = Text.icon(Material.SPECTRAL_ARROW, "<yellow>◀ Предыдущая страница",
                "<gray>Страница: <white>" + (current + 1) + " из " + pages,
                "<yellow>Нажмите, чтобы перейти на страницу " + current);
        icon.setAmount(Math.max(1, Math.min(64, current)));
        return icon;
    }

    static ItemStack next(int current, int pages) {
        ItemStack icon = Text.icon(Material.SPECTRAL_ARROW, "<yellow>Следующая страница ▶",
                "<gray>Страница: <white>" + (current + 1) + " из " + pages,
                "<yellow>Нажмите, чтобы перейти на страницу " + (current + 2));
        icon.setAmount(Math.max(1, Math.min(64, current + 2)));
        return icon;
    }

    static ItemStack home() {
        return Text.icon(Material.NETHER_STAR, "<gold>Главное меню", "<yellow>Нажмите, чтобы вернуться");
    }

    static ItemStack back(String where) {
        return where == null ? Text.icon(Material.ARROW, "<yellow>← Назад", "<yellow>Нажмите, чтобы вернуться")
                : Text.icon(Material.ARROW, "<yellow>← Назад", "<gray>Куда: <white>" + where, "<yellow>Нажмите, чтобы вернуться");
    }

    /** Цвет текста для цветной панели-заголовка группы. */
    private static String paneColor(Material pane) {
        return switch (pane) {
            case LIME_STAINED_GLASS_PANE -> "green";
            case YELLOW_STAINED_GLASS_PANE -> "yellow";
            case CYAN_STAINED_GLASS_PANE, LIGHT_BLUE_STAINED_GLASS_PANE, BLUE_STAINED_GLASS_PANE -> "aqua";
            case RED_STAINED_GLASS_PANE -> "red";
            case ORANGE_STAINED_GLASS_PANE -> "gold";
            case PURPLE_STAINED_GLASS_PANE, MAGENTA_STAINED_GLASS_PANE -> "light_purple";
            default -> "white";
        };
    }

    /** Панель-заголовок группы кнопок: цветное стекло с названием заглавными буквами. */
    static ItemStack section(String name, Material pane, String... lines) {
        String color = paneColor(pane);
        return Text.icon(pane, "<" + color + "><bold>" + name.toUpperCase(java.util.Locale.ROOT) + "</bold>", lines);
    }

    static ItemStack close() {
        return Text.icon(Material.BARRIER, "<red>Закрыть");
    }

    /** Индикатор состояния: «● » нужного цвета. */
    static String dot(String color) {
        return "<" + color + ">● ";
    }

    /** Тихий щелчок при нажатии на кнопку (ошибки звука не должны ломать меню). */
    static void clickSound(Player player) {
        try {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.3f);
        } catch (RuntimeException ignored) {
            // тестовый сервер без звуков
        }
    }
}
