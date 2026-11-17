package ru.buildmaster.html;

import java.io.File;
import org.bukkit.entity.Player;

/** HTML-экраны игры: страницы открывает плагин MC GUI. Интерфейс нужен, чтобы тесты подменяли плагин заглушкой. */
public interface GuiBackend {

    /** Плагин установлен, включён и умеет открывать страницы. */
    boolean available();

    /** Папка, откуда плагин берёт страницы (plugins/MCGUI/pages). */
    File pagesDir();

    boolean exists(String page);

    /** Открывает страницу на весь экран. false - такой страницы нет. */
    boolean open(Player player, String page);

    void close(Player player);

    /** Страница, открытая у игрока, либо null. */
    String pageOf(Player player);
}
