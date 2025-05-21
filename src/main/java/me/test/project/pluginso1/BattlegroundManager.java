package me.test.project.pluginso1;

import org.bukkit.*;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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

    public BattlegroundManager(Main plugin) {
        this.plugin = plugin;
        this.currentPhase = 0;
        this.killDataFile = new File(plugin.getDataFolder(), "kills.yml");
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        loadConfig();
        loadKillData();
        scheduleStartCheck();
        setupScoreboard();
        startAutoSave();
    }

    private void loadConfig() {
        try {
            lobbyLocation = parseConfigLocation("lobby-location");
            mapCenter = parseConfigLocation("map-center");
            matchDuration = plugin.getConfig().getInt("match-duration", 600);
            invincibilityDuration = plugin.getConfig().getInt("invincibility-duration", 30);
            borderInitialSize = plugin.getConfig().getDouble("border-initial-size", 500.0);
            borderFinalSize = plugin.getConfig().getDouble("border-final-size", 5.0);
            minPlayers = plugin.getConfig().getInt("min-players", 2);
            numberOfPhases = plugin.getConfig().getInt("number-of-phases", 6); // Tăng lên 6 phase
            borderShrinkFactor = plugin.getConfig().getDouble("border-shrink-factor", 0.85); // Giữ nguyên để tương
                                                                                             // thích
            borderCenterOffset = plugin.getConfig().getDouble("border-random-offset", 50.0);
            finalPhaseDuration = plugin.getConfig().getInt("final-phase-duration", 120); // 120 giây cho phase cuối

            if (borderInitialSize <= 0) {
                borderInitialSize = 500.0;
                plugin.getLogger().warning("Invalid border-initial-size in config, defaulting to 500");
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
                            ", final-phase-duration=" + finalPhaseDuration);
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading config: " + e.getMessage());
            e.printStackTrace();
            borderInitialSize = 500.0;
            borderFinalSize = 5.0;
            numberOfPhases = 6;
            borderShrinkFactor = 0.85;
            finalPhaseDuration = 120;
        }
    }

    private Location parseConfigLocation(String path) {
        String worldName = plugin.getConfig().getString(path + ".world", "lobby");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World '" + worldName + "' not found for " + path);
            world = Bukkit.getWorlds().get(0);
        }
        return new Location(
                world,
                plugin.getConfig().getDouble(path + ".x", 0),
                plugin.getConfig().getDouble(path + ".y", 100),
                plugin.getConfig().getDouble(path + ".z", 0));
    }

    private void scheduleStartCheck() {
        startCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isRunning && !isCountingDown && startTime > 0 && System.currentTimeMillis() / 1000 >= startTime) {
                    plugin.getLogger().info("Start check triggered, calling start()");
                    start();
                    startTime = -1;
                    cancel(); // Hủy task ngay sau khi gọi start()
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
            String addCommand = "rg addmember -w lobby __global__ " + player.getName();
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), addCommand);
            plugin.getLogger().info("Executing command: " + addCommand + " (success: " + success + ")");

            player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                    + ChatColor.GREEN + "Bạn đã tham gia Battleground!");
            Bukkit.broadcastMessage(
                    ChatColor.YELLOW + "Hiện có " + participants.size() + "/" + minPlayers + " người tham gia.");
            plugin.getLogger().info("Player " + player.getName() + " registered. Participants: " + participants.size());
        } else {
            player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                    + ChatColor.RED + "Bạn đã đăng ký hoặc trận đã bắt đầu!");
        }
    }

    public void unregisterPlayer(Player player) {
        if (participants.remove(player)) {
            String removeCommand = "rg removemember -w lobby __global__ " + player.getName();
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), removeCommand);
            plugin.getLogger().info("Executing command: " + removeCommand + " (success: " + success + ")");
            player.setGameMode(GameMode.SURVIVAL);
            resetPlayerScoreboard(player);
            player.teleport(Bukkit.getWorld("world").getSpawnLocation());
            player.sendMessage(ChatColor.YELLOW + "Bạn đã rời khỏi Battleground!");
            plugin.getLogger().info("Player " + player.getName() + " unregistered and reset to survival mode");
            // Execute /board command twice to refresh scoreboard
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
            }.runTaskLater(plugin, 10L);

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
            }.runTaskLater(plugin, 20L);
        }
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
        if (isRunning || isCountingDown) {
            Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                    + ChatColor.RED + "Trận đã bắt đầu hoặc đang đếm ngược!");
            plugin.getLogger().warning("Start called while match is running or counting down!");
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
        isJoinOpen = false; // Close joining when match starts
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
                p.teleport(lobbyLocation);
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
            int countdown = 30;

            @Override
            public void run() {
                if (!isRunning) {
                    plugin.getLogger().info("Countdown cancelled due to match not running");
                    cancel();
                    return;
                }
                if (countdown > 10) {
                    if (countdown % 10 == 0) { // Thông báo mỗi 10 giây
                        Bukkit.broadcastMessage(
                                ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY
                                        + "] " + ChatColor.YELLOW + "Trận bắt đầu sau " + countdown + " giây!");
                        updateCountdownScoreboard(countdown);
                    }
                } else if (countdown > 0) { // Đếm ngược từng giây khi còn 10 giây
                    Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY
                            + "] " + ChatColor.YELLOW + "Trận bắt đầu sau " + countdown + " giây!");
                    updateCountdownScoreboard(countdown);
                } else { // Khi countdown = 0
                    isCountingDown = false;
                    startMatch();
                    cancel();
                    plugin.getLogger().info("Countdown finished, match started");
                }
                countdown--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void updateCountdownScoreboard(int countdown) {
        Objective objective = gameScoreboard.getObjective("bginfo");
        if (objective != null) {
            objective.unregister();
        }
        objective = gameScoreboard.registerNewObjective("bginfo", Criteria.DUMMY, ChatColor.GOLD + "♦ Battleground ♦");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = 15;
        objective.getScore(ChatColor.YELLOW + "Bắt đầu trong: " + formatTime(countdown)).setScore(score--);
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
        if (!isRunning || isCountingDown) {
            plugin.getLogger().warning("startMatch called while match is not running or still counting down, ignoring");
            return;
        }
        plugin.getLogger().info("Starting match with " + participants.size() + " players");

        matchStartTime = System.currentTimeMillis() / 1000;
        World world = mapCenter.getWorld();
        border = world.getWorldBorder();
        border.reset();

        // Calculate random border center at match start
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
        border.setWarningDistance(5); // Increased for better visibility
        border.setWarningTime(15); // Warning time in seconds
        plugin.getLogger()
                .info("Border set to initial size: " + borderInitialSize + ", center: " + borderCenter.toVector());

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

        String playerList = participants.stream()
                .map(Player::getName)
                .collect(Collectors.joining(", "));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(
                    ChatColor.GOLD + "⚔ BATTLEGROUND ⚔",
                    ChatColor.YELLOW + "Trận đấu bắt đầu!",
                    20, 100, 20);

            if (participants.contains(p)) {
                p.sendMessage("");
                p.sendMessage(ChatColor.GOLD + "✦ BATTLEGROUND ✦");
                p.sendMessage(ChatColor.WHITE + "• Người chơi: " + ChatColor.YELLOW + playerList);
                p.sendMessage(ChatColor.WHITE + "• Border: " + ChatColor.YELLOW
                        + String.format("%.1f", borderInitialSize) + " blocks");
                p.sendMessage(ChatColor.WHITE + "• Tâm vòng bo: " + ChatColor.YELLOW
                        + String.format("x=%.1f, z=%.1f", borderCenter.getX(), borderCenter.getZ()));
                p.sendMessage(ChatColor.WHITE + "• Thời gian: " + ChatColor.YELLOW + formatTime(matchDuration));
                p.sendMessage(ChatColor.WHITE + "• Bất tử: " + ChatColor.GREEN + invincibilityDuration + " giây");
                p.sendMessage("");
            }
        }

        for (Player p : participants) {
            if (p.isOnline()) {
                p.teleport(borderCenter.clone().add(0, 10, 0)); // Teleport to randomized border center
                p.setInvulnerable(true);
                p.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                        + ChatColor.GREEN + "Bạn đã vào trận Battleground!");
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 16 * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 6 * 20, 4));
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 8 * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 6 * 20, 4));
                plugin.getLogger().info("Teleported " + p.getName() + " to match start location and applied effects");
            }
        }

        // Schedule end of invincibility period
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : participants) {
                    if (p.isOnline()) {
                        p.setInvulnerable(false);
                        p.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                                + ChatColor.RED + "Bất tử đã hết, trận đấu bắt đầu!");
                        p.sendTitle(
                                ChatColor.RED + "⚠ Thời gian bất tử đã hết ⚠",
                                ChatColor.RED + "Trận Chiến Bắt Đầu!",
                                10, 60, 20);
                    }
                }
            }
        }.runTaskLater(plugin, invincibilityDuration * 20L);

        // Tính toán thời gian cho mỗi phase (chia đều)
        long timePerPhase = (matchDuration * 20L) / numberOfPhases;

        // Quản lý các phase chính của border
        startBorderPhases(timePerPhase);

        // Kiểm tra người thắng cuộc liên tục
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

    private void startBorderPhases(long timePerPhase) {
        // Tính toán kích thước border cho mỗi phase
        double[] phaseSizes = new double[numberOfPhases + 1];
        phaseSizes[0] = borderInitialSize;

        // Phase 1: Rút 30%
        phaseSizes[1] = borderInitialSize * 0.7; // 500 * 0.7 = 350
        // Phase 2: Rút 20% của phase 1
        phaseSizes[2] = phaseSizes[1] * 0.8; // 350 * 0.8 = 280
        // Phase 3: Rút 20% của phase 2
        phaseSizes[3] = phaseSizes[2] * 0.8; // 280 * 0.8 = 224
        // Phase 4: Rút 20% của phase 3
        phaseSizes[4] = phaseSizes[3] * 0.8; // 224 * 0.8 = 179.2
        // Phase 5: Rút còn borderFinalSize (5 block)
        phaseSizes[5] = borderFinalSize; // 5 block
        // Phase 6: Giữ borderFinalSize
        phaseSizes[6] = borderFinalSize; // 5 block

        for (int i = 1; i <= numberOfPhases; i++) {
            plugin.getLogger().info("Phase " + i + " size calculated: " + phaseSizes[i]);
        }

        // Tạo task để quản lý các phase của border
        new BukkitRunnable() {
            int phase = 0;

            @Override
            public void run() {
                if (!isRunning) {
                    cancel();
                    return;
                }

                phase++;
                if (phase <= numberOfPhases) {
                    double newSize = phaseSizes[phase];
                    long shrinkTime = phase >= numberOfPhases - 1 ? 60L : 30L; // 60 giây cho phase 5 và 6, 30 giây cho
                                                                               // các phase trước

                    border.setSize(newSize, shrinkTime);
                    currentPhase = phase;

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

                    // Thông báo trước khi vào phase cuối (phase 6)
                    if (phase == numberOfPhases - 1) {
                        Bukkit.broadcastMessage(
                                ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                                        + ChatColor.RED + "CẢNH BÁO: Vòng bo cuối cùng sẽ bắt đầu sau "
                                        + formatTime(timePerPhase / 20) + "!");
                    }

                    // Khi đến phase cuối (phase 6), chuyển sang phase overtime
                    if (phase == numberOfPhases) {
                        startFinalPhase();
                        cancel(); // Dừng task này vì đã chuyển sang phase cuối
                    }
                }
            }
        }.runTaskTimer(plugin, timePerPhase, timePerPhase);
    }

    private void startFinalPhase() {
        Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                + ChatColor.RED + "CẢNH BÁO: Vòng bo cuối cùng!\n" +
                ChatColor.YELLOW + "Kích thước: " + String.format("%.1f", borderFinalSize) + " blocks\n" +
                ChatColor.YELLOW + "Tử chiến bắt đầu sau: " + formatTime(finalPhaseDuration));

        for (Player p : participants) {
            if (p.isOnline()) {
                p.sendTitle(
                        ChatColor.RED + "⚠ Vòng bo cuối cùng ⚠",
                        ChatColor.YELLOW + "Chuẩn bị tử chiến!",
                        10, 60, 20);
                p.sendMessage(ChatColor.YELLOW + "Tử chiến sau " + formatTime(finalPhaseDuration));
            }
        }

        showBorderParticles();
        updateBorderBar();

        // Tạo countdown cho phase cuối
        new BukkitRunnable() {
            int countdown = finalPhaseDuration;

            @Override
            public void run() {
                if (!isRunning) {
                    cancel();
                    return;
                }

                countdown--;

                // Thông báo thời gian còn lại theo khoảng thời gian
                if (countdown == 30 || countdown == 20 || countdown == 10 || (countdown > 0 && countdown <= 5)) {
                    Bukkit.broadcastMessage(
                            ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                                    + ChatColor.RED + "Tử chiến bắt đầu sau: " + formatTime(countdown));

                    for (Player p : participants) {
                        if (p.isOnline()) {
                            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
                        }
                    }
                }

                // Thông báo bổ sung khi còn 30 giây
                if (countdown == 30) {
                    Bukkit.broadcastMessage(
                            ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                                    + ChatColor.RED + "CẢNH BÁO: Toàn map sẽ gây sát thương sau 30 giây!");
                }

                // Khi hết thời gian, chuyển sang phase overtime
                if (countdown <= 0) {
                    startOvertimePhase();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void startOvertimePhase() {
        // Xóa border hiện tại
        border.reset();
        border = null;

        // Thông báo cho người chơi
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

        // Tạo task gây sát thương toàn map
        new BukkitRunnable() {
            int damagePhase = 0;

            @Override
            public void run() {
                if (!isRunning) {
                    cancel();
                    return;
                }

                damagePhase++;
                double damage = 1.0; // Sát thương cơ bản

                // Tăng sát thương theo thời gian
                if (damagePhase > 10) {
                    damage = 2.0;
                }
                if (damagePhase > 20) {
                    damage = 4.0;
                }
                if (damagePhase > 30) {
                    damage = 8.0;
                }

                // Gây sát thương cho tất cả người chơi
                for (Player p : participants) {
                    if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                        p.damage(damage);
                        // Hiệu ứng hình ảnh cho đẹp
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
                BarStyle.SEGMENTED_10); // Segmented for better visual
        for (Player p : participants) {
            borderBar.addPlayer(p);
        }
    }

    private void updateBorderBar() {
        if (borderBar != null && border != null) {
            long timeLeft = matchStartTime + matchDuration - (System.currentTimeMillis() / 1000);
            borderBar.setTitle(ChatColor.GOLD + "Vòng Bo: " + String.format("%.1f", border.getSize()) + " blocks" +
                    ChatColor.YELLOW + " | Phase: " + currentPhase + "/" + numberOfPhases +
                    ChatColor.GREEN + " | Time: " + formatTime(timeLeft));
            double progress = border.getSize() / borderInitialSize;
            borderBar.setProgress(Math.max(0, Math.min(1, progress)));
        }
    }

    private void showBorderParticles() {
        if (border == null)
            return;

        double size = border.getSize() / 2;
        Location center = border.getCenter();
        World world = center.getWorld();

        // Optimize particle spawning (reduce frequency)
        for (double x = -size; x <= size; x += 5) { // Increased step size to 5
            spawnBorderParticle(world, center.clone().add(x, 0, -size));
            spawnBorderParticle(world, center.clone().add(x, 0, size));
        }

        for (double z = -size; z <= size; z += 5) {
            spawnBorderParticle(world, center.clone().add(-size, 0, z));
            spawnBorderParticle(world, center.clone().add(size, 0, z));
        }
    }

    private void spawnBorderParticle(World world, Location loc) {
        // Spawn particles only at player height to reduce lag
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
        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(ChatColor.WHITE + "✦ KẾT QUẢ TRẬN ĐẤU BattleGround  ✦");
        Bukkit.broadcastMessage("");

        Player winner = null;
        for (Player p : participants) {
            if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                winner = p;
                break;
            }
        }

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
                        ChatColor.GREEN + entry.getValue() + " kill"
                        + ChatColor.WHITE + ")");

                if (rank >= 3)
                    break;
                rank++;
            }
            kills.clear();
        }

        Bukkit.broadcastMessage("");

        if (border != null) {
            border.reset();
            border = null;
        }

        if (borderBar != null) {
            borderBar.removeAll();
            borderBar = null;
        }

        // Hủy tất cả các task
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        if (startCheckTask != null) {
            startCheckTask.cancel();
            startCheckTask = null;
        }
        for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
            if (task.getOwner().equals(plugin)) {
                task.cancel();
            }
        }

        List<Player> playersToRemove = new ArrayList<>(participants);
        for (Player p : playersToRemove) {
            if (p.isOnline()) {
                p.setGameMode(GameMode.SURVIVAL);
                resetPlayerScoreboard(p);
                unregisterPlayer(p);
                p.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                        + ChatColor.GREEN + "Trận đấu đã kết thúc! Bạn đã được đưa về trạng thái bình thường.");
            }
        }
        if (gameScoreboard.getObjective("bginfo") != null) {
            gameScoreboard.getObjective("bginfo").unregister();
        }

        for (Map.Entry<Player, Scoreboard> entry : new HashMap<>(originalScoreboards).entrySet()) {
            Player player = entry.getKey();
            if (player.isOnline()) {
                resetPlayerScoreboard(player);
            }
        }
        originalScoreboards.clear();
        vanishedArmor.clear();
        participants.clear();
        isRunning = false;
        isCountingDown = false;
        isJoinOpen = false;
        startTime = -1;
        currentPhase = 0;

        Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                + ChatColor.GOLD + "Battleground đã kết thúc!");
        plugin.getLogger().info("Match stopped and all players reset to survival mode");
    }

    public void saveKillData() {
        try {
            YamlConfiguration config = new YamlConfiguration();

            for (Map.Entry<Player, Integer> entry : totalKills.entrySet()) {
                String uuid = entry.getKey().getUniqueId().toString();
                String playerName = entry.getKey().getName();
                config.set("kills." + uuid + ".name", playerName);
                config.set("kills." + uuid + ".total", entry.getValue());
            }

            killDataFile.getParentFile().mkdirs();
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

            totalKills.clear();
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
        if (timeLeft > 0) {
            objective.getScore(ChatColor.WHITE + "⌚ Thời gian còn lại: " + ChatColor.YELLOW + formatTime(timeLeft))
                    .setScore(score--);
            objective.getScore("").setScore(score--);
        }

        objective.getScore(ChatColor.WHITE + "⚔ Số người tham gia: " + participants.size()).setScore(score--);
        objective.getScore("");
        objective.getScore(ChatColor.GREEN + " ▸ Còn sống: " + alive).setScore(score--);
        objective.getScore(" ").setScore(score--);

        if (border != null) {
            objective
                    .getScore(
                            ChatColor.YELLOW + "⭕ Chiều rộng Bo: " + String.format("%.1f", border.getSize()) + " block")
                    .setScore(score--);
            objective.getScore(ChatColor.YELLOW + " ▸ Phase: " + currentPhase + "/" + numberOfPhases).setScore(score--);

            long timePerPhase = matchDuration / numberOfPhases;
            long elapsedTime = (System.currentTimeMillis() / 1000) - matchStartTime;
            long timeInCurrentPhase = elapsedTime % timePerPhase;
            long timeToNextPhase = timePerPhase - timeInCurrentPhase;

            if (currentPhase < numberOfPhases && timeToNextPhase > 0) {
                objective.getScore(ChatColor.YELLOW + " ▸ Đợt Bo kế tiếp: " + formatTime(timeToNextPhase))
                        .setScore(score--);
            } else if (currentPhase == numberOfPhases) {
                // Hiển thị thông tin phase overtime nếu đang ở phase cuối
                objective.getScore(ChatColor.RED + " ▸ Tử Chiến Sắp Diễn Ra!")
                        .setScore(score--);
            }
            objective.getScore("   ").setScore(score--);
        } else {
            // Nếu border đã reset (phase overtime)
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
        if (!isRunning)
            return;

        kills.merge(killer, 1, Integer::sum);
        totalKills.merge(killer, 1, Integer::sum);

        updateScoreboard();

        killer.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 6 * 20, 4));
    }

    public String getTopTotalKills() {
        if (totalKills.isEmpty()) {
            return ChatColor.RED + "Chưa có thống kê kills nào!";
        }

        StringBuilder status = new StringBuilder();
        status.append(ChatColor.GOLD + "✦ TOP KILLS BattleGround ✦\n");

        List<Map.Entry<Player, Integer>> topKillers = new ArrayList<>(totalKills.entrySet());
        topKillers.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        int rank = 1;
        for (Map.Entry<Player, Integer> entry : topKillers) {
            String prefix = rank == 1 ? "#1." : rank == 2 ? "#2." : "#3.";
            status.append(ChatColor.YELLOW + prefix + " " +
                    ChatColor.WHITE + entry.getKey().getName() + ": " +
                    ChatColor.GREEN + entry.getValue() + " kills\n");
            if (rank >= 10)
                break;
            rank++;
        }

        return status.toString();
    }

    private void startAutoSave() {
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
        saveKillData();
    }
}