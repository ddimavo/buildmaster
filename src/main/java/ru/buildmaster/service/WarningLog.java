package ru.buildmaster.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/** Личный журнал предупреждений игрока (аренда, электричество, отказы). Отдельно от журнала решений администрации. */
public final class WarningLog {

    public static final class Warning {
        public final long time;
        public final String plotId;
        public final String text;
        public boolean read;

        Warning(long time, String plotId, String text, boolean read) {
            this.time = time;
            this.plotId = plotId;
            this.text = text;
            this.read = read;
        }
    }

    private static final int MAX_PER_PLAYER = 100;

    private final File dir;
    private final Map<UUID, List<Warning>> cache = new HashMap<>();

    public WarningLog(File dir) {
        this.dir = dir;
    }

    private File file(UUID uuid) {
        return new File(dir, uuid + ".yml");
    }

    private List<Warning> load(UUID uuid) {
        List<Warning> list = new ArrayList<>();
        File f = file(uuid);
        if (f.exists()) {
            for (String line : YamlConfiguration.loadConfiguration(f).getStringList("warnings")) {
                String[] p = line.split("\\|", 4);
                if (p.length == 4) {
                    list.add(new Warning(Long.parseLong(p[0]), p[1].replace("\\p", "|"), p[3].replace("\\p", "|"),
                            p[2].equals("1")));
                }
            }
        }
        return list;
    }

    private List<Warning> list(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::load);
    }

    private void save(UUID uuid) {
        YamlConfiguration yml = new YamlConfiguration();
        List<String> lines = new ArrayList<>();
        for (Warning w : list(uuid)) {
            lines.add(w.time + "|" + w.plotId.replace("|", "\\p") + "|" + (w.read ? "1" : "0") + "|"
                    + w.text.replace("|", "\\p"));
        }
        yml.set("warnings", lines);
        try {
            dir.mkdirs();
            yml.save(file(uuid));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить предупреждения", e);
        }
    }

    public void add(UUID uuid, String plotId, String text) {
        List<Warning> list = list(uuid);
        list.add(new Warning(System.currentTimeMillis(), plotId, text, false));
        while (list.size() > MAX_PER_PLAYER) {
            list.remove(0);
        }
        save(uuid);
    }

    /** Новые первыми. */
    public List<Warning> forPlayer(UUID uuid) {
        List<Warning> copy = new ArrayList<>(list(uuid));
        java.util.Collections.reverse(copy);
        return copy;
    }

    public int unread(UUID uuid) {
        int n = 0;
        for (Warning w : list(uuid)) {
            if (!w.read) {
                n++;
            }
        }
        return n;
    }

    public void markAllRead(UUID uuid) {
        boolean changed = false;
        for (Warning w : list(uuid)) {
            if (!w.read) {
                w.read = true;
                changed = true;
            }
        }
        if (changed) {
            save(uuid);
        }
    }
}
