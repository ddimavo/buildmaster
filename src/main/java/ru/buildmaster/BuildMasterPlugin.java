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
import ru.buildmaster.service.BankService;
import ru.buildmaster.service.BuildService;
import ru.buildmaster.service.DistrictManager;
import ru.buildmaster.service.ProductionService;
import ru.buildmaster.service.RecipeBook;
import ru.buildmaster.service.GridService;
import ru.buildmaster.service.HotelService;
import ru.buildmaster.service.UpgradeCatalog;
import ru.buildmaster.service.EconomyHook;
import ru.buildmaster.service.PlotManager;
import ru.buildmaster.service.HologramService;
import ru.buildmaster.service.Journal;
import ru.buildmaster.service.LedgerStore;
import ru.buildmaster.service.OrderService;
import ru.buildmaster.service.PlayerPlotService;
import ru.buildmaster.service.ServiceCatalog;
import ru.buildmaster.service.ModerationService;
import ru.buildmaster.service.PlotService;
import ru.buildmaster.service.RegionAccess;
import ru.buildmaster.service.WorldGuardRegions;
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
    private BankService bank;
    private GridService grid;
    private DistrictManager districts;
    private ServiceCatalog catalog;
    private OrderService orders;
    private UpgradeCatalog upgrades;
    private RecipeBook recipes;
    private ProductionService factory;
    private HotelService hotels;
    private RegionAccess regions;
    private PlayerPlotService playerPlots;
    private ru.buildmaster.service.JobService jobs;
    private ru.buildmaster.auction.TreasuryAuction treasuryAuction;
    private ru.buildmaster.service.ArrestService arrests;
    private ru.buildmaster.service.MachineHolograms machineHolograms;
    private ru.buildmaster.service.RoomAutoService roomAuto;
    private ru.buildmaster.service.DoorHolograms doorHolograms;
    private ru.buildmaster.service.SelectionVisualizer visualizer;
    private ru.buildmaster.service.ManagerPanels managerPanels;
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
        bank = new BankService(this);
        bank.load();
        grid = new GridService(this);
        districts = new DistrictManager(new java.io.File(getDataFolder(), "districts.yml"));
        districts.load();
        catalog = new ServiceCatalog(this);
        orders = new OrderService(this);
        orders.load();
        upgrades = new UpgradeCatalog(this::getConfig);
        recipes = new RecipeBook(this);
        factory = new ProductionService(this);
        hotels = new HotelService(this);
        regions = new WorldGuardRegions(getLogger());
        playerPlots = new PlayerPlotService(this);
        jobs = new ru.buildmaster.service.JobService(this);
        treasuryAuction = new ru.buildmaster.auction.TreasuryAuction(this);
        arrests = new ru.buildmaster.service.ArrestService(this);
        machineHolograms = new ru.buildmaster.service.MachineHolograms(this);
        roomAuto = new ru.buildmaster.service.RoomAutoService(this);
        doorHolograms = new ru.buildmaster.service.DoorHolograms(this);
        visualizer = new ru.buildmaster.service.SelectionVisualizer(this);
        managerPanels = new ru.buildmaster.service.ManagerPanels(this);
        jobs.load();
        plots = new PlotManager(this);
        plots.load();
        service = new PlotService(this);
        build = new BuildService(this);
        factory.migrate();
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

        new ru.buildmaster.service.ChestShopHook(this).register();
        if (getServer().getPluginManager().getPlugin("zAuctionHouse") != null) {
            try {
                ru.buildmaster.auction.ZahBridge bridge = new ru.buildmaster.auction.ZahBridge(this);
                bridge.register();
                treasuryAuction.setPort(bridge);
                getLogger().info("zAuctionHouse найден: невостребованные вещи пойдут на общий аукцион от казны.");
            } catch (LinkageError e) {
                getLogger().warning("zAuctionHouse найден, но его API не подошёл: " + e);
            }
        }

        DistrictCommand command = new DistrictCommand(this);
        var district = Objects.requireNonNull(getCommand("district"));
        district.setExecutor(command);
        district.setTabCompleter(command);

        // проверка сроков аренды, автопродление и продолжение незавершённых сбросов зданий
        getServer().getScheduler().runTaskTimer(this, service::tick, 100L, 400L);
        getServer().getScheduler().runTaskTimer(this, jobs::minuteTick, 1200L, 1200L);
        getServer().getScheduler().runTaskTimer(this, factory::tick, 60L, 20L);
        getServer().getScheduler().runTaskTimer(this, machineHolograms::tick, 60L, 20L);
        getServer().getScheduler().runTaskTimer(this, managerPanels::tick, 80L, 40L);
        getServer().getScheduler().runTaskTimer(this, visualizer::tick, 40L, 10L);
        getServer().getScheduler().runTaskTimer(this, doorHolograms::tick, 100L, 40L);
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
        if (orders != null) {
            orders.save();
        }
        if (jobs != null) {
            jobs.save();
        }
        if (holograms != null) {
            holograms.removeAll();
        }
        if (machineHolograms != null) {
            machineHolograms.removeAll();
        }
        if (managerPanels != null) {
            managerPanels.removeAll();
        }
        if (doorHolograms != null) {
            doorHolograms.removeAll();
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

    public BankService bank() {
        return bank;
    }

    public GridService grid() {
        return grid;
    }

    public ServiceCatalog catalog() {
        return catalog;
    }

    public UpgradeCatalog upgrades() {
        return upgrades;
    }

    public RecipeBook recipes() {
        return recipes;
    }

    /** Доступ к регионам WorldGuard (по умолчанию через рефлексию). */
    public RegionAccess regions() {
        return regions;
    }

    /** Только для тестов: подмена доступа к WorldGuard. */
    public void setRegions(RegionAccess access) {
        this.regions = access;
    }

    public ru.buildmaster.service.DoorHolograms doorHolograms() {
        return doorHolograms;
    }

    public ru.buildmaster.service.RoomAutoService roomAuto() {
        return roomAuto;
    }

    public ru.buildmaster.service.SelectionVisualizer visualizer() {
        return visualizer;
    }

    public ru.buildmaster.service.MachineHolograms machineHolograms() {
        return machineHolograms;
    }

    public ru.buildmaster.service.ManagerPanels managerPanels() {
        return managerPanels;
    }

    public ru.buildmaster.service.ArrestService arrests() {
        return arrests;
    }

    public ru.buildmaster.auction.TreasuryAuction treasuryAuction() {
        return treasuryAuction;
    }

    public ru.buildmaster.service.JobService jobs() {
        return jobs;
    }

    public PlayerPlotService playerPlots() {
        return playerPlots;
    }

    public HotelService hotels() {
        return hotels;
    }

    public ProductionService factory() {
        return factory;
    }

    public OrderService orders() {
        return orders;
    }

    public DistrictManager districts() {
        return districts;
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
