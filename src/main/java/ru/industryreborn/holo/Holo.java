package ru.industryreborn.holo;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Одна текстовая голограмма: либо сущность TextDisplay, либо голограмма плагина FancyHolograms. */
public interface Holo {

    /** Новый текст (MiniMessage, строки через перевод строки). */
    void text(String miniMessage);

    void teleport(Location at);

    /** Голограмма ещё существует (чанк не выгружен, плагин не перезагружался). */
    boolean valid();

    void remove();

    /** Показать конкретному игроку (для голограмм «только своим», HoloSpec.perViewer). */
    void show(Player player);

    void hide(Player player);
}
