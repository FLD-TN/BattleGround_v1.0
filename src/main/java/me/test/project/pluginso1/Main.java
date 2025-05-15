package me.test.project.pluginso1;

import me.test.project.pluginso1.commands.BattlegroundCommand;
import me.test.project.pluginso1.listeners.DeathListener;
import me.test.project.pluginso1.listeners.PlayerListener;
import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin {
    private BattlegroundManager bgManager;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            bgManager = new BattlegroundManager(this);
            getCommand("bg").setExecutor(new BattlegroundCommand(bgManager));
            getServer().getPluginManager().registerEvents(new PlayerListener(bgManager), this);
            getServer().getPluginManager().registerEvents(new DeathListener(bgManager), this);
            getLogger().info("Battleground plugin đã bật!");
        } catch (Exception e) {
            getLogger().severe("Lỗi khi khởi động plugin: " + e.getMessage());
            e.printStackTrace();
        }
    }    @Override
    public void onDisable() {
        if (bgManager != null) {
            bgManager.forceResetBorder(); // Reset border trước khi tắt plugin
            bgManager.stop();
        }
        getLogger().info("Battleground plugin đã tắt!");
    }
}