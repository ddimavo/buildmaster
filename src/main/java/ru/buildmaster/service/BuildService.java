package ru.buildmaster.service;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import ru.buildmaster.BuildMasterPlugin;
import ru.buildmaster.model.Box;
import ru.buildmaster.model.Plot;
import ru.buildmaster.util.ChunkJob;
import ru.buildmaster.util.Text;

/** Видоизменение здания: подсчёт блоков и массовая замена материала. */
public final class BuildService {

    private final BuildMasterPlugin plugin;

    public BuildService(BuildMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** Подсчитывает материалы в области (для меню выбора заменяемого блока). */
    public void scan(Plot plot, Box box, Consumer<Map<Material, Integer>> done, Consumer<Throwable> failed) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            failed.accept(new IllegalStateException("Мир не загружен"));
            return;
        }
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        ChunkJob.run(plugin, world, box, (chunk, clip) -> {
            for (int y = clip.minY(); y <= clip.maxY(); y++) {
                for (int z = clip.minZ(); z <= clip.maxZ(); z++) {
                    for (int x = clip.minX(); x <= clip.maxX(); x++) {
                        Material type = world.getType(x, y, z);
                        if (!type.isAir()) {
                            counts.merge(type, 1, Integer::sum);
                        }
                    }
                }
            }
        }, () -> {
            // по убыванию количества, без блоков с данными
            Map<Material, Integer> sorted = new LinkedHashMap<>();
            counts.entrySet().stream()
                    .filter(e -> !BlockRules.isTileEntity(e.getKey()))
                    .sorted(Map.Entry.<Material, Integer>comparingByValue().reversed())
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            done.accept(sorted);
        }, failed);
    }

    /**
     * Заменяет все блоки материала {@code from} на {@code to} в области; ориентация
     * (направление ступеней, верхняя/нижняя половина плиты и т.п.) сохраняется.
     */
    public void replace(Player player, Plot plot, Box box, Material from, Material to) {
        if (plugin.plots().get(plot.id) != plot || !plot.isOwner(player.getUniqueId())) {
            Text.err(player, "Менять здание может только владелец.");
            return;
        }
        if (plot.isPlayerPlot()) {
            Text.err(player, "Здание участка игрока менять нельзя: оно принадлежит арендодателю.");
            return;
        }
        if (plugin.service().isLocked(plot)) {
            Text.err(player, "Участок сейчас обрабатывается, попробуйте позже.");
            return;
        }
        if (plot.isFrozen()) {
            Text.err(player, "Участок заморожен до погашения платежа: изменять здание нельзя.");
            return;
        }
        if (!plugin.rules().canReplaceWith(to)) {
            Text.err(player, "Этим блоком заменять нельзя: <white>" + to.getKey().getKey() + "</white>.");
            return;
        }
        if (ru.buildmaster.model.Station.fromName(to.name()) != null
                || ru.buildmaster.model.Station.fromName(from.name()) != null) {
            Text.err(player, "Станки ставить и заменять через меню нельзя: размещайте их вручную (действуют лимиты типа здания).");
            return;
        }
        if (from.isAir() || from == to) {
            Text.err(player, "Нечего заменять.");
            return;
        }
        if (BlockRules.isTileEntity(from)) {
            Text.err(player, "Блоки с данными (сундуки, таблички, кровати...) заменять нельзя.");
            return;
        }
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            Text.err(player, "Мир участка не загружен.");
            return;
        }
        plot.busy = true;
        AtomicInteger count = new AtomicInteger();
        Map<BlockData, BlockData> cache = new HashMap<>();
        Text.send(player, "<yellow>Заменяю <white>" + from.getKey().getKey() + "</white> на <white>"
                + to.getKey().getKey() + "</white>...");
        ChunkJob.run(plugin, world, box, (chunk, clip) -> {
            for (int y = clip.minY(); y <= clip.maxY(); y++) {
                for (int z = clip.minZ(); z <= clip.maxZ(); z++) {
                    for (int x = clip.minX(); x <= clip.maxX(); x++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType() != from) {
                            continue;
                        }
                        BlockData converted = cache.computeIfAbsent(block.getBlockData(), d -> convert(d, to));
                        block.setBlockData(converted, false);
                        count.incrementAndGet();
                    }
                }
            }
        }, () -> {
            plot.busy = false;
            Text.ok(player, "Готово: заменено блоков - " + count.get() + ".");
        }, error -> {
            plot.busy = false;
            plugin.getLogger().log(Level.SEVERE, "Ошибка замены на участке " + plot.id, error);
            Text.err(player, "Ошибка при замене: " + error.getMessage());
        });
    }

    /** Переносит свойства (направление, половину, форму...) на новый материал, если он их поддерживает. */
    static BlockData convert(BlockData old, Material to) {
        BlockData def = to.createBlockData();
        String oldString = old.getAsString();
        int open = oldString.indexOf('[');
        if (open < 0) {
            return def;
        }
        String defString = def.getAsString();
        String base = to.getKey().toString();
        StringBuilder accepted = new StringBuilder();
        BlockData result = def;
        for (String prop : oldString.substring(open + 1, oldString.length() - 1).split(",")) {
            String key = prop.substring(0, prop.indexOf('=') + 1);
            if (!defString.contains("[" + key) && !defString.contains("," + key)) {
                continue;
            }
            String attempt = base + "[" + (accepted.length() == 0 ? "" : accepted + ",") + prop + "]";
            try {
                result = Bukkit.createBlockData(attempt);
                accepted.append(accepted.length() == 0 ? "" : ",").append(prop);
            } catch (IllegalArgumentException ignored) {
                // значение не подходит новому блоку - оставляем по умолчанию
            }
        }
        return result;
    }
}
