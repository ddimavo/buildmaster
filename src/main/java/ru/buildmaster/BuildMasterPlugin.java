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
import ru.buildmaster.service.PendingItems;
import ru.buildmaster.service.PlotManager;
import ru.buildmaster.service.PlotService;

public final class BuildMasterPlugin extends JavaPlugin {

    private PlotManager plots;
    private PlotService service;
    private BuildService build;
    private BlockRules rules;
    private EconomyHook economy;
    private PendingItems pending;
    private ChatPrompt chatPrompt;
    private SelectionListener selection;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rules = new BlockRules();
        rules.reload(getConfig());
        economy = new EconomyHook();
        pending = new PendingItems(this);
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

        DistrictCommand command = new DistrictCommand(this);
        var district = Objects.requireNonNull(getCommand("district"));
        district.setExecutor(command);
        district.setTabCompleter(command);

        // проверка сроков аренды, автопродление и продолжение незавершённых сбросов зданий
        getServer().getScheduler().runTaskTimer(this, service::tick, 100L, 400L);
        getLogger().info("Загружено участков: " + plots.all().size());
    }

    @Override
    public void onDisable() {
        if (plots != null) {
            plots.save();
        }
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

    public PendingItems pending() {
        return pending;
    }

    public ChatPrompt chatPrompt() {
        return chatPrompt;
    }

    public SelectionListener selection() {
        return selection;
    }
}
