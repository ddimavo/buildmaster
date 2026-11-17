package ru.industryreborn.service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import ru.industryreborn.IndustryRebornPlugin;
import ru.industryreborn.model.Ledger;

/** Книги учёта блоков всех участков: ленивая загрузка, периодический сброс на диск. */
public final class LedgerStore {

    private final IndustryRebornPlugin plugin;
    private final File dir;
    private final Map<String, Ledger> ledgers = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();

    public LedgerStore(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "ledgers");
    }

    private static String key(String plotId) {
        return plotId.toLowerCase(Locale.ROOT);
    }

    private File file(String plotId) {
        return new File(dir, key(plotId) + ".bin");
    }

    public Ledger get(String plotId) {
        return ledgers.computeIfAbsent(key(plotId), k -> {
            File f = file(plotId);
            if (f.exists()) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                    return Ledger.read(in);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "Не удалось прочитать книгу учёта " + plotId, e);
                }
            }
            return new Ledger();
        });
    }

    public void markDirty(String plotId) {
        dirty.add(key(plotId));
    }

    public void flush() {
        for (String k : new HashSet<>(dirty)) {
            Ledger ledger = ledgers.get(k);
            if (ledger == null) {
                continue;
            }
            dir.mkdirs();
            File tmp = new File(dir, k + ".bin.tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                ledger.write(out);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить книгу учёта " + k, e);
                continue;
            }
            try {
                java.nio.file.Files.move(tmp.toPath(), new File(dir, k + ".bin").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                dirty.remove(k);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить книгу учёта " + k, e);
            }
        }
    }

    public void delete(String plotId) {
        ledgers.remove(key(plotId));
        dirty.remove(key(plotId));
        file(plotId).delete();
    }
}
