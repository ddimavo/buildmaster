package ru.industryreborn.service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import ru.industryreborn.model.Box;

/**
 * Реализация {@link RegionAccess} через рефлексию: плагин не зависит от WorldGuard при сборке и работает
 * с любой его версией 7.x. Все ошибки перехватываются и логируются, плагин при этом продолжает работать.
 */
public final class WorldGuardRegions implements RegionAccess {

    private final Logger log;

    public WorldGuardRegions(Logger log) {
        this.log = log;
    }

    @Override
    public boolean available() {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            return false;
        }
        try {
            Class.forName("com.sk89q.worldguard.WorldGuard");
            Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        return type.getMethod(name, params);
    }

    /** RegionManager мира или null. */
    private Object manager(World world) throws ReflectiveOperationException {
        Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
        Object wg = method(wgClass, "getInstance").invoke(null);
        Object platform = method(wgClass, "getPlatform").invoke(wg);
        Class<?> platformIface = Class.forName("com.sk89q.worldguard.internal.platform.WorldGuardPlatform");
        Object container = method(platformIface, "getRegionContainer").invoke(platform);
        Class<?> containerIface = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer");
        Class<?> weWorld = Class.forName("com.sk89q.worldedit.world.World");
        Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
        Object adapted = method(adapter, "adapt", World.class).invoke(null, world);
        return method(containerIface, "get", weWorld).invoke(container, adapted);
    }

    private static Class<?> regionClass() throws ClassNotFoundException {
        return Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion");
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> uuids(Object domain) throws ReflectiveOperationException {
        Class<?> domainClass = Class.forName("com.sk89q.worldguard.domains.DefaultDomain");
        return new HashSet<>((Set<UUID>) method(domainClass, "getUniqueIds").invoke(domain));
    }

    private static int coordinate(Object vector, String axis) throws ReflectiveOperationException {
        Class<?> v = Class.forName("com.sk89q.worldedit.math.BlockVector3");
        return (Integer) method(v, "getBlock" + axis).invoke(vector);
    }

    @Override
    public RegionInfo find(World world, String regionId) {
        try {
            Object manager = manager(world);
            if (manager == null) {
                return null;
            }
            Class<?> managerClass = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager");
            Object region = method(managerClass, "getRegion", String.class).invoke(manager, regionId.toLowerCase(Locale.ROOT));
            if (region == null) {
                return null;
            }
            Class<?> rc = regionClass();
            Object type = method(rc, "getType").invoke(region);
            boolean cuboid = type.toString().toUpperCase(Locale.ROOT).contains("CUBOID");
            Object min = method(rc, "getMinimumPoint").invoke(region);
            Object max = method(rc, "getMaximumPoint").invoke(region);
            Box box = new Box(coordinate(min, "X"), coordinate(min, "Y"), coordinate(min, "Z"),
                    coordinate(max, "X"), coordinate(max, "Y"), coordinate(max, "Z"));
            return new RegionInfo((String) method(rc, "getId").invoke(region), cuboid, box,
                    uuids(method(rc, "getOwners").invoke(region)), uuids(method(rc, "getMembers").invoke(region)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.log(Level.WARNING, "WorldGuard: не удалось прочитать регион " + regionId, e);
            return null;
        }
    }

    @Override
    public boolean setDomains(World world, String regionId, Set<UUID> owners, Set<UUID> members) {
        try {
            Object manager = manager(world);
            if (manager == null) {
                return false;
            }
            Class<?> managerClass = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager");
            Object region = method(managerClass, "getRegion", String.class).invoke(manager, regionId.toLowerCase(Locale.ROOT));
            if (region == null) {
                return false;
            }
            Class<?> rc = regionClass();
            Class<?> domainClass = Class.forName("com.sk89q.worldguard.domains.DefaultDomain");
            method(rc, "setOwners", domainClass).invoke(region, domain(domainClass, owners));
            method(rc, "setMembers", domainClass).invoke(region, domain(domainClass, members));
            try {
                method(managerClass, "saveChanges").invoke(manager);
            } catch (NoSuchMethodException e) {
                method(managerClass, "save").invoke(manager);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.log(Level.WARNING, "WorldGuard: не удалось изменить участников региона " + regionId, e);
            return false;
        }
    }

    private static Object domain(Class<?> domainClass, Set<UUID> players) throws ReflectiveOperationException {
        Object domain = domainClass.getConstructor().newInstance();
        Method add = method(domainClass, "addPlayer", UUID.class);
        for (UUID id : players) {
            add.invoke(domain, id);
        }
        return domain;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> regionsOwnedBy(World world, UUID player) {
        List<String> result = new ArrayList<>();
        try {
            Object manager = manager(world);
            if (manager == null) {
                return result;
            }
            Class<?> managerClass = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager");
            Map<String, Object> regions = (Map<String, Object>) method(managerClass, "getRegions").invoke(manager);
            Class<?> rc = regionClass();
            for (Map.Entry<String, Object> e : regions.entrySet()) {
                if (uuids(method(rc, "getOwners").invoke(e.getValue())).contains(player)) {
                    result.add(e.getKey());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.log(Level.FINE, "WorldGuard: не удалось получить список регионов", e);
        }
        return result;
    }
}
