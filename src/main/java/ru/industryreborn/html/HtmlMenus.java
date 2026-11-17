package ru.industryreborn.html;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.industryreborn.IndustryRebornPlugin;

/**
 * HTML-меню поверх плагина MC GUI. Страницы создаются под игрока при открытии (в папке plugins/MCGUI/pages/industryreborn),
 * пока страница открыта, они обновляются; MC GUI перечитывает изменённый файл за секунду. Без MC GUI, при выключенном режиме
 * или у игрока, выбравшего меню-сундук, остаются обычные меню.
 */
public final class HtmlMenus implements Listener {

    public enum Kind {
        MAIN("main"), BANK("bank"), TREASURY("treasury"), FREE("free"), MINE("mine"), STORAGE("storage"), JOURNAL("journal"),
        WARNINGS("warnings"), BUSINESS("business"), HOTEL("hotel"), JOBS("jobs"), ORDERS("orders"), ADMIN("admin"), MODERATION("moderation"), ARRESTS("arrests"), GRID("grid"), BANKSET("bankset");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    public enum Mode {
        AUTO, HTML, CHEST;

        public static Mode parse(String s) {
            if (s == null) {
                return null;
            }
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private static final String DIR = "industryreborn";

    private final IndustryRebornPlugin plugin;
    private final HtmlPages pages;
    private GuiBackend backend;
    private final Map<UUID, Kind> open = new HashMap<>();
    private final Map<UUID, Long> openedAt = new HashMap<>();
    private final Map<UUID, Map<String, String>> written = new HashMap<>();
    private final Map<UUID, Mode> prefs = new HashMap<>();
    private final File prefsFile;
    private final java.util.Set<UUID> seenWarnings = new java.util.HashSet<>();
    private long loadGraceMs = 15_000L;

    public HtmlMenus(IndustryRebornPlugin plugin) {
        this.plugin = plugin;
        this.pages = new HtmlPages(plugin);
        this.backend = new McGuiBackend(plugin.getLogger());
        this.prefsFile = new File(plugin.getDataFolder(), "ui-prefs.yml");
        loadPrefs();
    }

    /** Только для тестов: подмена плагина MC GUI. */
    public void setBackend(GuiBackend backend) {
        this.backend = backend;
    }

    /** Только для тестов: сколько ждать загрузки страницы, прежде чем считать её закрытой. */
    public void setLoadGraceMs(long ms) {
        this.loadGraceMs = ms;
    }

    public GuiBackend backend() {
        return backend;
    }

    // ------------------------------------------------------------ режимы

    public Mode serverMode() {
        Mode m = Mode.parse(plugin.getConfig().getString("gui.mode", "auto"));
        return m == null ? Mode.AUTO : m;
    }

    public Mode modeOf(Player player) {
        Mode own = prefs.get(player.getUniqueId());
        return own != null && own != Mode.AUTO ? own : serverMode();
    }

    public void setMode(Player player, Mode mode) {
        if (mode == null || mode == Mode.AUTO) {
            prefs.remove(player.getUniqueId());
        } else {
            prefs.put(player.getUniqueId(), mode);
        }
        savePrefs();
    }

    /** Откроется ли игроку HTML-страница: режим не «сундук» и MC GUI доступен. */
    public boolean enabledFor(Player player) {
        return modeOf(player) != Mode.CHEST && backend.available();
    }

    private void loadPrefs() {
        if (!prefsFile.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(prefsFile);
        for (String key : yml.getKeys(false)) {
            try {
                Mode m = Mode.parse(yml.getString(key));
                if (m != null) {
                    prefs.put(UUID.fromString(key), m);
                }
            } catch (IllegalArgumentException ignored) {
                // повреждённая запись
            }
        }
    }

    private void savePrefs() {
        YamlConfiguration yml = new YamlConfiguration();
        prefs.forEach((id, m) -> yml.set(id.toString(), m.name().toLowerCase(Locale.ROOT)));
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(prefsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить ui-prefs.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ страницы

    private static String suffix(Player player) {
        return player.getUniqueId().toString().replace("-", "");
    }

    private static String pageName(Kind kind, String suffix) {
        return DIR + "/" + kind.key() + "-" + suffix;
    }

    private String render(Kind kind, Player player, String suffix) {
        return switch (kind) {
            case MAIN -> pages.main(player, suffix);
            case BANK -> pages.bank(player, suffix);
            case TREASURY -> pages.treasury(player, suffix);
            case FREE -> pages.free(player, suffix);
            case MINE -> pages.mine(player, suffix);
            case STORAGE -> pages.storage(player, suffix);
            case JOURNAL -> pages.journal(player, suffix);
            case WARNINGS -> pages.warnings(player, suffix);
            case BUSINESS -> pages.business(player, suffix);
            case HOTEL -> pages.hotel(player, suffix);
            case JOBS -> pages.jobs(player, suffix);
            case ORDERS -> pages.orders(player, suffix);
            case ADMIN -> pages.admin(player, suffix);
            case MODERATION -> pages.moderation(player, suffix);
            case ARRESTS -> pages.arrests(player, suffix);
            case GRID -> pages.grid(player, suffix);
            case BANKSET -> pages.bankSettings(player, suffix);
        };
    }

    /** Записывает (при изменении) страницы игрока в папку MC GUI. */
    private boolean write(Player player) {
        return write(player, null);
    }

    /** only - обновить одну страницу (иначе все): открытая страница обновляется часто, остальные при открытии. */
    private boolean write(Player player, Kind only) {
        File root = backend.pagesDir();
        if (root == null) {
            return false;
        }
        File dir = new File(root, DIR);
        dir.mkdirs();
        String suffix = suffix(player);
        Map<String, String> state = written.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        for (Kind kind : Kind.values()) {
            if (only != null && kind != only) {
                continue;
            }
            String html = render(kind, player, suffix);
            String name = kind.key() + "-" + suffix + ".html";
            if (html.equals(state.get(name))) {
                continue;
            }
            try {
                File tmp = new File(dir, name + ".tmp");
                Files.writeString(tmp.toPath(), html, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), new File(dir, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                state.put(name, html);
            } catch (IOException e) {
                plugin.getLogger().warning("Не удалось записать страницу " + name + ": " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    /** Открывает HTML-страницу. false - откройте обычное меню. */
    public boolean open(Player player, Kind kind) {
        if (!enabledFor(player) || !write(player)) {
            return false;
        }
        String name = pageName(kind, suffix(player));
        if (!backend.open(player, name)) {
            return false;
        }
        open.put(player.getUniqueId(), kind);
        openedAt.put(player.getUniqueId(), System.currentTimeMillis());
        return true;
    }

    /** Раз в несколько секунд обновляет открытые страницы; закрытые убирает. */
    public void tick() {
        for (UUID id : new java.util.ArrayList<>(open.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                forget(id);
                continue;
            }
            String current = backend.pageOf(player);
            boolean loading = System.currentTimeMillis() - openedAt.getOrDefault(id, 0L) < loadGraceMs;   // страница грузится в фоне
            if ((current == null && !loading) || (current != null && !current.startsWith(DIR + "/"))) {
                forget(id);
                continue;
            }
            Kind kind = kindOf(current);
            if (kind == Kind.WARNINGS && !written.isEmpty()) {
                // страницу уведомлений игрок видит: новые подсвечены при этом показе, затем считаются прочитанными
                if (seenWarnings.add(id)) {
                    continue;
                }
                plugin.warnings().markAllRead(id);
            } else {
                seenWarnings.remove(id);
            }
            write(player, kind);
        }
    }

    private static Kind kindOf(String page) {
        if (page == null) {
            return null;
        }
        String file = page.substring(page.lastIndexOf('/') + 1);
        for (Kind k : Kind.values()) {
            if (file.startsWith(k.key() + "-")) {
                return k;
            }
        }
        return null;
    }

    private void forget(UUID id) {
        open.remove(id);
        seenWarnings.remove(id);
        openedAt.remove(id);
        written.remove(id);
        File root = backend.pagesDir();
        if (root == null) {
            return;
        }
        String suffix = id.toString().replace("-", "");
        for (Kind kind : Kind.values()) {
            new File(new File(root, DIR), kind.key() + "-" + suffix + ".html").delete();
        }
    }

    public int openCount() {
        return open.size();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (open.containsKey(id)) {
            forget(id);
        }
    }

    /** При включении и выключении плагина убирает созданные страницы. */
    public void cleanUp() {
        File root = backend.pagesDir();
        open.clear();
        openedAt.clear();
        written.clear();
        if (root == null) {
            return;
        }
        File[] files = new File(root, DIR).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".html") || f.getName().endsWith(".tmp")) {
                    f.delete();
                }
            }
        }
    }
}
