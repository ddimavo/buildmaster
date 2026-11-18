package ru.industryreborn;

import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;
import ru.industryreborn.command.DistrictCommand;
import ru.industryreborn.gui.ChatPrompt;
import ru.industryreborn.gui.MenuListener;
import ru.industryreborn.listener.JoinListener;
import ru.industryreborn.listener.ProtectionListener;
import ru.industryreborn.listener.SelectionListener;
import ru.industryreborn.service.BlockRules;
import ru.industryreborn.service.BankService;
import ru.industryreborn.service.DistrictManager;
import ru.industryreborn.service.ProductionService;
import ru.industryreborn.service.RecipeBook;
import ru.industryreborn.service.GridService;
import ru.industryreborn.service.HotelService;
import ru.industryreborn.service.UpgradeCatalog;
import ru.industryreborn.service.EconomyHook;
import ru.industryreborn.service.PlotManager;
import ru.industryreborn.service.HologramService;
import ru.industryreborn.service.Journal;
import ru.industryreborn.service.LedgerStore;
import ru.industryreborn.service.OrderService;
import ru.industryreborn.service.PlayerPlotService;
import ru.industryreborn.service.ServiceCatalog;
import ru.industryreborn.service.ModerationService;
import ru.industryreborn.service.PlotService;
import ru.industryreborn.service.RegionAccess;
import ru.industryreborn.service.WorldGuardRegions;
import ru.industryreborn.service.PowerService;
import ru.industryreborn.service.StationService;
import ru.industryreborn.service.WarningLog;
import ru.industryreborn.service.SnapshotStore;
import ru.industryreborn.service.StorageService;
import ru.industryreborn.service.Treasury;

public class IndustryRebornPlugin extends JavaPlugin {

    private PlotManager plots;
    private PlotService service;
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
    private ru.industryreborn.service.JobService jobs;
    private ru.industryreborn.auction.TreasuryAuction treasuryAuction;
    private ru.industryreborn.service.ArrestService arrests;
    private ru.industryreborn.service.MachineHolograms machineHolograms;
    private ru.industryreborn.service.RoomAutoService roomAuto;
    private ru.industryreborn.service.DoorHolograms doorHolograms;
    private ru.industryreborn.service.GoodsService goods;
    private ru.industryreborn.service.EnchantService enchants;
    private ru.industryreborn.service.TreasuryPayouts payouts;
    private ru.industryreborn.html.HtmlMenus htmlMenus;
    private ru.industryreborn.holo.HoloFactory holoFactory;
    private ru.industryreborn.service.ModuleService modules;
    private ru.industryreborn.service.BalanceService balance;
    private ru.industryreborn.service.StatsService stats;
    private ru.industryreborn.webhook.WebhookService webhooks;
    private ru.industryreborn.plan.PlanHook planHook;
    private ru.industryreborn.holo.ClickZones clickZones;
    private ru.industryreborn.building.BuildingService buildings;
    private ru.industryreborn.holoshop.HoloShopService holoShops;
    private ru.industryreborn.lot.LotService lots;
    private ru.industryreborn.service.LinkService links;
    private ru.industryreborn.service.ServicesHolograms servicesHolograms;
    private ru.industryreborn.service.BankHolograms bankHolograms;
    private ru.industryreborn.service.SelectionVisualizer visualizer;
    private ru.industryreborn.service.ManagerPanels managerPanels;
    private ChatPrompt chatPrompt;
    private SelectionListener selection;

