package me.test.project.pluginso1;

import org.bukkit.*;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.text.SimpleDateFormat;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.util.concurrent.TimeUnit;
import org.bson.Document;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;

public class BattlegroundManager {
    private final Main plugin;
    private final List<Player> participants = new ArrayList<>();
    private boolean isRunning = false;
    private long startTime = -1;
    private BukkitTask countdownTask;
    private BukkitTask startCheckTask;
    private WorldBorder border;
    private Location lobbyLocation;
    private long phaseStartTime;
    private Location mapCenter;
    private int matchDuration; // In seconds
    private int invincibilityDuration;
    private double borderInitialSize;
    private double borderFinalSize;
    private int minPlayers;
    private int numberOfPhases; // Configurable number of phases
    private double borderShrinkFactor; // Shrink factor per phase
    private double borderCenterOffset; // Random offset for border center at match start
    private int finalPhaseDuration; // Duration to hold final border size before global damage
    private final Map<Player, Integer> kills = new HashMap<>();
    private final Map<Player, Integer> totalKills = new HashMap<>();
    private int currentPhase = 0;
    private boolean isPaused = false;
    private BukkitTask borderTask;
    private long matchStartTime;
    private BossBar borderBar;
    private Scoreboard gameScoreboard;
    private Map<Player, Scoreboard> originalScoreboards = new HashMap<>();
    private File killDataFile;
    private BukkitTask autoSaveTask;
    private boolean isCountingDown = false;
    private final Map<Player, ItemStack[]> vanishedArmor = new HashMap<>();
    private boolean isJoinOpen = false;
    private boolean isBorderShrinking = false; // New flag to track border shrinking
    private long finalPhaseCountdown = -1;
    private Location waitingLobby;
    private String mongoConnectionString;
    private String mongoDatabase;
    private MongoClient mongoClient;
    private MongoCollection<Document> matchHistoryCollection;
    private MongoCollection<Document> playerKillsCollection;
    private String mongoMatchHistoryCollection;
    private String mongoKillsCollection;
    private final List<Player> deathOrder = new ArrayList<>();

    public BattlegroundManager(Main plugin) {
        this.plugin = plugin;
        this.currentPhase = 0;
        this.killDataFile = new File(plugin.getDataFolder(), "kills.yml");
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        loadConfig();
        initializeMongoDB();
        loadKillData();
        scheduleStartCheck();
        setupScoreboard();
        startAutoSave();
    }

