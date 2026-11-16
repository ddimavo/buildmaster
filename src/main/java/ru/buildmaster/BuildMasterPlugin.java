package ru.buildmaster;

import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;
import ru.buildmaster.command.DistrictCommand;
import ru.buildmaster.gui.ChatPrompt;
import ru.buildmaster.gui.MenuListener;
import ru.buildmaster.listener.JoinListener;
import ru.buildmaster.listener.ProtectionListener;
import ru.buildmaster.listener.SelectionListener;
import ru.buildmaster.service.BlockRules;
import ru.buildmaster.service.BuildService;
import ru.buildmaster.service.EconomyHook;
import ru.buildmaster.service.PlotManager;
import ru.buildmaster.service.HologramService;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.LedgerStore;
import ru.buildmaster.service.ModerationService;
import ru.buildmaster.service.PlotService;
import ru.buildmaster.service.PowerService;
import ru.buildmaster.service.StationService;
import ru.buildmaster.service.WarningLog;
import ru.buildmaster.service.SnapshotStore;
import ru.buildmaster.service.StorageService;
import ru.buildmaster.service.Treasury;

public class BuildMasterPlugin extends JavaPlugin {

    private PlotManager plots;
    private PlotService service;
    private BuildService build;
    private BlockRules rules;
    private EconomyHook economy;
    private StorageService storage;
    private SnapshotStore snapshots;
    private LedgerStore ledgers;
    private Journal journal;
    private Treasury treasury;
    private ModerationService moderation;
    private HologramService holograms;
    private StationService stations;
    private PowerService power;
    private WarningLog warnings;
    private ChatPrompt chatPrompt;
    private SelectionListener selection;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rules = new BlockRules();
        rules.reload(getConfig());
        economy = new EconomyHook();
        storage = new StorageService(this);
        snapshots = new SnapshotStore();
        ledgers = new LedgerStore(this);
        journal = new Journal(new java.io.File(getDataFolder(), "journal.log"));
        try {
            journal.load();
        } catch (java.io.IOException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Не удалось прочитать журнал", e);
        }
        treasury = Treasury.load(new java.io.File(getDataFolder(), "treasury.yml"));
        moderation = new ModerationService(this);
        moderation.load();
        holograms = new HologramService(this);
        stations = new StationService(this);
        power = new PowerService(this);
        power.load();
        warnings = new WarningLog(new java.io.File(getDataFolder(), "warnings"));
        plots = new PlotManager(this);
        plots.load();
        service = new PlotService(this);
        build = new BuildService(this);
        chatPrompt = new ChatPrompt(this);
        selection = new SelectionListener(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new ProtectionListener(this), this);
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(chatPrompt, this);
        pm.registerEvents(selection, this);
        pm.registerEvents(new JoinListener(this), this);
        pm.registerEvents(new ru.buildmaster.listener.HologramListener(this), this);
        pm.registerEvents(new ru.buildmaster.listener.StationListener(this), this);

        DistrictCommand command = new DistrictCommand(this);
        var district = Objects.requireNonNull(getCommand("district"));
        district.setExecutor(command);
        district.setTabCompleter(command);

        // проверка сроков аренды, автопродление и продолжение незавершённых сбросов зданий
        getServer().getScheduler().runTaskTimer(this, service::tick, 100L, 400L);
        getServer().getScheduler().runTaskLater(this, () -> {
            stations.rescanAll();
            holograms.updateAll();
        }, 40L);
        getLogger().info("Загружено участков: " + plots.all().size());
    }

    @Override
    public void onDisable() {
        if (plots != null) {
            plots.save();
        }
        if (ledgers != null) {
            ledgers.flush();
        }
        if (holograms != null) {
            holograms.removeAll();
        }
        saveTreasury();
    }

    /** Только для тестов: подмена хранилища снимков (StructureManager нет в MockBukkit). */
    public void setSnapshots(SnapshotStore store) {
        this.snapshots = store;
    }

    public void reload() {
        reloadConfig();
        rules.reload(getConfig());
    }

    public PlotManager plots() {
        return plots;
    }

    public PlotService service() {
        return service;
    }

    public BuildService build() {
        return build;
    }

    public BlockRules rules() {
        return rules;
    }

    public EconomyHook economy() {
        return economy;
    }

    public StorageService storage() {
        return storage;
    }

    public SnapshotStore snapshots() {
        return snapshots;
    }

    public LedgerStore ledgers() {
        return ledgers;
    }

    public Journal journal() {
        return journal;
    }

    public Treasury treasury() {
        return treasury;
    }

    public ModerationService moderation() {
        return moderation;
    }

    public StationService stations() {
        return stations;
    }

    public PowerService power() {
        return power;
    }

    public WarningLog warnings() {
        return warnings;
    }

    public HologramService holograms() {
        return holograms;
    }

    public void saveTreasury() {
        if (treasury == null) {
            return;
        }
        try {
            treasury.save(new java.io.File(getDataFolder(), "treasury.yml"));
        } catch (java.io.IOException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Не удалось сохранить казну", e);
        }
    }

    public ChatPrompt chatPrompt() {
        return chatPrompt;
    }

    public SelectionListener selection() {
        return selection;
    }
}
