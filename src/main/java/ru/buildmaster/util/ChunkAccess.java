package ru.buildmaster.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;

/** Доступ к блокам с данными и сущностям чанка. Отдельный слой, чтобы подменять его в тестах. */
public final class ChunkAccess {

    public interface Source {
        List<BlockState> tileEntities(Chunk chunk);

        Collection<Entity> entities(Chunk chunk);
    }

    public static final Source PAPER = new Source() {
        @Override
        public List<BlockState> tileEntities(Chunk chunk) {
            return new ArrayList<>(Arrays.asList(chunk.getTileEntities(false)));
        }

        @Override
        public Collection<Entity> entities(Chunk chunk) {
            return Arrays.asList(chunk.getEntities());
        }
    };

    private static volatile Source source = PAPER;

    private ChunkAccess() { }

    /** Только для тестов. */
    public static void setSource(Source newSource) {
        source = newSource == null ? PAPER : newSource;
    }

    public static List<BlockState> tileEntities(Chunk chunk) {
        return source.tileEntities(chunk);
    }

    public static Collection<Entity> entities(Chunk chunk) {
        return source.entities(chunk);
    }
}
