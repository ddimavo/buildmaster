package ru.industryreborn.items;

import java.lang.reflect.Method;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Связь с Nexo через его API (классы com.nexomc.nexo.api.NexoItems: itemFromId(id).build(), idFromItem(stack), exists(id)).
 * Вызовы идут через отражение: Nexo необязателен и не нужен для сборки. Любая ошибка API отключает мост до перезапуска и пишется в лог один раз.
 */
public final class NexoBridge implements ExternalItems {

    private static final String API = "com.nexomc.nexo.api.NexoItems";

    private final Plugin plugin;
    private boolean broken;
    private Class<?> api;
    private Method idFromItem;
    private Method itemFromId;
    private Method exists;
    private Method build;

    public NexoBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    private boolean ready() {
        if (broken) {
            return false;
        }
        if (api != null) {
            return true;
        }
        Plugin nexo = Bukkit.getPluginManager().getPlugin("Nexo");
        if (nexo == null || !nexo.isEnabled()) {
            return false;
        }
        try {
            api = Class.forName(API, true, nexo.getClass().getClassLoader());
            idFromItem = api.getMethod("idFromItem", ItemStack.class);
            itemFromId = api.getMethod("itemFromId", String.class);
            exists = api.getMethod("exists", String.class);
            build = itemFromId.getReturnType().getMethod("build");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            broken = true;
            api = null;
            plugin.getLogger().log(Level.WARNING, "API Nexo не подошёл (ожидается " + API + "): предметы Nexo недоступны", e);
            return false;
        }
    }

    @Override
    public boolean available() {
        return ready();
    }

    @Override
    public String idOf(ItemStack stack) {
        if (stack == null || !ready()) {
            return null;
        }
        try {
            Object id = idFromItem.invoke(null, stack);
            return id == null ? null : id.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public ItemStack build(String id) {
        if (!ready()) {
            return null;
        }
        try {
            Object builder = itemFromId.invoke(null, id);
            return builder == null ? null : (ItemStack) build.invoke(builder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean exists(String id) {
        if (!ready()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(exists.invoke(null, id));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