    /**
     * Плагин раньше назывался BuildMaster. Если есть его папка с данными, а новая ещё пуста, данные копируются
     * (старая папка остаётся нетронутой как резервная копия).
     */
    private void migrateLegacyData() {
        java.io.File legacy = new java.io.File(getDataFolder().getParentFile(), "BuildMaster");
        java.io.File current = getDataFolder();
        if (!legacy.isDirectory() || new java.io.File(current, "config.yml").exists() || new java.io.File(current, "plots.yml").exists()) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(legacy.toPath())) {
            for (java.nio.file.Path src : (Iterable<java.nio.file.Path>) walk::iterator) {
                java.nio.file.Path dst = current.toPath().resolve(legacy.toPath().relativize(src).toString());
                if (java.nio.file.Files.isDirectory(src)) {
                    java.nio.file.Files.createDirectories(dst);
                } else {
                    java.nio.file.Files.createDirectories(dst.getParent());
                    java.nio.file.Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            getLogger().info("Данные прежнего плагина BuildMaster скопированы в папку " + current.getName()
                    + ". Папка BuildMaster осталась как резервная копия.");
        } catch (java.io.IOException e) {
            getLogger().log(java.util.logging.Level.WARNING, "Не удалось скопировать данные из папки BuildMaster", e);
        }
    }

    @Override
    public void onEnable() {
        migrateLegacyData();
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
        modules = new ru.industryreborn.service.ModuleService(this);
        balance = new ru.industryreborn.service.BalanceService(this);
        stats = new ru.industryreborn.service.StatsService(this);
        webhooks = new ru.industryreborn.webhook.WebhookService(this);
        planHook = new ru.industryreborn.plan.PlanHook(this);
        clickZones = new ru.industryreborn.holo.ClickZones(this);
        buildings = new ru.industryreborn.building.BuildingService(this);
        buildings.load();
        holoShops = new ru.industryreborn.holoshop.HoloShopService(this);
        lots = new ru.industryreborn.lot.LotService(this);
        holoFactory = new ru.industryreborn.holo.HoloFactory(this);
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
        jobs = new ru.industryreborn.service.JobService(this);
        treasuryAuction = new ru.industryreborn.auction.TreasuryAuction(this);
        arrests = new ru.industryreborn.service.ArrestService(this);
        machineHolograms = new ru.industryreborn.service.MachineHolograms(this);
        roomAuto = new ru.industryreborn.service.RoomAutoService(this);
        doorHolograms = new ru.industryreborn.service.DoorHolograms(this);
        goods = new ru.industryreborn.service.GoodsService(this);
        enchants = new ru.industryreborn.service.EnchantService(this);
        payouts = new ru.industryreborn.service.TreasuryPayouts(this);
        htmlMenus = new ru.industryreborn.html.HtmlMenus(this);
        links = new ru.industryreborn.service.LinkService(this);
        servicesHolograms = new ru.industryreborn.service.ServicesHolograms(this);
        bankHolograms = new ru.industryreborn.service.BankHolograms(this);
        visualizer = new ru.industryreborn.service.SelectionVisualizer(this);
        managerPanels = new ru.industryreborn.service.ManagerPanels(this);
        jobs.load();
        plots = new PlotManager(this);
        plots.load();
        service = new PlotService(this);
        factory.migrate();
        chatPrompt = new ChatPrompt(this);
        selection = new SelectionListener(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new ProtectionListener(this), this);
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(chatPrompt, this);
        pm.registerEvents(selection, this);
        pm.registerEvents(new JoinListener(this), this);
        pm.registerEvents(new ru.industryreborn.listener.HologramListener(this), this);
        pm.registerEvents(new ru.industryreborn.listener.StationListener(this), this);

        new ru.industryreborn.service.ChestShopHook(this).register();
        if (getServer().getPluginManager().getPlugin("zAuctionHouse") != null) {
            try {
                ru.industryreborn.auction.ZahBridge bridge = new ru.industryreborn.auction.ZahBridge(this);
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
        getServer().getScheduler().runTaskTimer(this, servicesHolograms::tick, 120L, 40L);
        getServer().getScheduler().runTaskTimer(this, bankHolograms::tick, 140L, 40L);
        getServer().getPluginManager().registerEvents(htmlMenus, this);
        getServer().getScheduler().runTaskLater(this, htmlMenus::cleanUp, 100L);
        getServer().getScheduler().runTaskTimer(this, htmlMenus::tick, 200L, 60L);
        getServer().getScheduler().runTaskTimer(this, balance::minuteTick, 1200L, 1200L);
        getServer().getScheduler().runTaskTimer(this, webhooks::minuteTick, 1300L, 1200L);
        getServer().getPluginManager().registerEvents(clickZones, this);
        getServer().getScheduler().runTaskLater(this, clickZones::cleanLeftovers, 60L);
        getServer().getScheduler().runTaskTimer(this, holoShops::tick, 80L, 20L);
        getServer().getScheduler().runTaskTimer(this, lots::tick, 100L, 20L);
        getServer().getScheduler().runTaskTimer(this, planHook::tick, 100L, 100L);
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
        if (lots != null) {
            lots.removeAll();
            lots.save();
        }
        if (holoShops != null) {
            holoShops.removeAll();
        }
        if (clickZones != null) {
            clickZones.removeAll();
        }
        if (htmlMenus != null) {
            htmlMenus.cleanUp();
        }
        if (bankHolograms != null) {
            bankHolograms.removeAll();
        }
        if (servicesHolograms != null) {
            servicesHolograms.removeAll();
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

    public ru.industryreborn.service.StatsService stats() {
        return stats;
    }

    public ru.industryreborn.building.BuildingService buildings() {
        return buildings;
    }

    public ru.industryreborn.holo.ClickZones clickZones() {
        return clickZones;
    }

    public ru.industryreborn.lot.LotService lots() {
        return lots;
    }

    public ru.industryreborn.holoshop.HoloShopService holoShops() {
        return holoShops;
    }

    public ru.industryreborn.plan.PlanHook planHook() {
        return planHook;
    }

    public ru.industryreborn.webhook.WebhookService webhooks() {
        return webhooks;
    }

    public ru.industryreborn.service.BalanceService balance() {
        return balance;
    }

    public ru.industryreborn.service.ModuleService modules() {
        return modules;
    }

    public ru.industryreborn.holo.HoloFactory holoFactory() {
        return holoFactory;
    }

    public ru.industryreborn.html.HtmlMenus htmlMenus() {
        return htmlMenus;
    }

    public ru.industryreborn.service.TreasuryPayouts payouts() {
        return payouts;
    }

    public ru.industryreborn.service.EnchantService enchants() {
        return enchants;
    }

    public ru.industryreborn.service.GoodsService goods() {
        return goods;
    }

    public ru.industryreborn.service.LinkService links() {
        return links;
    }

    public ru.industryreborn.service.BankHolograms bankHolograms() {
        return bankHolograms;
    }

    public ru.industryreborn.service.ServicesHolograms servicesHolograms() {
        return servicesHolograms;
    }

    public ru.industryreborn.service.DoorHolograms doorHolograms() {
        return doorHolograms;
    }

    public ru.industryreborn.service.RoomAutoService roomAuto() {
        return roomAuto;
    }

    public ru.industryreborn.service.SelectionVisualizer visualizer() {
        return visualizer;
    }

    public ru.industryreborn.service.MachineHolograms machineHolograms() {
        return machineHolograms;
    }

    public ru.industryreborn.service.ManagerPanels managerPanels() {
        return managerPanels;
    }

    public ru.industryreborn.service.ArrestService arrests() {
        return arrests;
    }

    public ru.industryreborn.auction.TreasuryAuction treasuryAuction() {
        return treasuryAuction;
    }

    public ru.industryreborn.service.JobService jobs() {
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
