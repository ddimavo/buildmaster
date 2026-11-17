package ru.buildmaster.html;

import java.io.File;
import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Связь с плагином MC GUI (https://github.com/resourcepack-ai/mcgui) через рефлексию: плагин необязательный,
 * при сборке на него ничего не ссылается. Используются только его публичные методы {@code ai.resourcepack.ui.MCGUI}.
 */
public final class McGuiBackend implements GuiBackend {

    private final Logger log;
    private Plugin plugin;
    private Method open;
    private Method close;
    private Method page;
    private Method exists;
    private boolean resolved;
    private boolean broken;

    public McGuiBackend(Logger log) {
        this.log = log;
    }

    private boolean resolve() {
        if (broken) {
            return false;
        }
        Plugin p = Bukkit.getPluginManager().getPlugin("MCGUI");
        if (p == null || !p.isEnabled()) {
            resolved = false;
            return false;
        }
        if (resolved && p == plugin) {
            return true;
        }
        try {
            Class<?> api = Class.forName("ai.resourcepack.ui.MCGUI", true, p.getClass().getClassLoader());
            open = api.getMethod("open", Player.class, String.class);
            close = api.getMethod("close", Player.class);
            page = api.getMethod("page", Player.class);
            exists = api.getMethod("exists", String.class);
            plugin = p;
            resolved = true;
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            broken = true;
            log.warning("MC GUI найден, но его API не подошёл (" + e + "): HTML-меню отключены.");
            return false;
        }
    }

    @Override
    public boolean available() {
        return resolve();
    }

    @Override
    public File pagesDir() {
        return resolve() ? new File(plugin.getDataFolder(), "pages") : null;
    }

    @Override
    public boolean exists(String name) {
        try {
            return resolve() && (boolean) exists.invoke(null, name);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @Override
    public boolean open(Player player, String name) {
        if (!resolve()) {
            return false;
        }
        try {
            return (boolean) open.invoke(null, player, name);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // например, рядом нет места для просмотра: игрок остаётся с обычным меню
            log.fine("MC GUI не открыл страницу " + name + ": " + e.getCause());
            return false;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @Override
    public void close(Player player) {
        try {
            if (resolve()) {
                close.invoke(null, player);
            }
        } catch (ReflectiveOperationException ignored) {
            // игрок и так без страницы
        }
    }

    @Override
    public String pageOf(Player player) {
        try {
            return resolve() ? (String) page.invoke(null, player) : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
