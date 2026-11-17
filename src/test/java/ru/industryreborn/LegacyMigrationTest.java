package ru.industryreborn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Переименование BuildMaster в IndustryReborn: данные прежней папки копируются, права старых имён работают. */
class LegacyMigrationTest {

    @Test
    void dataOfTheOldPluginIsCopiedOnce() throws Exception {
        ServerMock server = MockBukkit.mock();
        try {
            server.addSimpleWorld("world");
            File legacy = new File(server.getPluginsFolder(), "BuildMaster");
            new File(legacy, "sub").mkdirs();
            Files.writeString(new File(legacy, "plots.yml").toPath(), "plots: {}\n");
            Files.writeString(new File(legacy, "sub/x.txt").toPath(), "x");
            IndustryRebornPlugin plugin = MockBukkit.load(IndustryRebornPlugin.class);
            assertTrue(new File(plugin.getDataFolder(), "plots.yml").exists(), "данные скопированы");
            assertEquals("x", Files.readString(new File(plugin.getDataFolder(), "sub/x.txt").toPath()));
            assertTrue(legacy.exists(), "старая папка осталась как копия");
            deleteTree(legacy);
        } finally {
            MockBukkit.unmock();
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        f.delete();
    }

    @Test
    void oldPermissionNamesGrantTheNewOnes() {
        ServerMock server = MockBukkit.mock();
        try {
            server.addSimpleWorld("world");
            IndustryRebornPlugin plugin = MockBukkit.load(IndustryRebornPlugin.class);
            var player = server.addPlayer("Old");
            player.addAttachment(plugin, "buildmaster.admin", true);
            assertTrue(player.hasPermission("industryreborn.admin"), "старое право включает новое");
            assertTrue(player.hasPermission("industryreborn.bank"), "и его дочерние");
            var other = server.addPlayer("New");
            assertFalse(other.hasPermission("industryreborn.admin"));
        } finally {
            MockBukkit.unmock();
        }
    }
}
