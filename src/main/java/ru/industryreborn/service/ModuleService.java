package ru.industryreborn.service;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.util.Text;

/**
 * Включение и выключение функций плагина. Состояние берётся из config.yml (modules.*), а переключённое командой
 * /district module хранится в modules.yml и перекрывает конфиг. Отключённая функция недоступна всем, включая администрацию.
 */
public final class ModuleService {

    private final IndustryRebornPlugin plugin;
    private final Map<Module, Boolean> overrides = new EnumMap<>(Module.class);
    private final File file;

    public ModuleService(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "modules.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (Module m : Module.values()) {
            if (yml.contains(m.key())) {
                overrides.put(m, yml.getBoolean(m.key()));
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        overrides.forEach((m, v) -> yml.set(m.key(), v));
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить modules.yml: " + e.getMessage());
        }
    }

    /** Включена ли функция (с учётом зависимостей). */
    public boolean enabled(Module m) {
        if (m == Module.ENCHANTING && !enabled(Module.PRODUCTION)) {
            return false;
        }
        Boolean o = overrides.get(m);
        return o != null ? o : plugin.getConfig().getBoolean("modules." + m.key(), true);
    }

    /** Почему функция недоступна, либо null, если включена. */
    public String refusal(Module m) {
        if (enabled(m)) {
            return null;
        }
        if (m == Module.ENCHANTING && !enabled(Module.PRODUCTION)) {
            return "Функция «" + m.title() + "» недоступна: отключено производство.";
        }
        return "Функция «" + m.title() + "» отключена администрацией сервера.";
    }

    /** Сообщает игроку об отказе; true - функция включена. */
    public boolean check(Player player, Module m) {
        String r = refusal(m);
        if (r != null && player != null) {
            Text.err(player, r);
        }
        return r == null;
    }

    /** Переключает функцию. Возвращает ошибку или null. */
    public String set(Module m, boolean on, String actor) {
        if (enabled(m) == on && overrides.containsKey(m)) {
            return null;
        }
        boolean before = enabled(m);
        overrides.put(m, on);
        save();
        if (before == on) {
            return null;
        }
        onChanged(m, on, actor);
        return null;
    }

    private void onChanged(Module m, boolean on, String actor) {
        plugin.journal().add("-", null, actor, Journal.Action.MODULE, m.title() + ": " + (on ? "включено" : "отключено"));
        String msg = "<yellow>Функция «<white>" + Text.esc(m.title()) + "</white>» " + (on ? "<green>включена" : "<red>отключена")
                + "<yellow> (" + Text.esc(actor) + ").";
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("industryreborn.admin")) {
                Text.send(p, msg);
            }
        }
        plugin.getLogger().info("Функция " + m.key() + (on ? " включена" : " отключена") + " (" + actor + ")");
        // тарифы и голограммы зависят от функций: пересчитываем
        if (m == Module.ELECTRICITY || m == Module.HOTELS) {
            for (ru.industryreborn.model.Plot plot : new java.util.ArrayList<>(plugin.plots().all())) {
                plugin.power().recalc(plot);
            }
        }
        plugin.holograms().updateAll();
    }

    /** Сброс к значению из config.yml. */
    public void reset(Module m) {
        overrides.remove(m);
        save();
    }

    public Map<Module, Boolean> states() {
        Map<Module, Boolean> result = new EnumMap<>(Module.class);
        for (Module m : Module.values()) {
            result.put(m, enabled(m));
        }
        return result;
    }
}
