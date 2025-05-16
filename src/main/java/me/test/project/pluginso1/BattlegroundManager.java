package me.test.project.pluginso1;
import org.apache.logging.log4j.message.Message;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

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

    public BattlegroundManager(Main plugin) {
        this.plugin = plugin;
        loadConfig();
        scheduleStartCheck();
    }

    private void loadConfig() {
        try {
            lobbyLocation = parseLocation(plugin.getConfig().getString("lobby-location"));
            mapCenter = parseLocation(plugin.getConfig().getString("map-center"));
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
    }

    private Location parseLocation(String loc) {
        String[] parts = loc.split(",");
        return new Location(
                Bukkit.getWorld(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3])
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
            participants.add(player);
            player.sendMessage(ChatColor.GREEN + "Bạn đã đăng ký Battleground!");
            Bukkit.broadcastMessage(ChatColor.YELLOW + "Hiện có " + participants.size() + "/" + minPlayers + " người tham gia.");
            plugin.getLogger().info("Player " + player.getName() + " registered. Participants: " + participants.size());
        } else {
            player.sendMessage(ChatColor.RED + "Bạn đã đăng ký hoặc trận đã bắt đầu!");
        }
    }

    public void unregisterPlayer(Player player) {
        if (participants.remove(player)) {
            player.setGameMode(GameMode.SURVIVAL);
            player.teleport(Bukkit.getWorld("world").getSpawnLocation());
            player.sendMessage(ChatColor.GREEN + "Bạn đã rời khỏi Battleground!");
            plugin.getLogger().info("Player " + player.getName() + " unregistered.");
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
        }
        isRunning = true;
        startTime = -1;

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
                p.setGameMode(GameMode.ADVENTURE);
                p.sendMessage(ChatColor.YELLOW + "Chờ đếm ngược để bắt đầu!");
            }
        }

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
                } else {
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
        }

        // Trong phương thức startMatch(), thay đổi phần thiết lập border:
        World world = mapCenter.getWorld();
        border = world.getWorldBorder();
        border.reset();
        border.setCenter(mapCenter);
        border.setSize(borderInitialSize);
        border.setDamageAmount(1.0);    // Tăng sát thương lên 2 hearts/giây
        border.setDamageBuffer(0.0);    // Không có buffer, gây sát thương ngay khi ra khỏi border
        border.setWarningDistance(1);    // Giữ nguyên
        border.setWarningTime(0);        // Giữ nguyên
        plugin.getLogger().info("Border set to initial size: " + borderInitialSize);

        // Teleport và thiết lập người chơi
        for (Player p : participants) {
            if (p.isOnline()) {
                p.teleport(mapCenter.clone().add(0, 10, 0));
                p.setInvulnerable(true);
                p.sendMessage(ChatColor.GREEN + "Bạn đã vào trận Battleground!");
                p.sendMessage(ChatColor.YELLOW + "Border ban đầu: " + String.format("%.1f", borderInitialSize) + " blocks");
                plugin.getLogger().info("Teleported " + p.getName() + " to match start location");
            }
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
        
        long finalPhases = 4; // Giai đoạn cuối chia làm 4 phần
        long finalTimePerPhase = 40L; // 2 giây mỗi lần thu nhỏ ở giai đoạn cuối

        // Giai đoạn chính (80% đầu)
        new BukkitRunnable() {
            int phase = 0;
            double currentSize = borderInitialSize;

            @Override
            public void run() {
                phase++;
                if (phase <= mainPhases) {
                    currentSize = borderInitialSize - ((borderInitialSize * 0.2) * phase);
                    border.setSize(currentSize, 40L);
                    
                    String message = ChatColor.GOLD + "Border đang thu nhỏ!\n" +
                            ChatColor.WHITE + "• Kích thước mới: " + String.format("%.1f", currentSize) + "\n" +
                            ChatColor.WHITE + "• Còn " + (mainPhases - phase) + " lần thu nhỏ chính";
                    
                    Bukkit.broadcastMessage(message);
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
                        p.damage(200.0); // Sát thương player chịu khi border biến mất hoàn toàn
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
        }

        // Reset border
        if (border != null) {
            border.reset();
            border = null;
        }
        
        // Reset các trạng thái và hủy tất cả các task
        for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
            if (task.getOwner().equals(plugin)) {
                task.cancel();
            }
        }
        
        // Tự động cho tất cả người chơi leave BG
        List<Player> playersToRemove = new ArrayList<>(participants);
        for (Player p : playersToRemove) {
            if (p.isOnline()) {
                // Đưa về GameMode SURVIVAL cho mọi người chơi
                p.setGameMode(GameMode.SURVIVAL);
                unregisterPlayer(p);
                p.sendMessage(ChatColor.GREEN + "Trận đấu đã kết thúc! Bạn đã được đưa về trạng thái bình thường.");
            }
        }

        // Reset các trạng thái
        participants.clear();
        isRunning = false;
        startTime = -1;

        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }

        Bukkit.broadcastMessage(ChatColor.GOLD + "Battleground đã kết thúc!");
        plugin.getLogger().info("Match stopped and all players reset to survival mode");
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
    }

   
}