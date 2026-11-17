package ru.industryreborn.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import ru.industryreborn.model.Box;

/**
 * Обрабатывает область по одному чанку за тик: чанки подгружаются асинхронно,
 * сами блоки меняются в основном потоке, поэтому сервер не подвисает на больших зданиях.
 */
public final class ChunkJob {

    @FunctionalInterface
    public interface ChunkTask {
        /** @param clip часть области, лежащая в этом чанке */
        void run(Chunk chunk, Box clip);
    }

    /** Способ загрузки чанка. По умолчанию - асинхронный (Paper); в тестах подменяется синхронным. */
    @FunctionalInterface
    public interface Loader {
        void load(World world, int cx, int cz, java.util.function.BiConsumer<Chunk, Throwable> callback);
    }

    public static final Loader PAPER_ASYNC = (world, cx, cz, callback) ->
            world.getChunkAtAsync(cx, cz).whenComplete(callback);

    private static volatile Loader loader = PAPER_ASYNC;

    /** Только для тестов. */
    public static void setLoader(Loader newLoader) {
        loader = newLoader == null ? PAPER_ASYNC : newLoader;
    }

    private final Plugin plugin;
    private final World world;
    private final Box box;
    private final ChunkTask task;
    private final Runnable done;
    private final Consumer<Throwable> failed;
    private final List<int[]> chunks = new ArrayList<>();
    private int next;

    private ChunkJob(Plugin plugin, World world, Box box, ChunkTask task, Runnable done, Consumer<Throwable> failed) {
        this.plugin = plugin;
        this.world = world;
        this.box = box;
        this.task = task;
        this.done = done;
        this.failed = failed;
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                chunks.add(new int[] {cx, cz});
            }
        }
    }

    public static void run(Plugin plugin, World world, Box box, ChunkTask task, Runnable done,
                           Consumer<Throwable> failed) {
        new ChunkJob(plugin, world, box, task, done, failed).step();
    }

    private void step() {
        if (next >= chunks.size()) {
            done.run();
            return;
        }
        int[] c = chunks.get(next++);
        try {
            loader.load(world, c[0], c[1], (chunk, error) -> {
                Runnable work = () -> process(c, chunk, error);
                if (Bukkit.isPrimaryThread()) {
                    work.run();
                } else {
                    Bukkit.getScheduler().runTask(plugin, work);
                }
            });
        } catch (Throwable t) {
            failed.accept(t);
        }
    }

    private void process(int[] c, Chunk chunk, Throwable error) {
        try {
            if (error != null || chunk == null) {
                throw new IllegalStateException("Не удалось загрузить чанк " + c[0] + "," + c[1], error);
            }
            Box chunkBox = new Box(c[0] << 4, box.minY(), c[1] << 4, (c[0] << 4) + 15, box.maxY(), (c[1] << 4) + 15);
            Box clip = box.intersection(chunkBox);
            if (clip != null) {
                task.run(chunk, clip);
            }
        } catch (Throwable t) {
            failed.accept(t);
            return;
        }
        // следующий чанк - в следующем тике, чтобы не нагружать сервер
        Bukkit.getScheduler().runTask(plugin, this::step);
    }
}
