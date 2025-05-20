package me.test.project.pluginso1;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import me.test.project.pluginso1.commands.BattlegroundCommand;
import me.test.project.pluginso1.commands.BattlegroundTabCompleter;
import me.test.project.pluginso1.listeners.DeathListener;
import me.test.project.pluginso1.listeners.PlayerListener;
import me.test.project.pluginso1.listeners.TeleportListener;
import me.test.project.pluginso1.listeners.WorldListener;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin {
    private BattlegroundManager bgManager;
    private RegionContainer container;
    private RegionManager regions;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            setupWorldGuard();
            bgManager = new BattlegroundManager(this);
            // Register commands and listeners
            getCommand("bg").setExecutor(new BattlegroundCommand(bgManager));
            getCommand("bg").setTabCompleter(new BattlegroundTabCompleter());
            getServer().getPluginManager().registerEvents(new PlayerListener(bgManager), this);
            getServer().getPluginManager().registerEvents(new DeathListener(bgManager), this);// Get war location from
                                                                                              // config
            String worldName = getConfig().getString("war-location.world", "lobby");
            World world = getServer().getWorld(worldName);
            if (world == null) {
                getLogger().warning("World '" + worldName + "' not found in war-location config!");
                return;
            }

            Location warLocation = new Location(
                    world,
                    getConfig().getDouble("war-location.x", 0),
                    getConfig().getDouble("war-location.y", 100),
                    getConfig().getDouble("war-location.z", 0));

            // Register remaining listeners
            getServer().getPluginManager().registerEvents(new TeleportListener(bgManager, warLocation), this);
            getServer().getPluginManager().registerEvents(new WorldListener(bgManager), this);
            getLogger().info("Battleground plugin enabled!");

        } catch (Exception e) {
            getLogger().severe("Error enabling plugin: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void setupWorldGuard() {
        // Get the WorldGuard region container
        container = WorldGuard.getInstance().getPlatform().getRegionContainer();

        // Get or create region manager for newbox world
        World newboxWorld = getServer().getWorld("lobby");
        if (newboxWorld != null) {
            regions = container.get(BukkitAdapter.adapt(newboxWorld));
            if (regions != null) {
                // Create __global__ region if it doesn't exist
                if (regions.getRegion("__global__") == null) {
                    // Create using command since it's more reliable than API
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            "rg define -w lobby __global__ //-1000,-1000,-1000 1000,1000,1000");
                    getLogger().info("Created __global__ region in lobby world");
                }

                // Get __global__ region and set flags
                ProtectedRegion global = regions.getRegion("__global__");
                if (global != null) {
                    // Set default flags
                    global.setFlag(Flags.ENTRY, StateFlag.State.DENY);
                    getLogger().info("WorldGuard __global__ region configured for lobby world");
                }
            }
        } else {
            getLogger().warning("World 'lobby' not found! The plugin may not work correctly.");
        }
    }

    @Override
    public void onDisable() {
        if (bgManager != null) {
            bgManager.cleanup();
        }
        getLogger().info("Plugin đã tắt!");
    }
}