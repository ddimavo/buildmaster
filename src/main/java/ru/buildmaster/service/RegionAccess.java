package ru.buildmaster.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import ru.buildmaster.model.Box;

/** Доступ к регионам WorldGuard. Отдельный интерфейс: реализация через рефлексию, в тестах - подмена. */
public interface RegionAccess {

    /** Регион: прямоугольный ли он, его границы и участники. */
    record RegionInfo(String id, boolean cuboid, Box box, Set<UUID> owners, Set<UUID> members) { }

    /** WorldGuard установлен и доступен. */
    boolean available();

    /** Регион по идентификатору или null. */
    RegionInfo find(World world, String regionId);

    /** Заменяет владельцев и участников региона. false - не удалось. */
    boolean setDomains(World world, String regionId, Set<UUID> owners, Set<UUID> members);

    /** Идентификаторы регионов, которыми владеет игрок (для подсказок команд). */
    List<String> regionsOwnedBy(World world, UUID player);
}
