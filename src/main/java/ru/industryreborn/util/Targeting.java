package ru.industryreborn.util;

import java.util.function.Function;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Блок, на который смотрит игрок. Отдельный слой: MockBukkit не умеет трассировку, в тестах он подменяется. */
public final class Targeting {

    private static final Function<Player, Block> DEFAULT = p -> p.getTargetBlockExact(6);
    private static volatile Function<Player, Block> finder = DEFAULT;

    private Targeting() { }

    public static Block of(Player player) {
        return finder.apply(player);
    }

    /** Только для тестов. */
    public static void set(Function<Player, Block> custom) {
        finder = custom == null ? DEFAULT : custom;
    }
}
