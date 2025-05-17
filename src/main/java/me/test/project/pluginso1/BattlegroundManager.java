package me.test.project.pluginso1;

import org.bukkit.*;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

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
    private Location mapCenter;
    private int matchDuration;
    private int invincibilityDuration;
    private double borderInitialSize;
    private double borderFinalSize;
    private int minPlayers;
    private final Map<Player, Integer> kills = new HashMap<>(); // Kills trong trận hiện tại
    private final Map<Player, Integer> totalKills = new HashMap<>(); // Tổng kills từ tất cả các trận
    private int currentPhase = 0;
    private boolean isPaused = false;
    private BukkitTask borderTask;
    private long matchStartTime;
    private BossBar borderBar;
    private Scoreboard gameScoreboard;
    private Map<Player, Scoreboard> originalScoreboards = new HashMap<>(); // Lưu scoreboard cũ của mỗi người chơi
    private File killDataFile;
    private BukkitTask autoSaveTask;

    public BattlegroundManager(Main plugin) {
        this.plugin = plugin;
        this.currentPhase = 0;
        this.killDataFile = new File(plugin.getDataFolder(), "kills.yml");
        // Create plugin folder if it doesn't exist
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        loadConfig();
        loadKillData(); // Load kill data when initializing
        scheduleStartCheck();
        setupScoreboard();
        startAutoSave(); // Start periodic auto-save
    }

    private void loadConfig() {
        try {            lobbyLocation = parseConfigLocation("lobby-location");
            mapCenter = parseConfigLocation("map-center");
            matchDuration = plugin.getConfig().getInt("match-duration", 1800);
            invincibilityDuration = plugin.getConfig().getInt("invincibility-duration", 60);
            borderInitialSize = plugin.getConfig().getDouble("border-initial-size", 200.0);
            borderFinalSize = plugin.getConfig().getDouble("border-final-size", 10.0);
            minPlayers = plugin.getConfig().getInt("min-players", 1);
            if (borderInitialSize <= 0) {
                borderInitialSize = 200.0;
                plugin.getLogger().warning("Invalid border-initial-size in config, defaulting to 200");
            }
            plugin.getLogger().info("Loaded config: border-initial-size=" + borderInitialSize + " (area: " + borderInitialSize + "x" + borderInitialSize + "), border-final-size=" + borderFinalSize + ", min-players=" + minPlayers);
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading config: " + e.getMessage());
            e.printStackTrace();
            borderInitialSize = 200.0; // Giá trị mặc định nếu lỗi
        }
    }    private Location parseConfigLocation(String path) {
        String worldName = plugin.getConfig().getString(path + ".world", "newbox");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World '" + worldName + "' not found for " + path);
            world = Bukkit.getWorlds().get(0); // Fallback to default world
        }
        return new Location(
            world,
            plugin.getConfig().getDouble(path + ".x", 0),
            plugin.getConfig().getDouble(path + ".y", 100),
            plugin.getConfig().getDouble(path + ".z", 0)
        );
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
        if (!isRunning && !participants.contains(player)) {
            participants.add(player);            // Add player to global region using command
            String addCommand = "rg addmember -w newbox __global__ " + player.getName();
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), addCommand);
            plugin.getLogger().info("Executing command: " + addCommand + " (success: " + success + ")");
            
            player.sendMessage(ChatColor.GREEN + "Bạn đã tham gia Battleground!");
            Bukkit.broadcastMessage(ChatColor.YELLOW + "Hiện có " + participants.size() + "/" + minPlayers + " người tham gia.");
            plugin.getLogger().info("Player " + player.getName() + " registered. Participants: " + participants.size());
        } else {
            player.sendMessage(ChatColor.RED + "Bạn đã đăng ký hoặc trận đã bắt đầu!");
        }
    }    public void unregisterPlayer(Player player) {
        if (participants.remove(player)) {            // Remove player from global region using command
            String removeCommand = "rg removemember -w newbox __global__ " + player.getName();
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), removeCommand);
            plugin.getLogger().info("Executing command: " + removeCommand + " (success: " + success + ")");
              // Reset player state
            player.setGameMode(GameMode.SURVIVAL);
            resetPlayerScoreboard(player);
            player.teleport(Bukkit.getWorld("world").getSpawnLocation());
            player.sendMessage(ChatColor.YELLOW + "Bạn đã rời khỏi Battleground!");
            plugin.getLogger().info("Player " + player.getName() + " unregistered and reset to survival mode");
        }
    }

    public void setStartTime(long seconds) {
        startTime = System.currentTimeMillis() / 1000 + seconds;
        Bukkit.broadcastMessage(ChatColor.YELLOW + "Battleground sẽ bắt đầu sau " + seconds + " giây!");
        plugin.getLogger().info("Set start time to " + seconds + " seconds from now");
    }

    public void setMatchDuration(int seconds) {
        this.matchDuration = seconds;
        plugin.getLogger().info("Match duration set to: " + seconds + " seconds");
    }

    public void start() {
        if (isRunning) {
            Bukkit.broadcastMessage(ChatColor.RED + "Trận đã bắt đầu!");
            plugin.getLogger().warning("Start called while match is already running!");
            return;
        }
        if (participants.size() < minPlayers) {
            Bukkit.broadcastMessage(ChatColor.RED + "Cần ít nhất " + minPlayers + " người để bắt đầu! Hiện có: " + participants.size());
            return;
        }        isRunning = true;
        startTime = -1;
        kills.clear(); // Reset kills for new match
        matchStartTime = System.currentTimeMillis() / 1000; // Set match start time

        String participantNames = participants.stream().map(Player::getName).collect(Collectors.joining(", "));
        plugin.getLogger().info("Starting Battleground with " + participants.size() + " participants: " + participantNames);

        if (participants.size() == 1) {
            Player winner = participants.get(0);
            Bukkit.broadcastMessage(ChatColor.GOLD + winner.getName() + " đã thắng Battleground vì là người duy nhất tham gia!");
            stop();
            return;
        }

        for (Player p : participants) {
            if (p.isOnline()) {
                p.teleport(lobbyLocation);
                p.sendMessage(ChatColor.YELLOW + "Chờ đếm ngược để bắt đầu!");
            }
        }        // Setup scoreboard right away
        setupScoreboard();
        updateScoreboard();

        if (countdownTask != null && !countdownTask.isCancelled()) {
            countdownTask.cancel();
            plugin.getLogger().info("Cancelled existing countdownTask before starting new one");
        }

        countdownTask = new BukkitRunnable() {
            int countdown = 10;

            @Override
            public void run() {
                if (countdown > 0) {
                    Bukkit.broadcastMessage(ChatColor.YELLOW + "Trận bắt đầu sau " + countdown + " giây!");
                    countdown--;
                    // Update scoreboard countdown
                    updateScoreboard();
                } else {
                    // Thêm effect slow falling khi countdown kết thúc
                    for (Player p : participants) {
                        if (p.isOnline()) {
                            // Thêm effect slow falling level 1 trong 30 giây (20 ticks * 30 = 600 ticks)
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 600, 0));
                        }
                    }
                    startMatch();
                    cancel();
                    plugin.getLogger().info("Countdown finished, match started");
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    public void setInitialBorderSize(double size) {
        this.borderInitialSize = size;
        plugin.getLogger().info("Initial border size set to: " + size);
    }

    private void startMatch() {
        if (!isRunning) {
            plugin.getLogger().warning("startMatch called while match is not running, ignoring");
            return;
        }        // Setup border
        World world = mapCenter.getWorld();
        border = world.getWorldBorder();
        border.reset();
        border.setCenter(mapCenter);
        border.setSize(borderInitialSize);
        border.setDamageAmount(7.0);    // Tăng sát thương lên 2 hearts/giây
        border.setDamageBuffer(0.0);    // Không có buffer, gây sát thương ngay khi ra khỏi border
        border.setWarningDistance(1);    // Giữ nguyên
        border.setWarningTime(0);        // Giữ nguyên
        plugin.getLogger().info("Border set to initial size: " + borderInitialSize);
        
        // Create and show border effects
        createBorderBar();
        updateBorderBar();
        showBorderParticles();        // Set up scoreboard and start update timer for scoreboard and bossbar
        setupScoreboard();
        updateScoreboard();
        createBorderBar();
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
        }.runTaskTimer(plugin, 20L, 20L); // Update every second

        // Game start announcement
        String playerList = participants.stream()
            .map(Player::getName)
            .collect(Collectors.joining(", "));
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Main title
            p.sendTitle(
                ChatColor.GOLD + "⚔ BATTLEGROUND ⚔",
                ChatColor.YELLOW + "Trận đấu bắt đầu!",
                20, 100, 20
            );
            
            // Information messages
            if (participants.contains(p)) {
                p.sendMessage("");
                p.sendMessage(ChatColor.GOLD + "✦ BATTLEGROUND ✦");
                p.sendMessage(ChatColor.WHITE + "• Người chơi: " + ChatColor.YELLOW + playerList);
                p.sendMessage(ChatColor.WHITE + "• Border: " + ChatColor.YELLOW + String.format("%.1f", borderInitialSize) + " blocks");
                p.sendMessage(ChatColor.WHITE + "• Thời gian: " + ChatColor.YELLOW + formatTime(matchDuration));
                p.sendMessage(ChatColor.WHITE + "• Bất tử: " + ChatColor.GREEN + invincibilityDuration + " giây");
                p.sendMessage("");
            }
        }

        // Teleport và thiết lập người chơi
        for (Player p : participants) {
                // Xóa dòng thêm effect vì đã chuyển lên trên
                p.teleport(mapCenter.clone().add(0, 10, 0));
                p.setInvulnerable(true);
                p.sendMessage(ChatColor.GREEN + "Bạn đã vào trận Battleground!");
                p.sendMessage(ChatColor.YELLOW + "Border ban đầu: " + String.format("%.1f", borderInitialSize) + " blocks");
                plugin.getLogger().info("Teleported " + p.getName() + " to match start location");
        }
        

        // Hết thời gian bất tử
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : participants) {
                    if (p.isOnline()) {
                        p.setInvulnerable(false);
                        p.sendMessage(ChatColor.RED + "Bất tử đã hết, trận đấu bắt đầu!");
                    }
                }
            }
        }.runTaskLater(plugin, invincibilityDuration * 20L);

        // Thu nhỏ border
        // Tính toán thời gian cho các giai đoạn (tính bằng ticks)
        long mainPhases = 4; // 80% thời gian đầu chia làm 4 giai đoạn
        long mainTimePerPhase = (matchDuration * 20L) / mainPhases; // Chuyển seconds thành ticks (20 ticks = 1 second)
  

        // Giai đoạn chính (80% đầu)
        new BukkitRunnable() {
            int phase = 0;
            double currentSize = borderInitialSize;

            @Override
            public void run() {                phase++;
                if (phase <= mainPhases) {
                    currentSize = borderInitialSize - ((borderInitialSize * 0.2) * phase);
                    border.setSize(currentSize, 40L);
                    currentPhase = phase;
                    
                    String message = ChatColor.GOLD + "Border đang thu nhỏ!\n" +
                            ChatColor.WHITE + "• Kích thước mới: " + String.format("%.1f", currentSize) + "\n" +
                            ChatColor.WHITE + "• Còn " + (mainPhases - phase) + " lần thu nhỏ chính";
                    
                    // Send title to all players
                    for (Player p : participants) {
                        if (p.isOnline()) {
                            p.sendTitle(
                                ChatColor.RED + "⚠ Border thu nhỏ!",
                                ChatColor.YELLOW + "Kích thước mới: " + String.format("%.1f", currentSize),
                                10, 60, 20
                            );
                        }
                    }
                    
                    Bukkit.broadcastMessage(message);
                    
                    // Update particles and boss bar
                    showBorderParticles();
                    createBorderBar();
                    updateBorderBar();
                    plugin.getLogger().info("Phase " + phase + "/" + mainPhases + 
                            ": size=" + String.format("%.1f", currentSize));

                    if (phase == mainPhases) {
                        cancel();
                        startFinalPhases(currentSize);
                    }
                }
            }
        }.runTaskTimer(plugin, mainTimePerPhase, mainTimePerPhase);

        // Kiểm tra người thắng
        new BukkitRunnable() {
            @Override
            public void run() {
                // Đếm số người chơi còn sống (không ở chế độ spectator)
                int alivePlayers = 0;
                Player lastAlivePlayer = null;
                
                for (Player p : participants) {
                    if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                        alivePlayers++;
                        lastAlivePlayer = p;
                    }
                }

                // Chỉ kết thúc khi còn 1 người sống
                if (alivePlayers == 1 && lastAlivePlayer != null) {
                    Bukkit.broadcastMessage(ChatColor.GOLD + lastAlivePlayer.getName() + " đã thắng Battleground!");
                    stop();
                    cancel();
                } 
                // Hoặc khi không còn ai sống
                else if (alivePlayers == 0) {
                    Bukkit.broadcastMessage(ChatColor.RED + "Trận đấu kết thúc - Không có người thắng cuộc!");
                    stop();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void startFinalPhases(double startSize) {
        long timePerPhase = 40L; // 2 seconds
        
        new BukkitRunnable() {
            int phase = 0;
            double currentSize = startSize;
            double shrinkAmount = startSize / 4;

            @Override
            public void run() {
                phase++;
                if (phase <= 4) {
                    currentSize = startSize - (shrinkAmount * phase);
                    if (currentSize < 0) currentSize = 0;
                    
                    border.setSize(currentSize, timePerPhase);
                    
                    String message = ChatColor.RED + "CẢNH BÁO: Giai đoạn cuối!\n" +
                            ChatColor.WHITE + "• Kích thước mới: " + String.format("%.1f", currentSize) + "\n" +
                            ChatColor.WHITE + "• Còn " + (4 - phase) + " lần thu nhỏ\n" +
                            ChatColor.WHITE + "• Border sắp biến mất!";
                    
                    Bukkit.broadcastMessage(message);
                    
                    if (phase == 4) {
                        // Xóa border và bắt đầu gây sát thương toàn map
                        border.reset();
                        border = null;
                        startGlobalDamage();
                        Bukkit.broadcastMessage(ChatColor.RED + "CẢNH BÁO: Border đã biến mất!\n" + 
                                          ChatColor.RED + "Toàn bộ khu vực sẽ gây sát thương!");
                        cancel();
                    }
                }
            }
        }.runTaskTimer(plugin, timePerPhase, timePerPhase);
    }

    // Thêm phương thức mới để gây sát thương toàn map
    private void startGlobalDamage() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    cancel();
                    return;
                }

                for (Player p : participants) {
                    if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                        p.damage(150.0); // Sát thương player chịu khi border biến mất hoàn toàn
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 20L); // Gây sát thương mỗi giây

        Bukkit.broadcastMessage(ChatColor.RED+"Cảnh Báo : Toàn bộ vòng bo đã biến mất!");
    }

    public void stop() {
        if (!isRunning) {
            plugin.getLogger().warning("Stop called while match is not running!");
            return;
        }        // Show end game stats
        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(ChatColor.GOLD + "✦ KẾT QUẢ TRẬN ĐẤU ✦");
        Bukkit.broadcastMessage("");

        // Find winner if exists
        Player winner = null;
        for (Player p : participants) {
            if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                winner = p;
                break;
            }
        }

        // Show winner
        if (winner != null) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "⚔ NGƯỜI THẮNG CUỘC ⚔");
            Bukkit.broadcastMessage(ChatColor.YELLOW + "► " + winner.getName());
            Bukkit.broadcastMessage("");

            // Show winner title to all
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle(
                    ChatColor.GOLD + "⚔ " + winner.getName() + " ⚔",
                    ChatColor.YELLOW + "Đã chiến thắng!",
                    20, 100, 20
                );
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
        }        // Show match kill stats
        if (!kills.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.RED + "☠ TOP KILLS TRẬN NÀY ☠");
            // Tạo một bản sao của kills map để không ảnh hưởng đến dữ liệu gốc
            Map<Player, Integer> matchKills = new HashMap<>(kills);
            List<Map.Entry<Player, Integer>> matchKillers = new ArrayList<>(matchKills.entrySet());
            matchKillers.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            
            int rank = 1;
            for (Map.Entry<Player, Integer> entry : matchKillers) {
                String prefix = rank == 1 ? "★" : rank == 2 ? "☆" : "✮";
                Bukkit.broadcastMessage(ChatColor.YELLOW + prefix + " " + 
                    ChatColor.WHITE + entry.getKey().getName() + ": " + 
                    ChatColor.GREEN + entry.getValue() + " kills trong trận này");
                
                if (rank >= 3) break;
                rank++;
            }
            // Clear match kills sau khi hiển thị stats
            kills.clear();
        }
        
        Bukkit.broadcastMessage("");

        // Reset border
        if (border != null) {
            border.reset();
            border = null;
        }
        
        // Remove BossBar
        if (borderBar != null) {
            borderBar.removeAll();
            borderBar = null;
        }
        
        // Reset all tasks
        for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
            if (task.getOwner().equals(plugin)) {
                task.cancel();
            }
        }
          // Auto-leave all players and remove region access
        List<Player> playersToRemove = new ArrayList<>(participants);
        for (Player p : playersToRemove) {
            if (p.isOnline()) {
                p.setGameMode(GameMode.SURVIVAL);
                resetPlayerScoreboard(p); // Reset scoreboard before unregistering
                unregisterPlayer(p); // This will also remove them from the region
                p.sendMessage(ChatColor.GREEN + "Trận đấu đã kết thúc! Bạn đã được đưa về trạng thái bình thường.");
            }
        }        // Reset scoreboard objective
        if (gameScoreboard.getObjective("bginfo") != null) {
            gameScoreboard.getObjective("bginfo").unregister();
        }

        // Khôi phục scoreboard gốc cho tất cả người chơi
        for (Map.Entry<Player, Scoreboard> entry : new HashMap<>(originalScoreboards).entrySet()) {
            Player player = entry.getKey();
            if (player.isOnline()) {
                resetPlayerScoreboard(player);
            }
        }
        originalScoreboards.clear();        // Reset states
        participants.clear();
        isRunning = false;
        startTime = -1;
        currentPhase = 0; // Reset phase counter

        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }

        Bukkit.broadcastMessage(ChatColor.GOLD + "Battleground đã kết thúc!");
        plugin.getLogger().info("Match stopped and all players reset to survival mode");
    }

    public void saveKillData() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            
            // Save total kills for each player
            for (Map.Entry<Player, Integer> entry : totalKills.entrySet()) {
                String uuid = entry.getKey().getUniqueId().toString();
                String playerName = entry.getKey().getName();
                config.set("kills." + uuid + ".name", playerName);
                config.set("kills." + uuid + ".total", entry.getValue());
            }
            
            // Create parent directories if they don't exist
            killDataFile.getParentFile().mkdirs();
            
            // Save the configuration
            config.save(killDataFile);
            plugin.getLogger().info("Successfully saved kill data to " + killDataFile.getName());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save kill data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadKillData() {
        if (!killDataFile.exists()) {
            plugin.getLogger().info("No kill data file found. Starting with empty kill statistics.");
            return;
        }

        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(killDataFile);
            ConfigurationSection killsSection = config.getConfigurationSection("kills");
            
            if (killsSection == null) {
                plugin.getLogger().info("No kill data found in file. Starting with empty kill statistics.");
                return;
            }

            totalKills.clear(); // Clear existing data before loading
            for (String uuid : killsSection.getKeys(false)) {
                try {
                    UUID playerUUID = UUID.fromString(uuid);
                    Player player = Bukkit.getPlayer(playerUUID);
                    if (player != null && player.isOnline()) {
                        int total = killsSection.getInt(uuid + ".total");
                        totalKills.put(player, total);
                        plugin.getLogger().info("Loaded " + total + " kills for player " + player.getName());
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID in kill data file: " + uuid);
                }
            }
            plugin.getLogger().info("Successfully loaded kill data for " + totalKills.size() + " players");
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading kill data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void forceResetBorder() {
        // Reset border cho tất cả các world
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

        // Reset border hiện tại
        border.reset();
        border.setCenter(mapCenter);
        // Set border về 0 ngay lập tức
        border.setSize(0, 1L);

        // Thông báo
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
                plugin.getLogger().warning("Border size mismatch in setBorderSize! Expected: " + size + ", Actual: " + actualSize);
            }
        }
    }    private void setupScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        gameScoreboard = manager.getNewScoreboard();
        // Reset any existing objective
        if (gameScoreboard.getObjective("bginfo") != null) {
            gameScoreboard.getObjective("bginfo").unregister();
        }
        // Create new objective
        Objective objective = gameScoreboard.registerNewObjective("bginfo", Criteria.DUMMY, ChatColor.GOLD + "♦ Battleground ♦");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        // Lưu và thiết lập scoreboard cho tất cả người chơi tham gia
        for (Player p : participants) {
            // Lưu scoreboard hiện tại của người chơi nếu chưa từng lưu (chỉ lưu lần đầu khi bắt đầu BG)
            if (!originalScoreboards.containsKey(p)) {
                Scoreboard current = p.getScoreboard();
                // Đảm bảo không lưu board BG (tránh trường hợp đã bị set trước đó)
                if (current != null && current != gameScoreboard) {
                    originalScoreboards.put(p, current);
                } else {
                    // Nếu board hiện tại là null hoặc là board BG, lưu main scoreboard
                    originalScoreboards.put(p, Bukkit.getScoreboardManager().getMainScoreboard());
                }
            }
            // Đặt scoreboard BG cho người chơi
            p.setScoreboard(gameScoreboard);
        }
    }    private void resetPlayerScoreboard(Player player) {
    // Khôi phục scoreboard ban đầu
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
                plugin.getLogger().warning("Failed to execute first /board for player " + player.getName() + ": " + e.getMessage());
            }
        }
    }.runTaskLater(plugin, 10L); // Chạy sau 0.5 giây

    new BukkitRunnable() {
        @Override
        public void run() {
            try {
                player.performCommand("board");
                plugin.getLogger().info("Executed second /board for player: " + player.getName());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to execute second /board for player " + player.getName() + ": " + e.getMessage());
            }
        }
    }.runTaskLater(plugin, 20L); // Chạy sau 1 giây
}

    private void updateScoreboard() {
        if (!isRunning) return;

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
        // Display time remaining if match is in progress
        if (isRunning) {
            long timeLeft = matchStartTime + matchDuration - (System.currentTimeMillis() / 1000);
            if (timeLeft > 0) {
                objective.getScore(ChatColor.WHITE + "⌚ Time: " + ChatColor.YELLOW + formatTime(timeLeft)).setScore(score--);
                objective.getScore("").setScore(score--);
            }

            // Display player stats
            objective.getScore(ChatColor.WHITE + "⚔ Players:").setScore(score--);
            objective.getScore(ChatColor.GREEN + " ▸ Alive: " + alive).setScore(score--);
            objective.getScore(ChatColor.RED + " ▸ Dead: " + (participants.size() - alive)).setScore(score--);
            objective.getScore(" ").setScore(score--);

            // Display kills
            for (Player p : participants) {
                if (kills.containsKey(p)) {
                    objective.getScore(ChatColor.YELLOW + p.getName() + ": " + kills.get(p) + " kills").setScore(score--);
                }
            }
            objective.getScore("  ").setScore(score--);
            
            // Display border info if active
            if (border != null) {
                objective.getScore(ChatColor.YELLOW + "⭕ Border: " + String.format("%.1f", border.getSize())).setScore(score--);
                objective.getScore(ChatColor.YELLOW + " ▸ Phase: " + currentPhase + "/4").setScore(score--);
                objective.getScore("   ").setScore(score--);
            }
        }

        // Set updated scoreboard for all participants
        for (Player p : participants) {
            if (p.isOnline()) {
                p.setScoreboard(gameScoreboard);
            }
        }
    }

    public String getStatus() {
        if (!isRunning) {
            return ChatColor.RED + "Không có trận đấu nào đang diễn ra!";
        }

        StringBuilder status = new StringBuilder();
        status.append(ChatColor.GOLD + "✦ Trạng thái BattleGround ✦\n");
        
        // Đếm người còn sống
        int alivePlayers = 0;
        List<String> alivePlayerNames = new ArrayList<>();
        for (Player p : participants) {
            if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                alivePlayers++;
                alivePlayerNames.add(p.getName());
            }
        }
        
        status.append(ChatColor.WHITE + "• Người còn sống: " + ChatColor.GREEN + alivePlayers + "/" + participants.size() + "\n");
        if (!alivePlayerNames.isEmpty()) {
            status.append(ChatColor.GRAY + "  ↳ " + String.join(", ", alivePlayerNames) + "\n");
        }
        
        if (border != null) {
            status.append(ChatColor.WHITE + "• Kích thước Border: " + ChatColor.YELLOW + String.format("%.1f", border.getSize()) + "\n");
            status.append(ChatColor.WHITE + "• Giai đoạn: " + ChatColor.YELLOW + currentPhase + "/4\n");
        }
        
        long timeLeft = matchStartTime + matchDuration - (System.currentTimeMillis() / 1000);
        if (timeLeft > 0) {
            status.append(ChatColor.WHITE + "• Thời gian còn lại: " + ChatColor.YELLOW + formatTime(timeLeft));
        }
        
        return status.toString();
    }

    public String getPlayerList() {
        if (participants.isEmpty()) {
            return ChatColor.RED + "Chưa có người chơi nào tham gia!";
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

    private String formatTime(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    public boolean toggleBorderPause() {
        isPaused = !isPaused;
        if (isPaused && border != null) {
            if (borderTask != null) borderTask.cancel();
            return true;
        } else {
            return false;
        }
    }

    private void createBorderBar() {
        if (borderBar != null) {
            borderBar.removeAll();
        }
        borderBar = Bukkit.createBossBar(
            ChatColor.GOLD + "Border: " + String.format("%.1f", border.getSize()),
            BarColor.YELLOW,
            BarStyle.SOLID
        );
        for (Player p : participants) {
            borderBar.addPlayer(p);
        }
    }    private void updateBorderBar() {
        if (borderBar != null && border != null) {
            borderBar.setTitle(ChatColor.GOLD + "Border: " + String.format("%.1f", border.getSize()) + 
                             ChatColor.YELLOW + " | Phase: " + currentPhase + "/4");
            // Set progress based on current size vs initial size
            double progress = border.getSize() / borderInitialSize;
            borderBar.setProgress(Math.max(0, Math.min(1, progress)));
        }
    }private void showBorderParticles() {
        if (border == null) return;
        
        double size = border.getSize() / 2;
        Location center = border.getCenter();
        World world = center.getWorld();
        
        // Show particles along border edges
        for (double x = -size; x <= size; x += 2) {
            spawnBorderParticle(world, center.clone().add(x, 0, -size));
            spawnBorderParticle(world, center.clone().add(x, 0, size));
        }
        
        for (double z = -size; z <= size; z += 2) {
            spawnBorderParticle(world, center.clone().add(-size, 0, z));
            spawnBorderParticle(world, center.clone().add(size, 0, z));
        }
    }

    private void spawnBorderParticle(World world, Location loc) {
        for (double y = 0; y < 256; y += 16) {
            world.spawnParticle(Particle.END_ROD, loc.clone().add(0, y, 0), 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.DRAGON_BREATH, loc.clone().add(0, y, 0), 1, 0, 0, 0, 0);
        }
    }    public void addKill(Player killer) {
        if (!isRunning) return; // Chỉ tính kill khi trận đấu đang diễn ra
        
        // Tăng số kill trong trận hiện tại
        kills.merge(killer, 1, Integer::sum);
        // Tăng tổng số kill (cho bảng xếp hạng tổng)
        totalKills.merge(killer, 1, Integer::sum);
        
        // Thông báo kill cho người chơi
        killer.sendMessage(ChatColor.GREEN + "+1 Kill! " + ChatColor.YELLOW + "Số kill trong trận này: " + kills.get(killer));
        
        updateScoreboard();
        
        // Give killer full health as a reward
        killer.setHealth(20.0); // 20.0 is the default max health in Minecraft
        
        // Optional: Give killer some rewards like potion effects
        killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 0)); // Speed I for 10 seconds
        killer.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 0)); // Regen I for 5 seconds
    }

    // Thêm phương thức mới để hiển thị tổng số kill của tất cả các trận
    public String getTopTotalKills() {
        if (totalKills.isEmpty()) {
            return ChatColor.RED + "Chưa có thống kê kills nào!";
        }

        StringBuilder status = new StringBuilder();
        status.append(ChatColor.GOLD + "✦ TOP KILLS TỔNG ✦\n");
        
        List<Map.Entry<Player, Integer>> topKillers = new ArrayList<>(totalKills.entrySet());
        topKillers.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        int rank = 1;
        for (Map.Entry<Player, Integer> entry : topKillers) {
            String prefix = rank == 1 ? "★" : rank == 2 ? "☆" : "✮";
            status.append(ChatColor.YELLOW + prefix + " " + 
                         ChatColor.WHITE + entry.getKey().getName() + ": " + 
                         ChatColor.GREEN + entry.getValue() + " kills\n");
            if (rank >= 10) break; // Chỉ hiển thị top 10
            rank++;
        }
        
        return status.toString();
    }

    private void startAutoSave() {
        // Auto-save every 5 minutes (5 * 60 * 20 = 6000 ticks)
        autoSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveKillData();
                plugin.getLogger().info("Auto-saved kill data");
            }
        }.runTaskTimer(plugin, 6000L, 6000L);
    }

    public void cleanup() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        saveKillData(); // Save one last time before cleanup
    }
}