    private void loadConfig() {
        try {
            lobbyLocation = parseConfigLocation("lobby-location");
            mapCenter = parseConfigLocation("map-center");
            waitingLobby = parseConfigLocation("waiting-lobby");
            matchDuration = plugin.getConfig().getInt("settings.match-duration", 300);
            invincibilityDuration = plugin.getConfig().getInt("settings.invincibility-duration", 60);
            borderInitialSize = plugin.getConfig().getDouble("settings.border-initial-size", 600.0);
            borderFinalSize = plugin.getConfig().getDouble("settings.border-final-size", 5.0);
            minPlayers = plugin.getConfig().getInt("settings.min-players", 2);
            numberOfPhases = plugin.getConfig().getInt("settings.number-of-phases", 6);
            borderShrinkFactor = plugin.getConfig().getDouble("settings.border-shrink-factor", 0.85);
            borderCenterOffset = plugin.getConfig().getDouble("settings.border-center-offset", 200.0);
            finalPhaseDuration = plugin.getConfig().getInt("settings.final-phase-duration", 120);

            // Load MongoDB config
            mongoConnectionString = plugin.getConfig().getString("mongodb.connection-string", "");
            mongoDatabase = plugin.getConfig().getString("mongodb.database", "BattleGroundTopKill");
            mongoKillsCollection = plugin.getConfig().getString("mongodb.kills-collection", "players_topkills");
            mongoMatchHistoryCollection = plugin.getConfig().getString("mongodb.match-history-collection",
                    "match_history");

            if (mongoConnectionString.isEmpty()) {
                plugin.getLogger()
                        .warning("MongoDB connection string is missing in config. Disabling MongoDB features.");
            } else {
                plugin.getLogger().info("MongoDB configured: database=" + mongoDatabase +
                        ", kills-collection=" + mongoKillsCollection +
                        ", match-history-collection=" + mongoMatchHistoryCollection);
            }

            // Validate waiting lobby
            World defaultWorld = mapCenter != null && mapCenter.getWorld() != null ? mapCenter.getWorld()
                    : Bukkit.getWorld("warp_wheat");
            if (waitingLobby == null || waitingLobby.getWorld() == null) {
                waitingLobby = new Location(defaultWorld != null ? defaultWorld : Bukkit.getWorlds().get(0),
                        365.565, 207, -40.461, 0, 0);
                plugin.getLogger()
                        .warning("Invalid waiting-lobby in config, defaulting to x=365.565, y=207, z=-40.461");
            }

            // Check if waitingLobby is within border
            if (mapCenter != null && waitingLobby != null) {
                double distance = Math.sqrt(Math.pow(waitingLobby.getX() - mapCenter.getX(), 2)
                        + Math.pow(waitingLobby.getZ() - mapCenter.getZ(), 2));
                if (distance > borderInitialSize / 2) {
                    plugin.getLogger()
                            .warning("Waiting lobby is outside initial border! Distance from map center: "
                                    + String.format("%.1f", distance) + ", Border radius: "
                                    + String.format("%.1f", borderInitialSize / 2));
                    Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY
                            + "] " + ChatColor.RED + "Cảnh báo: Sảnh chờ ngoài vòng bo!");
                }
            }

            // Validate other config values
            if (borderInitialSize <= 0) {
                borderInitialSize = 600.0;
                plugin.getLogger().warning("Invalid border-initial-size in config, defaulting to 600");
            }
            if (borderFinalSize <= 0) {
                borderFinalSize = 5.0;
                plugin.getLogger().warning("Invalid border-final-size in config, defaulting to 5");
            }
            if (numberOfPhases < 1) {
                numberOfPhases = 6;
                plugin.getLogger().warning("Invalid number-of-phases in config, defaulting to 6");
            }
            if (finalPhaseDuration < 0) {
                finalPhaseDuration = 120;
                plugin.getLogger().warning("Invalid final-phase-duration in config, defaulting to 120");
            }

            plugin.getLogger().info(
                    "Loaded config: border-initial-size=" + borderInitialSize +
                            ", border-final-size=" + borderFinalSize +
                            ", min-players=" + minPlayers +
                            ", number-of-phases=" + numberOfPhases +
                            ", border-shrink-factor=" + borderShrinkFactor +
                            ", border-center-offset=" + borderCenterOffset +
                            ", final-phase-duration=" + finalPhaseDuration +
                            ", waiting-lobby=" + waitingLobby.toString());
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading config: " + e.getMessage());
            e.printStackTrace();
            // Default values
            World defaultWorld = Bukkit.getWorld("warp_wheat");
            waitingLobby = new Location(defaultWorld != null ? defaultWorld : Bukkit.getWorlds().get(0),
                    365.565, 207, -40.461, 0, 0);
            borderInitialSize = 600.0;
            borderFinalSize = 5.0;
            numberOfPhases = 6;
            borderShrinkFactor = 0.85;
            finalPhaseDuration = 120;
            mongoConnectionString = "";
            mongoDatabase = "BattleGroundTopKill";
            mongoKillsCollection = "players_topkills";
            mongoMatchHistoryCollection = "match_history";
        }
    }

    private Location parseConfigLocation(String path) {
        String worldName = plugin.getConfig().getString(path + ".world");
        if (worldName == null) {
            plugin.getLogger().warning("Missing world for " + path + " in config");
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Invalid world '" + worldName + "' for " + path + " in config");
            return null;
        }
        double x = plugin.getConfig().getDouble(path + ".x", 0.0);
        double y = plugin.getConfig().getDouble(path + ".y", 100.0);
        double z = plugin.getConfig().getDouble(path + ".z", 0.0);
        float yaw = (float) plugin.getConfig().getDouble(path + ".yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble(path + ".pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private void scheduleStartCheck() {
        startCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isRunning && startTime > 0 && System.currentTimeMillis() / 1000 >= startTime) {
                    plugin.getLogger().info("Start check triggered, calling start()");
                    start();
                    startTime = -1;
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    public void registerPlayer(Player player) {
        if (!isJoinOpen) {
            player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                    + ChatColor.RED + "Battleground chưa mở đăng ký! Vui lòng chờ admin mở bằng /bg open.");
            return;
        }
        if (!isRunning && !participants.contains(player)) {
            participants.add(player);
            String addCommand = "rg addmember -w warp_wheat __global__ " + player.getName();
            plugin.getLogger().info("Attempting to execute WorldGuard command: " + addCommand);
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), addCommand);
            plugin.getLogger().info("WorldGuard command result: " + addCommand + " (success: " + success + ")");
            if (!success) {
                player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                        + ChatColor.RED + "Lỗi: Không thể thêm bạn vào vùng Battleground. Liên hệ admin.");
                plugin.getLogger().severe("Failed to add " + player.getName() + " to region __global__ in warp_wheat");
                // Remove player from participants to prevent inconsistent state
                participants.remove(player);
                return;
            }

            // Log waitingLobby details
            plugin.getLogger().info("WaitingLobby: " + (waitingLobby != null ? waitingLobby.toString() : "null") +
                    ", World: "
                    + (waitingLobby != null && waitingLobby.getWorld() != null ? waitingLobby.getWorld().getName()
                            : "null"));

            // Delay teleport to ensure WorldGuard processes the command
            new BukkitRunnable() {
                @Override
                public void run() {
                    plugin.getLogger().info("Teleporting " + player.getName() + " to " + waitingLobby);
                    player.teleport(waitingLobby);
                    plugin.getLogger().info("Teleport successful for " + player.getName());
                    player.setGameMode(GameMode.SURVIVAL);
                    player.setFoodLevel(20);
                    for (PotionEffect effect : player.getActivePotionEffects()) {
                        player.removePotionEffect(effect.getType());
                    }

                    player.sendMessage(
                            ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                                    + ChatColor.GREEN + "Bạn đã tham gia Battleground và được đưa đến sảnh chờ!");
                    Bukkit.broadcastMessage(
                            ChatColor.YELLOW + "Hiện có " + participants.size()
                                    + " người tham gia.");
                    plugin.getLogger().info("Player " + player.getName()
                            + " registered and teleported to waiting lobby at " + waitingLobby + ". Participants: "
                            + participants.size());
                }
            }.runTaskLater(plugin, 20L); // Delay 1 second (20 ticks)
        } else {
            player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                    + ChatColor.RED + "Bạn đã đăng ký hoặc trận đã bắt đầu!");
        }
    }

    public Location getLobbyLocation() {
        return lobbyLocation;
    }

    public void unregisterPlayer(Player player) {
        plugin.getLogger().info("Attempting to unregister player: " + player.getName());
        boolean removed = participants.remove(player);
        plugin.getLogger()
                .info("Remove " + player.getName() + " from participants: " + (removed ? "success" : "failed"));

        // Execute WorldGuard command
        String removeCommand = "rg removemember -w warp_wheat __global__ " + player.getName();
        boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), removeCommand);
        plugin.getLogger().info("Executing command: " + removeCommand + " (success: " + success + ")");
        if (!success) {
            plugin.getLogger().warning("WorldGuard command failed for " + player.getName() + ", proceeding with reset");
        }

        // Set game mode to SURVIVAL
        plugin.getLogger().info("Setting game mode to SURVIVAL for " + player.getName());
        player.setGameMode(GameMode.SURVIVAL);
        plugin.getLogger().info("Set " + player.getName() + " to SURVIVAL mode");

        // Teleport to world spawn
        String spawnWorldName = plugin.getConfig().getString("spawn-world", "world");
        World world = Bukkit.getWorld(spawnWorldName);
        Location spawnLocation;
        if (world != null) {
            spawnLocation = world.getSpawnLocation();
        } else {
            plugin.getLogger().warning("World '" + spawnWorldName + "' not found, using default world spawn");
            spawnLocation = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        plugin.getLogger().info("Teleporting " + player.getName() + " to spawn: " + spawnLocation.toString());
        player.teleport(spawnLocation);
        plugin.getLogger().info("Teleported " + player.getName() + " to spawn: " + spawnLocation.toString());

        // Verify teleport location
        new BukkitRunnable() {
            @Override
            public void run() {
                Location actualLocation = player.getLocation();
                plugin.getLogger().info("Actual location of " + player.getName() + " after unregister teleport: "
                        + actualLocation.toString());
                if (!actualLocation.getWorld().getName().equals(spawnLocation.getWorld().getName()) ||
                        Math.abs(actualLocation.getX() - spawnLocation.getX()) > 1 ||
                        Math.abs(actualLocation.getY() - spawnLocation.getY()) > 1 ||
                        Math.abs(actualLocation.getZ() - spawnLocation.getZ()) > 1) {
                    plugin.getLogger().warning("Teleport mismatch for " + player.getName() +
                            "! Expected: " + spawnLocation.toString() +
                            ", Actual: " + actualLocation.toString());
                    player.teleport(spawnLocation);
                    plugin.getLogger()
                            .info("Retried teleport for " + player.getName() + " to: " + spawnLocation.toString());
                }
                // Ensure SURVIVAL mode
                if (player.getGameMode() != GameMode.SURVIVAL) {
                    player.setGameMode(GameMode.SURVIVAL);
                    plugin.getLogger()
                            .warning("Forced SURVIVAL mode for " + player.getName() + " after unregister teleport");
                }
            }
        }.runTaskLater(plugin, 2L);

        // Restore scoreboard
        resetPlayerScoreboard(player);

        // Notify player
        player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] " +
                ChatColor.YELLOW + "Bạn đã rời khỏi Battleground!");
        plugin.getLogger().info("Player " + player.getName() + " unregistered and reset to survival mode");

        // Execute /board command twice
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    player.performCommand("board");
                    plugin.getLogger().info("Executed first /board for player: " + player.getName());
                } catch (Exception e) {
                    plugin.getLogger().warning(
                            "Failed to execute first /board for player " + player.getName() + ": "
                                    + e.getMessage());
                }
            }
        }.runTaskLater(plugin, 5L);

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    player.performCommand("board");
                    plugin.getLogger().info("Executed second /board for player: " + player.getName());
                } catch (Exception e) {
                    plugin.getLogger().warning(
                            "Failed to execute second /board for player " + player.getName() + ": "
                                    + e.getMessage());
                }
            }
        }.runTaskLater(plugin, 10L);
    }

    public void openJoin() {
        isJoinOpen = true;
        Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                + ChatColor.GREEN + "Battleground đã mở đăng ký! Sử dụng /bg join để tham gia.");
        plugin.getLogger().info("Battleground join opened");
    }

    public void closeJoin() {
        isJoinOpen = false;
        Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                + ChatColor.RED + "Battleground đã đóng đăng ký! Không thể tham gia lúc này.");
        plugin.getLogger().info("Battleground join closed");
    }

    public boolean isJoinOpen() {
        return isJoinOpen;
    }

    public void setStartTime(long seconds) {
        startTime = System.currentTimeMillis() / 1000 + seconds;
        Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                + ChatColor.YELLOW + "Battleground sẽ bắt đầu sau " + seconds + " giây!");
        plugin.getLogger().info("Set start time to " + seconds + " seconds from now");
    }

    public void setMatchDuration(int seconds) {
        this.matchDuration = seconds;
        plugin.getLogger().info("Match duration set to: " + seconds + " seconds");
    }

    public void start() {
        if (isRunning) {
            Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                    + ChatColor.RED + "Trận đã bắt đầu!");
            plugin.getLogger().warning("Start called while match is already running!");
            return;
        }
        if (participants.size() < minPlayers) {
            Bukkit.broadcastMessage(
                    ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] " + ChatColor.RED
                            + "Cần ít nhất " + minPlayers + " người để bắt đầu! Hiện có: " + participants.size());
            return;
        }
        isRunning = true;
        isCountingDown = true;
        isJoinOpen = false;
        startTime = -1;
        kills.clear();

        String participantNames = participants.stream().map(Player::getName).collect(Collectors.joining(", "));
        plugin.getLogger()
                .info("Starting Battleground with " + participants.size() + " participants: " + participantNames);

        if (participants.size() == 1) {
            Player winner = participants.get(0);
            Bukkit.broadcastMessage(
                    ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] " + ChatColor.GOLD
                            + winner.getName() + " đã thắng Battleground vì là người duy nhất tham gia!");
            stop();
            return;
        }

        for (Player p : participants) {
            if (p.isOnline()) {
                p.addPotionEffect(
                        new PotionEffect(PotionEffectType.REGENERATION, invincibilityDuration * 5, 4, false, false));
                p.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                        + ChatColor.YELLOW + "Chờ đếm ngược để bắt đầu!");
            }
        }
        setupScoreboard();

        if (countdownTask != null && !countdownTask.isCancelled()) {
            countdownTask.cancel();
            plugin.getLogger().info("Cancelled existing countdownTask before starting new one");
        }

        countdownTask = new BukkitRunnable() {
            int countdown = 120; // Fixed 2-minute countdown

            @Override
            public void run() {
                if (countdown > 10) {
                    if (countdown % 30 == 0 || countdown == 20 || countdown == 15) {
                        Bukkit.broadcastMessage(
                                ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY
                                        + "] " + ChatColor.YELLOW + "Trận bắt đầu sau " + countdown + " giây!");
                        updateCountdownScoreboard(countdown, false);
                    }
                } else if (countdown > 0) {
                    for (Player p : participants) {
                        if (p.isOnline()) {
                            p.addPotionEffect(
                                    new PotionEffect(PotionEffectType.INVISIBILITY, invincibilityDuration * 8, 0, false,
                                            false));
                        }
                    }
                    Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY
                            + "] " + ChatColor.YELLOW + "Trận bắt đầu sau " + countdown + " giây!");
                    updateCountdownScoreboard(countdown, false);
                } else {
                    isCountingDown = false;
                    startInvincibilityPhase();
                    cancel();
                    plugin.getLogger().info("Countdown finished, invincibility phase started");
                }
                countdown--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void startInvincibilityPhase() {
        World world = mapCenter.getWorld();
        border = world.getWorldBorder();
        border.reset();

        Location borderCenter = mapCenter.clone();
        if (borderCenterOffset > 0) {
            Random rand = new Random();
            double offsetX = (rand.nextDouble() - 0.5) * borderCenterOffset;
            double offsetZ = (rand.nextDouble() - 0.5) * borderCenterOffset;
            borderCenter.add(offsetX, 0, offsetZ);
            plugin.getLogger()
                    .info("Randomized border center: x=" + borderCenter.getX() + ", z=" + borderCenter.getZ());
        }

        border.setCenter(borderCenter);
        border.setSize(borderInitialSize);
        border.setDamageAmount(5.0);
        border.setDamageBuffer(0.0);
        border.setWarningDistance(5);
        border.setWarningTime(15);
        plugin.getLogger()
                .info("Border set to initial size: " + borderInitialSize + ", center: " + borderCenter.toVector());

        String playerList = participants.stream()
                .map(Player::getName)
                .collect(Collectors.joining(", "));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(
                    ChatColor.GOLD + "⚔ BATTLEGROUND ⚔",
                    ChatColor.YELLOW + "Rơi nhẹ và thời gian bất tử bắt đầu!",
                    20, 100, 20);

            if (participants.contains(p)) {
                p.sendMessage("");
                p.sendMessage(ChatColor.GOLD + "✦ BATTLEGROUND ✦");
                p.sendMessage(ChatColor.WHITE + "• Người chơi: " + ChatColor.YELLOW + playerList);
                p.sendMessage(ChatColor.WHITE + "• Border: " + ChatColor.YELLOW
                        + String.format("%.1f", borderInitialSize) + " blocks");
                p.sendMessage(ChatColor.WHITE + "• Tâm vòng bo: " + ChatColor.YELLOW
                        + String.format("x=%.1f, z=%.1f", borderCenter.getX(), borderCenter.getZ()));
                p.sendMessage(ChatColor.WHITE + "• Bất tử: " + ChatColor.GREEN + invincibilityDuration + " giây");
                p.sendMessage("");
            }
        }

        for (Player p : participants) {
            if (p.isOnline()) {
                // Get current player location and reduce Y by 3
                Location currentLocation = p.getLocation();
                Location dropLocation = new Location(
                        currentLocation.getWorld(),
                        currentLocation.getX(),
                        currentLocation.getY() - 3,
                        currentLocation.getZ(),
                        currentLocation.getYaw(),
                        currentLocation.getPitch());
                p.teleport(dropLocation);
                p.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                        + ChatColor.GREEN + "Bạn đang trong thời gian bất tử và rơi nhẹ!");
                p.addPotionEffect(
                        new PotionEffect(PotionEffectType.SLOW_FALLING, invincibilityDuration * 7, 0, false, false));
                p.addPotionEffect(new PotionEffect(
                        PotionEffectType.SPEED, invincibilityDuration * 10, 3, false, false));
                plugin.getLogger().info("Teleported " + p.getName() + " to drop location: " +
                        dropLocation.getWorld().getName() + " x=" + dropLocation.getX() + ", y=" + dropLocation.getY()
                        + ", z=" + dropLocation.getZ());
            }
        }

        // Invincibility countdown
        new BukkitRunnable() {
            int countdown = invincibilityDuration;

            @Override
            public void run() {
                if (countdown <= 0) {
                    for (Player p : participants) {
                        if (p.isOnline()) {
                            // SLOW_FALLING is kept until player lands naturally
                            p.sendMessage(
                                    ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                                            + ChatColor.RED + "Bất tử đã hết, trận đấu bắt đầu!");
                            p.sendTitle(
                                    ChatColor.RED + "⚠ Thời gian bất tử đã hết ⚠",
                                    ChatColor.RED + "Trận Chiến Bắt Đầu!",
                                    10, 60, 20);
                            plugin.getLogger().info("Disabled invulnerability for " + p.getName());
                        }
                    }
                    // Ensure PvP is enabled in warp_wheat
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rg flag -w warp_wheat __global__ pvp allow");
                    plugin.getLogger().info("Set PvP to allow in warp_wheat");
                    startMatch();
                    cancel();
                    plugin.getLogger().info("Invincibility phase ended, starting main match");
                } else {
                    updateCountdownScoreboard(countdown, true);
                    if (countdown == 30 || countdown == 20 || countdown == 10 || countdown <= 5) {
                        Bukkit.broadcastMessage(
                                ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                                        + ChatColor.YELLOW + "Thời gian bất tử còn lại: " + countdown + " giây!");
                    }
                }
                countdown--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void updateCountdownScoreboard(int countdown, boolean isInvincibility) {
        Objective objective = gameScoreboard.getObjective("bginfo");
        if (objective != null) {
            objective.unregister();
        }
        objective = gameScoreboard.registerNewObjective("bginfo", Criteria.DUMMY, ChatColor.GOLD + "♦ Battleground ♦");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = 15;
        if (isInvincibility) {
            objective.getScore(ChatColor.YELLOW + "Bất tử còn lại: " + formatTime(countdown)).setScore(score--);
        } else {
            objective.getScore(ChatColor.YELLOW + "Bắt đầu trong: " + formatTime(countdown)).setScore(score--);
        }
        objective.getScore("").setScore(score--);
        objective.getScore(ChatColor.WHITE + "⚔ Số người tham gia: " + participants.size()).setScore(score--);

        for (Player p : participants) {
            if (p.isOnline()) {
                p.setScoreboard(gameScoreboard);
            }
        }
    }

    public void setInitialBorderSize(double size) {
        this.borderInitialSize = size;
        plugin.getLogger().info("Initial border size set to: " + size);
    }

    private void startMatch() {
        if (!isRunning) {
            plugin.getLogger().warning("startMatch called while match is not running, ignoring");
            return;
        }
        matchStartTime = System.currentTimeMillis() / 1000;
        currentPhase = 1; // Start at Phase 1
        phaseStartTime = matchStartTime; // Set initial phase start time for stable period
        createBorderBar();
        updateBorderBar();
        showBorderParticles();
        updateScoreboard();

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    cancel();
                    return;
                }
                updateScoreboard();
                updateBorderBar();
            }
        }.runTaskTimer(plugin, 20L, 20L);

        long timePerPhase = matchDuration / numberOfPhases; // In seconds
        startBorderPhases(timePerPhase * 20L); // Convert to ticks

        new BukkitRunnable() {
            @Override
            public void run() {
                int alivePlayers = 0;
                Player lastAlivePlayer = null;

                for (Player p : participants) {
                    if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                        alivePlayers++;
                        lastAlivePlayer = p;
                    }
                }

                if (alivePlayers == 1 && lastAlivePlayer != null) {
                    stop();
                    cancel();
                } else if (alivePlayers == 0) {
                    Bukkit.broadcastMessage(ChatColor.RED + "Trận đấu kết thúc - Không có người thắng cuộc!");
                    stop();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    public void endMatch(Player winner) {
        if (!isRunning) {
            plugin.getLogger().warning("No match is running to end!");
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rg flag -w warp_wheat __global__ pvp deny");
        plugin.getLogger().info("Set PvP to allow in warp_wheat");
        isRunning = false;
        String winnerName = winner != null ? winner.getName() : "None";
        String phaseReached = currentPhase <= 6 ? "Phase " + currentPhase : "Overtime";
        saveMatchHistory(winnerName, new ArrayList<>(participants), phaseReached);
        Bukkit.broadcastMessage(
                ChatColor.GOLD + "Trận đấu đã kết thúc! Người thắng: " + (winner != null ? winnerName : "Không có"));
        participants.clear();
        totalKills.clear();
        currentPhase = 0;
        matchStartTime = 0;
    }

    private void startBorderPhases(long timePerPhaseTicks) {
        double[] phaseSizes = new double[numberOfPhases + 1];
        phaseSizes[0] = borderInitialSize;

        // Percentage reductions for Phases 1-5
        double[] reductions = { 0.30, 0.20, 0.20, 0.10, 0.15 };
        double cumulativeReduction = 0.0;

        for (int i = 1; i <= numberOfPhases; i++) {
            if (i == numberOfPhases) {
                phaseSizes[i] = borderFinalSize; // Phase 6: Final size (5 blocks)
            } else {
                cumulativeReduction += reductions[i - 1];
                phaseSizes[i] = borderInitialSize * (1 - cumulativeReduction);
            }
            plugin.getLogger().info("Phase " + i + " size calculated: " + phaseSizes[i]);
        }

        final long shrinkTime = 30L; // 30 seconds to shrink
        final long stableTime = (timePerPhaseTicks / 20L) - shrinkTime; // Stable period in seconds

        new BukkitRunnable() {
            int phase = 0;

            @Override
            public void run() {
                if (!isRunning) {
                    cancel();
                    return;
                }

                if (phase == 0) {
                    // Initial shrink for Phase 1
                    double newSize = phaseSizes[1];
                    currentPhase = 1;
                    isBorderShrinking = true;

                    border.setSize(newSize, shrinkTime);
                    phaseStartTime = (System.currentTimeMillis() / 1000) + shrinkTime; // Set after shrink

                    String message = ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                            + ChatColor.GOLD + "Border đang thu nhỏ!\n" +
                            ChatColor.YELLOW + "Kích thước mới: " + String.format("%.1f", newSize) + " blocks";

                    for (Player p : participants) {
                        if (p.isOnline()) {
                            p.sendTitle(
                                    ChatColor.RED + "⚠ Border thu nhỏ ⚠",
                                    ChatColor.YELLOW + "Kích thước mới: " + String.format("%.1f", newSize) + " Blocks",
                                    10, 60, 20);
                            p.sendMessage(ChatColor.YELLOW + "Giai đoạn " + 1 + "/" + numberOfPhases);
                        }
                    }

                    Bukkit.broadcastMessage(message);
                    showBorderParticles();
                    createBorderBar();
                    updateBorderBar();
                    plugin.getLogger().info("Phase 1/" + numberOfPhases +
                            ": size=" + String.format("%.1f", newSize));

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            isBorderShrinking = false;
                            plugin.getLogger().info("Border shrink completed for phase 1");
                        }
                    }.runTaskLater(plugin, shrinkTime * 20L);
                }

                phase++;
                if (phase >= 2 && phase <= numberOfPhases) {
                    double newSize = phaseSizes[phase];
                    currentPhase = phase;
                    isBorderShrinking = true;

                    border.setSize(newSize, shrinkTime);
                    phaseStartTime = (System.currentTimeMillis() / 1000) + shrinkTime;

                    String message = ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                            + ChatColor.GOLD + "Border đang thu nhỏ!\n" +
                            ChatColor.YELLOW + "Kích thước mới: " + String.format("%.1f", newSize) + " blocks";

                    for (Player p : participants) {
                        if (p.isOnline()) {
                            p.sendTitle(
                                    ChatColor.RED + "⚠ Border thu nhỏ ⚠",
                                    ChatColor.YELLOW + "Kích thước mới: " + String.format("%.1f", newSize) + " Blocks",
                                    10, 60, 20);
                            p.sendMessage(ChatColor.YELLOW + "Giai đoạn " + phase + "/" + numberOfPhases);
                        }
                    }

                    Bukkit.broadcastMessage(message);
                    showBorderParticles();
                    createBorderBar();
                    updateBorderBar();
                    plugin.getLogger().info("Phase " + phase + "/" + numberOfPhases +
                            ": size=" + String.format("%.1f", newSize));

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            isBorderShrinking = false;
                            plugin.getLogger().info("Border shrink completed for phase " + phase);
                        }
                    }.runTaskLater(plugin, shrinkTime * 20L);

                    if (phase == numberOfPhases) {
                        // Calculate remaining match time for final phase
                        long timeElapsed = (System.currentTimeMillis() / 1000) - matchStartTime;
                        long remainingTime = Math.max(0, matchDuration - timeElapsed);
                        finalPhaseCountdown = Math.min(finalPhaseDuration, remainingTime); // Cap at remaining time
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                startFinalPhase();
                            }
                        }.runTaskLater(plugin, stableTime * 20L);
                        cancel();
                    }
                }

            }
        }.runTaskTimer(plugin, 0L, timePerPhaseTicks);

    }

    public void stopMatch(CommandSender sender) {
        if (!isRunning) {
            sender.sendMessage(ChatColor.RED + "Không có trận đấu nào đang diễn ra!");
            return;
        }
        isRunning = false;
        saveMatchHistory("None", new ArrayList<>(participants),
                currentPhase <= 6 ? "Phase " + currentPhase : "Overtime");
        Bukkit.broadcastMessage(ChatColor.RED + "Trận đấu đã bị dừng bởi " + sender.getName() + "!");
        participants.clear();
        totalKills.clear();
        currentPhase = 0;
        matchStartTime = 0;
    }

    public void saveMatchHistory(String winner, List<Player> participants, String phaseReached) {
        if (matchHistoryCollection == null) {
            plugin.getLogger().warning("Cannot save match history: MongoDB not initialized.");
            return;
        }
        try {
            List<Map.Entry<Player, Integer>> matchKillers = new ArrayList<>(kills.entrySet());
            matchKillers.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            List<Document> topKills = new ArrayList<>();
            int rank = 0;
            for (Map.Entry<Player, Integer> entry : matchKillers) {
                if (rank >= 3)
                    break;
                topKills.add(new Document("name", entry.getKey().getName())
                        .append("kills", entry.getValue()));
                rank++;
            }
            for (Player p : participants) {
                if (rank >= 3)
                    break;
                if (!kills.containsKey(p)) {
                    topKills.add(new Document("name", p.getName()).append("kills", 0));
                    rank++;
                }
            }

            // Tính toán Top 5 sống sót
            List<String> topSurvivors = new ArrayList<>();
            Player winnerPlayer = participants.stream()
                    .filter(p -> p.getName().equals(winner))
                    .findFirst()
                    .orElse(null);
            if (winnerPlayer != null) {
                topSurvivors.add(winnerPlayer.getName());
            }
            List<Player> reversedDeathOrder = new ArrayList<>(deathOrder);
            Collections.reverse(reversedDeathOrder);
            for (Player p : reversedDeathOrder) {
                if (topSurvivors.size() >= 5)
                    break;
                if (!topSurvivors.contains(p.getName())) {
                    topSurvivors.add(p.getName());
                }
            }
            for (Player p : participants) {
                if (topSurvivors.size() >= 5)
                    break;
                if (!topSurvivors.contains(p.getName()) && !deathOrder.contains(p)) {
                    topSurvivors.add(p.getName());
                }
            }

            Document matchDoc = new Document("_id", UUID.randomUUID().toString())
                    .append("timestamp", new Date())
                    .append("winner", winner)
                    .append("participants", participants.stream().map(Player::getName).collect(Collectors.toList()))
                    .append("top_kills", topKills)
                    .append("top_survivors", topSurvivors);

            plugin.getLogger()
                    .info("Attempting to save match history: winner=" + winner + ", participants=" + participants.size()
                            + ", top_kills=" + topKills.size() + ", top_survivors=" + topSurvivors.size());
            matchHistoryCollection.insertOne(matchDoc);
            plugin.getLogger().info("Successfully saved match history to MongoDB: winner=" + winner + ", participants="
                    + participants.size());
        } catch (com.mongodb.MongoWriteException e) {
            plugin.getLogger().severe("MongoDB write error while saving match history: " + e.getMessage() +
                    ", Code: " + e.getCode() + ", Details: " + e.getError().toString());
            e.printStackTrace();
        } catch (Exception e) {
            plugin.getLogger().severe("Unexpected error saving match history to MongoDB: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public String getRecentMatch() {
        if (matchHistoryCollection == null) {
            return ChatColor.RED + "Chưa có dữ liệu lịch sử trận đấu do MongoDB không được khởi tạo!";
        }
        try {
            Document match = matchHistoryCollection.find()
                    .sort(new Document("timestamp", -1))
                    .limit(1)
                    .first();
            if (match == null) {
                return ChatColor.RED + "Chưa có trận đấu nào được ghi lại!";
            }

            StringBuilder result = new StringBuilder();
            result.append(ChatColor.GOLD + "✦ LỊCH SỬ TRẬN ĐẤU GẦN NHẤT ✦\n");
            result.append(ChatColor.YELLOW + "Thời gian: " + ChatColor.WHITE
                    + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(match.getDate("timestamp")) + "\n");
            result.append(ChatColor.YELLOW + "Người thắng: " + ChatColor.GREEN + match.getString("winner") + "\n");
            result.append(ChatColor.YELLOW + "Người tham gia: " + ChatColor.WHITE
                    + String.join(", ", match.getList("participants", String.class)) + "\n");
            result.append(ChatColor.YELLOW + "Top Kills:\n");
            List<Document> topKills = match.getList("top_kills", Document.class);
            if (topKills == null || topKills.isEmpty()) {
                result.append(ChatColor.WHITE + "  - Không có kills nào được ghi lại\n");
            } else {
                int rank = 1;
                for (Document kill : topKills) {
                    String prefix = rank == 1 ? "Top 1:" : rank == 2 ? "Top 2:" : "Top 3:";
                    result.append(ChatColor.WHITE + "  - " + prefix + " " + kill.getString("name") + " ("
                            + ChatColor.GREEN + kill.getInteger("kills") + " kills)\n");
                    rank++;
                }
            }

            result.append(ChatColor.YELLOW + "Top Sống Sót:\n");
            List<String> topSurvivors = match.getList("top_survivors", String.class);
            if (topSurvivors == null || topSurvivors.isEmpty()) {
                result.append(ChatColor.WHITE + "  - Không có dữ liệu sống sót được ghi lại\n");
            } else {
                int rank = 1;
                for (String survivor : topSurvivors) {
                    String prefix = rank == 1 ? "Top 1:"
                            : rank == 2 ? "Top 2:"
                                    : rank == 3 ? "Top 3:"
                                            : "Top " + rank + ":";
                    String status = (rank == 1 && survivor.equals(match.getString("winner"))) ? " (Winner)" : "";
                    result.append(ChatColor.WHITE + "  - " + prefix + " " + survivor + ChatColor.GREEN + status + "\n");
                    rank++;
                }
            }

            return result.toString();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to fetch recent match from MongoDB: " + e.getMessage());
            e.printStackTrace();
            return ChatColor.RED + "Lỗi khi lấy lịch sử trận đấu từ MongoDB!";
        }
    }

    private void startFinalPhase() {
        Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                + ChatColor.RED + "CẢNH BÁO: Vòng bo cuối cùng!\n" +
                ChatColor.YELLOW + "Kích thước: " + String.format("%.1f", borderFinalSize) + " blocks\n" +
                ChatColor.YELLOW + "Tử chiến bắt đầu sau: " + formatTime(finalPhaseCountdown));

        for (Player p : participants) {
            if (p.isOnline()) {
                p.sendTitle(
                        ChatColor.RED + "⚠ Vòng bo cuối cùng ⚠",
                        ChatColor.YELLOW + "Tử chiến sau " + formatTime(finalPhaseCountdown),
                        10, 60, 20);
                p.sendMessage(ChatColor.YELLOW + "Tử chiến sau " + formatTime(finalPhaseCountdown));
            }
        }

        showBorderParticles();
        updateBorderBar();

        new BukkitRunnable() {
            long countdown = finalPhaseCountdown;

            @Override
            public void run() {
                if (!isRunning) {
                    cancel();
                    return;
                }

                finalPhaseCountdown = countdown; // Update global countdown
                if (countdown == 60 || countdown == 30 || countdown == 20 || countdown == 10
                        || (countdown > 0 && countdown <= 5)) {
                    Bukkit.broadcastMessage(
                            ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                                    + ChatColor.RED + "Tử chiến bắt đầu sau: " + formatTime(countdown));
                    for (Player p : participants) {
                        if (p.isOnline()) {
                            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
                        }
                    }
                }

                updateBorderBar();
                updateScoreboard();

                if (countdown <= 0) {
                    startOvertimePhase();
                    finalPhaseCountdown = -1; // Reset countdown
                    cancel();
                }
                countdown--;
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void startOvertimePhase() {
        // Remove existing BossBar
        if (borderBar != null) {
            borderBar.removeAll();
            borderBar = null;
        }

        // Create new BossBar for overtime
        borderBar = Bukkit.createBossBar(
                ChatColor.RED + "" + ChatColor.BOLD + "Tử Chiến",
                BarColor.RED,
                BarStyle.SOLID);
        borderBar.setProgress(1.0); // Full bar, static
        for (Player p : participants) {
            if (p.isOnline()) {
                borderBar.addPlayer(p);
            }
        }

        // Reset border
        border.reset();
        border = null;

        Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                + ChatColor.RED + "TỬ CHIẾN: Toàn bộ bản đồ sẽ chịu sát thương!");

        for (Player p : participants) {
            if (p.isOnline()) {
                p.sendTitle(
                        ChatColor.RED + "☠ TỬ CHIẾN ☠",
                        ChatColor.RED + "Toàn bộ map gây sát thương",
                        10, 60, 20);
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.0f);
            }
        }

        new BukkitRunnable() {
            int damagePhase = 0;

            @Override
            public void run() {
                if (!isRunning) {
                    cancel();
                    return;
                }

                damagePhase++;
                double damage = 1.0;

                if (damagePhase > 10) {
                    damage = 130.0;
                }
                if (damagePhase > 20) {
                    damage = 140.0;
                }
                if (damagePhase > 30) {
                    damage = 140.0;
                }

                for (Player p : participants) {
                    if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                        p.damage(damage);
                        p.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1, 0), 5, 0.5, 0.5,
                                0.5, 0.01);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void createBorderBar() {
        if (borderBar != null) {
            borderBar.removeAll();
        }
        borderBar = Bukkit.createBossBar(
                ChatColor.GOLD + "Vòng Bo: " + String.format("%.1f", border.getSize()) + " blocks",
                BarColor.YELLOW,
                BarStyle.SEGMENTED_10);
        for (Player p : participants) {
            borderBar.addPlayer(p);
        }
    }

    private void updateBorderBar() {
        if (borderBar != null && border != null) { // Only update if not in overtime
            if (currentPhase < numberOfPhases) {
                long timeLeft = matchStartTime + matchDuration - (System.currentTimeMillis() / 1000);
                borderBar.setTitle(ChatColor.GOLD + "Vòng Bo: " + String.format("%.1f", border.getSize()) + " blocks" +
                        ChatColor.YELLOW + " | Phase: " + currentPhase + "/" + numberOfPhases +
                        ChatColor.GREEN + " | Time: " + formatTime(Math.max(0, timeLeft)));
                double progress = border.getSize() / borderInitialSize;
                borderBar.setProgress(Math.max(0, Math.min(1, progress)));
            } else if (finalPhaseCountdown >= 0) {
                // Phase 6: Show countdown to overtime
                borderBar.setTitle(ChatColor.GOLD + "Vòng Bo: " + String.format("%.1f", border.getSize()) + " blocks" +
                        ChatColor.YELLOW + " | Phase: " + currentPhase + "/" + numberOfPhases +
                        ChatColor.RED + " | Tử Chiến Sau: " + formatTime(finalPhaseCountdown));
                double progress = border.getSize() / borderInitialSize;
                borderBar.setProgress(Math.max(0, Math.min(1, progress)));
            }
        }
    }

    private void showBorderParticles() {
        if (border == null)
            return;

        double size = border.getSize() / 2;
        Location center = border.getCenter();
        World world = center.getWorld();

        for (double x = -size; x <= size; x += 5) {
            spawnBorderParticle(world, center.clone().add(x, 0, -size));
            spawnBorderParticle(world, center.clone().add(x, 0, size));
        }

        for (double z = -size; z <= size; z += 5) {
            spawnBorderParticle(world, center.clone().add(-size, 0, z));
            spawnBorderParticle(world, center.clone().add(size, 0, z));
        }
    }

    private void spawnBorderParticle(World world, Location loc) {
        for (double y = loc.getY(); y <= loc.getY() + 5; y += 2) {
            world.spawnParticle(Particle.END_ROD, loc.clone().add(0, y, 0), 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.DRAGON_BREATH, loc.clone().add(0, y, 0), 1, 0, 0, 0, 0);
        }
    }

    private String formatTime(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    public void stop() {
        if (!isRunning) {
            plugin.getLogger().warning("Stop called while match is not running!");
            return;
        }
        plugin.getLogger().info("Participants before stop: "
                + participants.stream().map(Player::getName).collect(Collectors.joining(", ")));

        List<Player> playersToRemove = new ArrayList<>(participants);
        plugin.getLogger().info("Created playersToRemove list with " + playersToRemove.size() + " players");

        Player winner = null;
        for (Player p : participants) {
            if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                winner = p;
                break;
            }
        }

        // Tính toán Top 5 sống sót
        List<Player> topSurvivors = new ArrayList<>();
        if (winner != null) {
            topSurvivors.add(winner); // Top 1 là người chiến thắng
        }
        // Lấy tối đa 4 người chết cuối cùng từ deathOrder (ngược lại)
        List<Player> reversedDeathOrder = new ArrayList<>(deathOrder);
        Collections.reverse(reversedDeathOrder);
        for (Player p : reversedDeathOrder) {
            if (topSurvivors.size() >= 5)
                break;
            if (!topSurvivors.contains(p)) {
                topSurvivors.add(p);
            }
        }
        // Nếu không đủ 5 người, thêm các người chơi còn lại
        for (Player p : participants) {
            if (topSurvivors.size() >= 5)
                break;
            if (!topSurvivors.contains(p) && !deathOrder.contains(p)) {
                topSurvivors.add(p);
            }
        }

        String phaseReached = currentPhase <= 6 ? "Phase " + currentPhase : "Overtime";
        endMatch(winner);
        plugin.getLogger().info("Match stopped with winner: " + (winner != null ? winner.getName() : "None"));

        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(ChatColor.WHITE + "✦ KẾT QUẢ TRẬN ĐẤU BattleGround  ✦");
        Bukkit.broadcastMessage("");

        if (winner != null) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "⚔ NGƯỜI SỐNG SÓT CUỐI CÙNG ⚔");
            Bukkit.broadcastMessage("");
            Bukkit.broadcastMessage(ChatColor.YELLOW + "► " + winner.getName());
            Bukkit.broadcastMessage("");
            Bukkit.broadcastMessage("");
            Bukkit.broadcastMessage("");

            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle(
                        ChatColor.GOLD + "⚔ " + winner.getName() + " ⚔",
                        ChatColor.YELLOW + "Đã chiến thắng!",
                        20, 100, 20);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
        }

        if (!kills.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.RED + "☠ TOP KILL ☠");
            Map<Player, Integer> matchKills = new HashMap<>(kills);
            List<Map.Entry<Player, Integer>> matchKillers = new ArrayList<>(matchKills.entrySet());
            matchKillers.sort((a, b) -> b.getValue().compareTo(a.getValue()));

            int rank = 1;
            for (Map.Entry<Player, Integer> entry : matchKillers) {
                String prefix = rank == 1 ? "Top 1:" : rank == 2 ? "Top 2:" : "Top 3:";
                Bukkit.broadcastMessage(ChatColor.YELLOW + prefix + " " +
                        ChatColor.WHITE + entry.getKey().getName() + " " +
                        ChatColor.WHITE + "( " +
                        ChatColor.GREEN + entry.getValue() + " kill" +
                        ChatColor.WHITE + ")");
                if (rank >= 3)
                    break;
                rank++;
            }
        } else {
            plugin.getLogger().warning("No kills recorded for this match!");
            Bukkit.broadcastMessage(ChatColor.YELLOW + "No kills recorded for this match!");
        }

        // Hiển thị Top 5 sống sót
        if (!topSurvivors.isEmpty()) {
            Bukkit.broadcastMessage("");
            Bukkit.broadcastMessage(ChatColor.GREEN + "☠ TOP SỐNG SÓT ☠");
            int rank = 1;
            for (Player p : topSurvivors) {
                String prefix = rank == 1 ? "Top 1:"
                        : rank == 2 ? "Top 2:" : rank == 3 ? "Top 3:" : "Top " + rank + ":";
                String status = (rank == 1 && winner != null) ? " (Winner)" : "";
                Bukkit.broadcastMessage(ChatColor.YELLOW + prefix + " " +
                        ChatColor.WHITE + p.getName() + ChatColor.GREEN + status);
                rank++;
            }
        } else {
            plugin.getLogger().warning("No survivor data recorded for this match!");
            Bukkit.broadcastMessage(ChatColor.YELLOW + "No survivor data recorded for this match!");
        }

        kills.clear();
        deathOrder.clear(); // Xóa thứ tự tử trận

        Bukkit.broadcastMessage("");

        if (border != null) {
            border.reset();
            border = null;
        }

        if (borderBar != null) {
            borderBar.removeAll();
            borderBar = null;
        }

        plugin.getLogger().info("Starting player reset for " + playersToRemove.size() + " players");
        for (Player p : playersToRemove) {
            try {
                Player refreshedPlayer = Bukkit.getPlayer(p.getUniqueId());
                if (refreshedPlayer != null && refreshedPlayer.isOnline()) {
                    plugin.getLogger().info("Processing player: " + refreshedPlayer.getName() + ", online: true");
                    unregisterPlayer(refreshedPlayer);
                } else {
                    participants.remove(p);
                    originalScoreboards.remove(p);
                    vanishedArmor.remove(p);
                    plugin.getLogger().info("Removed offline player " + p.getName() + " from Battleground state");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error processing player " + p.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        plugin.getLogger().info("Completed player reset");

        if (gameScoreboard != null && gameScoreboard.getObjective("bginfo") != null) {
            gameScoreboard.getObjective("bginfo").unregister();
        }

        originalScoreboards.clear();
        vanishedArmor.clear();
        participants.clear();
        isRunning = false;
        isCountingDown = false;
        isJoinOpen = false;
        startTime = -1;
        currentPhase = 0;
        isBorderShrinking = false;

        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }

        for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
            if (task.getOwner().equals(plugin)) {
                task.cancel();
            }
        }

        Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] " +
                ChatColor.GOLD + "Battleground đã kết thúc!");
        plugin.getLogger().info("Match stopped and all players reset to survival mode");
    }

    public void saveKillData() {
        if (playerKillsCollection == null) {
            plugin.getLogger().warning("Cannot save kill data: MongoDB not initialized.");
            return;
        }
        try {
            for (Map.Entry<Player, Integer> entry : totalKills.entrySet()) {
                Player player = entry.getKey();
                String uuid = player.getUniqueId().toString();
                String playerName = player.getName();
                int kills = entry.getValue();

                Document query = new Document("_id", uuid);
                Document update = new Document("$set", new Document("name", playerName)
                        .append("total_kills", kills));
                playerKillsCollection.updateOne(query, update,
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
            }
            plugin.getLogger().info("Successfully saved kill data to MongoDB");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save kill data to MongoDB: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadKillData() {
        if (playerKillsCollection == null) {
            plugin.getLogger().warning("Cannot load kill data: MongoDB not initialized.");
            return;
        }
        try {
            totalKills.clear();
            for (Document doc : playerKillsCollection.find()) {
                String uuid = doc.getString("_id");
                String playerName = doc.getString("name");
                int total = doc.getInteger("total_kills", 0);
                try {
                    UUID playerUUID = UUID.fromString(uuid);
                    Player player = Bukkit.getPlayer(playerUUID);
                    if (player != null && player.isOnline()) {
                        totalKills.put(player, total);
                        plugin.getLogger().info("Loaded " + total + " kills for player " + playerName);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID in MongoDB data: " + uuid);
                }
            }
            plugin.getLogger().info("Successfully loaded kill data for " + totalKills.size() + " players from MongoDB");
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading kill data from MongoDB: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void forceResetBorder() {
        for (World world : Bukkit.getWorlds()) {
            WorldBorder border = world.getWorldBorder();
            border.reset();
            plugin.getLogger().info("Reset border for world: " + world.getName());
        }
        this.border = null;
        plugin.getLogger().info("All world borders have been reset");
    }

    public void forceShrinkBorder() {
        if (!isRunning || border == null) {
            return;
        }

        border.reset();
        border.setCenter(mapCenter);
        border.setSize(0, 1L);

        Bukkit.broadcastMessage(ChatColor.RED + "CẢNH BÁO: Border đã thu về 0!");
        plugin.getLogger().info("Border was force shrunk to 0");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public List<Player> getParticipants() {
        return participants;
    }

    public int getMinPlayers() {
        return minPlayers;
    }

    public Main getPlugin() {
        return plugin;
    }

    public double getBorderSize() {
        return border != null ? border.getSize() : -1;
    }

    public void setBorderSize(double size) {
        borderInitialSize = size;
        if (border != null) {
            border.setSize(size);
            plugin.getLogger().info("Border updated to size: " + size + " (area: " + size + "x" + size + ")");
            double actualSize = border.getSize();
            if (actualSize != size) {
                plugin.getLogger().warning(
                        "Border size mismatch in setBorderSize! Expected: " + size + ", Actual: " + actualSize);
            }
        }
    }

    private void setupScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        gameScoreboard = manager.getNewScoreboard();

        if (gameScoreboard.getObjective("bginfo") != null) {
            gameScoreboard.getObjective("bginfo").unregister();
        }

        Objective objective = gameScoreboard.registerNewObjective("bginfo", Criteria.DUMMY,
                ChatColor.GOLD + "♦ Battleground ♦");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        Scoreboard mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team team : mainScoreboard.getTeams()) {
            Team newTeam = gameScoreboard.registerNewTeam(team.getName());
            newTeam.setPrefix(team.getPrefix() != null ? team.getPrefix() : "");
            newTeam.setSuffix(team.getSuffix() != null ? team.getSuffix() : "");
            newTeam.setColor(team.getColor());
            newTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, team.getOption(Team.Option.NAME_TAG_VISIBILITY));
            newTeam.setOption(Team.Option.COLLISION_RULE, team.getOption(Team.Option.COLLISION_RULE));
            for (String entry : team.getEntries()) {
                newTeam.addEntry(entry);
            }
        }

        Objective nameHealthObj = gameScoreboard.registerNewObjective("name_health", Criteria.DUMMY,
                ChatColor.RED + "❤");
        nameHealthObj.setDisplaySlot(DisplaySlot.BELOW_NAME);

        for (Player p : participants) {
            if (p.isOnline()) {
                int health = (int) Math.ceil(p.getHealth());
                nameHealthObj.getScore(p.getName()).setScore(health);
                plugin.getLogger().info("Initialized health for " + p.getName() + ": " + health);
            }
        }

        for (Player p : participants) {
            if (!originalScoreboards.containsKey(p)) {
                Scoreboard current = p.getScoreboard();
                if (current != null && current != gameScoreboard) {
                    originalScoreboards.put(p, current);
                } else {
                    originalScoreboards.put(p, mainScoreboard);
                }
            }
            p.setScoreboard(gameScoreboard);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    cancel();
                    return;
                }
                Objective healthObj = gameScoreboard.getObjective("name_health");
                if (healthObj != null) {
                    for (Player p : participants) {
                        if (p.isOnline()) {
                            int health = (int) Math.ceil(p.getHealth());
                            healthObj.getScore(p.getName()).setScore(health);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 5L);
    }

    private void resetPlayerScoreboard(Player player) {
        if (originalScoreboards.containsKey(player)) {
            Scoreboard originalBoard = originalScoreboards.get(player);
            if (originalBoard != null) {
                player.setScoreboard(originalBoard);
                plugin.getLogger().info("Restored original scoreboard for player: " + player.getName());
            } else {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                plugin.getLogger().info("Set main scoreboard for player (original was null): " + player.getName());
            }
            originalScoreboards.remove(player);
        } else {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            plugin.getLogger().info("Set main scoreboard for player (no original): " + player.getName());
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    player.performCommand("board");
                    plugin.getLogger().info("Executed first /board for player: " + player.getName());
                } catch (Exception e) {
                    plugin.getLogger().warning(
                            "Failed to execute first /board for player " + player.getName() + ": " + e.getMessage());
                }
            }
        }.runTaskLater(plugin, 10L);

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    player.performCommand("board");
                    plugin.getLogger().info("Executed second /board for player: " + player.getName());
                } catch (Exception e) {
                    plugin.getLogger().warning(
                            "Failed to execute second /board for player " + player.getName() + ": " + e.getMessage());
                }
            }
        }.runTaskLater(plugin, 20L);
    }

    private void updateScoreboard() {
        if (!isRunning || isCountingDown)
            return;

        Objective objective = gameScoreboard.getObjective("bginfo");
        if (objective != null) {
            objective.unregister();
        }
        objective = gameScoreboard.registerNewObjective("bginfo", Criteria.DUMMY, ChatColor.GOLD + "♦ Battleground ♦");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int alive = 0;
        for (Player p : participants) {
            if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                alive++;
            }
        }

        int score = 15;
        long timeLeft = matchStartTime + matchDuration - (System.currentTimeMillis() / 1000);
        if (timeLeft > 0 && currentPhase < numberOfPhases) {
            objective.getScore(ChatColor.WHITE + "⌚ Thời gian còn lại: " + ChatColor.YELLOW + formatTime(timeLeft))
                    .setScore(score--);
            objective.getScore("").setScore(score--);
        }

        objective.getScore(ChatColor.WHITE + "⚔ Số người tham gia: " + participants.size()).setScore(score--);
        objective.getScore(ChatColor.GREEN + " ▸ Còn sống: " + alive).setScore(score--);
        objective.getScore(" ").setScore(score--);

        if (border != null) {
            objective
                    .getScore(
                            ChatColor.YELLOW + "⭕ Chiều rộng Bo: " + String.format("%.1f", border.getSize()) + " block")
                    .setScore(score--);
            objective.getScore(ChatColor.YELLOW + " ▸ Phase: " + currentPhase + "/" + numberOfPhases).setScore(score--);

            long stableTime = (matchDuration / numberOfPhases) - 30; // Stable period in seconds
            long timeToNextPhase = 0;

            if (currentPhase == 1 && !isBorderShrinking && phaseStartTime == matchStartTime) {
                // Initial stable period for Phase 1
                timeToNextPhase = phaseStartTime + stableTime - (System.currentTimeMillis() / 1000);
                if (timeToNextPhase > 0) {
                    objective.getScore(ChatColor.YELLOW + " ▸ Đợt Bo kế tiếp: " + formatTime(timeToNextPhase))
                            .setScore(score--);
                }
            } else if (currentPhase < numberOfPhases) {
                if (isBorderShrinking) {
                    objective.getScore(ChatColor.YELLOW + " ▸ Border đang thu nhỏ...").setScore(score--);
                } else {
                    timeToNextPhase = phaseStartTime + stableTime - (System.currentTimeMillis() / 1000);
                    if (timeToNextPhase > 0) {
                        objective.getScore(ChatColor.YELLOW + " ▸ Đợt Bo kế tiếp: " + formatTime(timeToNextPhase))
                                .setScore(score--);
                    }
                }
            } else if (finalPhaseCountdown >= 0) {
                objective.getScore(ChatColor.RED + " ▸ Tử Chiến Sau: " + formatTime(finalPhaseCountdown))
                        .setScore(score--);
            }
            objective.getScore("   ").setScore(score--);
        } else {
            objective.getScore(ChatColor.RED + "⚠ TỬ CHIẾN ĐANG DIỄN RA ⚠").setScore(score--);
            objective.getScore("   ").setScore(score--);
        }

        for (Player p : participants) {
            if (kills.containsKey(p)) {
                objective.getScore(ChatColor.YELLOW + p.getName() + ": " + kills.get(p) + " kills").setScore(score--);
            }
        }
        objective.getScore("  ").setScore(score--);

        for (Player p : participants) {
            if (p.isOnline()) {
                p.setScoreboard(gameScoreboard);
            }
        }
    }

    public String getStatus() {
        if (!isRunning) {
            return ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] " + ChatColor.RED
                    + "Không có trận đấu nào đang diễn ra!";
        }

        StringBuilder status = new StringBuilder();
        status.append(ChatColor.GOLD + "✦ Trạng thái BattleGround ✦\n");

        int alivePlayers = 0;
        List<String> alivePlayerNames = new ArrayList<>();
        for (Player p : participants) {
            if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                alivePlayers++;
                alivePlayerNames.add(p.getName());
            }
        }

        status.append(ChatColor.WHITE + "• Người còn sống: " + ChatColor.GREEN + alivePlayers + "/"
                + participants.size() + "\n");
        if (!alivePlayerNames.isEmpty()) {
            status.append(ChatColor.GRAY + "  ↳ " + String.join(", ", alivePlayerNames) + "\n");
        }

        if (border != null) {
            status.append(ChatColor.WHITE + "• Kích thước Border: " + ChatColor.YELLOW
                    + String.format("%.1f", border.getSize()) + "\n");
            status.append(
                    ChatColor.WHITE + "• Giai đoạn: " + ChatColor.YELLOW + currentPhase + "/" + numberOfPhases + "\n");
        }

        long timeLeft = matchStartTime + matchDuration - (System.currentTimeMillis() / 1000);
        if (timeLeft > 0) {
            status.append(ChatColor.WHITE + "• Thời gian còn lại: " + ChatColor.YELLOW + formatTime(timeLeft));
        }

        return status.toString();
    }

    public String getPlayerList() {
        if (participants.isEmpty()) {
            return ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] " + ChatColor.RED
                    + "Chưa có người chơi nào tham gia!";
        }

        StringBuilder list = new StringBuilder();
        list.append(ChatColor.GOLD + "✦ Danh sách người chơi (" + participants.size() + ") ✦\n");

        List<String> aliveList = new ArrayList<>();
        List<String> deadList = new ArrayList<>();

        for (Player p : participants) {
            String playerName = p.getName();
            if (p.getGameMode() == GameMode.SPECTATOR) {
                deadList.add(ChatColor.RED + "✗ " + playerName);
            } else {
                aliveList.add(ChatColor.GREEN + "✓ " + playerName);
            }
        }

        if (!aliveList.isEmpty()) {
            list.append(ChatColor.WHITE + "Còn sống:\n");
            list.append(ChatColor.GRAY + String.join("\n", aliveList) + "\n");
        }

        if (!deadList.isEmpty()) {
            list.append(ChatColor.WHITE + "Đã chết:\n");
            list.append(ChatColor.GRAY + String.join("\n", deadList));
        }

        return list.toString();
    }

    public boolean toggleBorderPause() {
        isPaused = !isPaused;
        if (isPaused && border != null) {
            if (borderTask != null)
                borderTask.cancel();
            return true;
        } else {
            return false;
        }
    }

    public void addKill(Player killer) {
        if (!isRunning) {
            plugin.getLogger().warning("Attempted to add kill for " + killer.getName() + " while no match is running!");
            return;
        }
        if (playerKillsCollection == null) {
            plugin.getLogger().warning("Cannot update kills: MongoDB not initialized.");
            return;
        }
        try {
            String uuid = killer.getUniqueId().toString();
            String playerName = killer.getName();
            // Update match-specific kills
            int matchKills = kills.getOrDefault(killer, 0) + 1;
            kills.put(killer, matchKills);
            // Update total kills
            int total = totalKills.getOrDefault(killer, 0) + 1;
            totalKills.put(killer, total);

            // Save to MongoDB (total kills only)
            Document query = new Document("_id", uuid);
            Document update = new Document("$set", new Document("name", playerName)
                    .append("total_kills", total));
            playerKillsCollection.updateOne(query, update, new UpdateOptions().upsert(true));
            plugin.getLogger()
                    .info("Updated kills for " + playerName + ": matchKills=" + matchKills + ", totalKills=" + total);
        } catch (Exception e) {
            plugin.getLogger()
                    .severe("Failed to update kills for " + killer.getName() + " in MongoDB: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public String getTopTotalKills() {
        if (playerKillsCollection == null) {
            return ChatColor.RED + "Chưa có thống kê kills nào do MongoDB không được khởi tạo!";
        }
        try {
            List<Document> topKillers = playerKillsCollection.find()
                    .sort(new Document("total_kills", -1))
                    .limit(10)
                    .into(new ArrayList<>());
            if (topKillers.isEmpty()) {
                return ChatColor.RED + "Chưa có thống kê kills nào!";
            }
            StringBuilder status = new StringBuilder();
            status.append(ChatColor.GOLD + "✦ TOP KILLS BattleGround ✦\n");
            int rank = 1;
            for (Document doc : topKillers) {
                String name = doc.getString("name");
                int kills = doc.getInteger("total_kills", 0);
                String prefix = rank == 1 ? "#1." : rank == 2 ? "#2." : rank == 3 ? "#3." : "#" + rank + ".";
                status.append(ChatColor.YELLOW + prefix + " " +
                        ChatColor.WHITE + name + ": " +
                        ChatColor.GREEN + kills + " kills\n");
                rank++;
            }
            return status.toString();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to fetch top kills from MongoDB: " + e.getMessage());
            e.printStackTrace();
            return ChatColor.RED + "Lỗi khi lấy top kills từ MongoDB!";
        }
    }

    private void startAutoSave() {
        autoSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveKillData();
                plugin.getLogger().info("Auto-saved kill data to MongoDB");
            }
        }.runTaskTimer(plugin, 6000L, 6000L); // Lưu mỗi 5 phút
    }

    public void cleanup() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        saveKillData();
        if (mongoClient != null) {
            mongoClient.close();
            plugin.getLogger().info("Closed MongoDB connection");
        }
        deathOrder.clear();
    }

    private void initializeMongoDB() {
        if (mongoConnectionString.isEmpty()) {
            plugin.getLogger().warning("MongoDB not initialized due to missing connection string.");
            return;
        }
        try {
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(mongoConnectionString))
                    .applyToClusterSettings(builder -> builder.serverSelectionTimeout(5, TimeUnit.SECONDS))
                    .build();
            mongoClient = MongoClients.create(settings);
            MongoDatabase database = mongoClient.getDatabase(mongoDatabase);
            playerKillsCollection = database.getCollection(mongoKillsCollection);
            matchHistoryCollection = database.getCollection(mongoMatchHistoryCollection);
            plugin.getLogger()
                    .info("Successfully connected to MongoDB Atlas: database=" + mongoDatabase + ", kills-collection="
                            + mongoKillsCollection + ", match-history-collection=" + mongoMatchHistoryCollection);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to connect to MongoDB: " + e.getMessage());
            e.printStackTrace();
            playerKillsCollection = null;
            matchHistoryCollection = null;
            mongoClient = null;
        }
    }

    public void recordDeath(Player player) {
        if (!deathOrder.contains(player)) {
            deathOrder.add(player);
            plugin.getLogger().info("Recorded death of " + player.getName() + " in death order");
        }
    }
}