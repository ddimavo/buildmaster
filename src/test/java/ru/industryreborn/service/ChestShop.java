package ru.industryreborn.service;

import org.bukkit.plugin.java.JavaPlugin;

/** Заглушка плагина ChestShop для тестов: нужен только факт его наличия. */
public class ChestShop extends JavaPlugin {

    public ChestShop() {
        super();
    }

    public ChestShop(String name, String version) {
        super();
    }

    protected ChestShop(org.bukkit.plugin.java.JavaPluginLoader loader, org.bukkit.plugin.PluginDescriptionFile description,
                            java.io.File dataFolder, java.io.File file) {
        super(loader, description, dataFolder, file);
    }
}
