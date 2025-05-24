package me.test.project.pluginso1.listeners;

import me.test.project.pluginso1.BattlegroundManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

public class DeathListener implements Listener {
    private final BattlegroundManager bgManager;

    public DeathListener(BattlegroundManager bgManager) {
        this.bgManager = bgManager;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Player killer = player.getKiller();

        // Chỉ xử lý nếu người chơi đang trong BG
        if (bgManager.isRunning() && bgManager.getParticipants().contains(player)) {
            // Đếm số người còn sống
            int alivePlayers = 0;
            for (Player p : bgManager.getParticipants()) {
                if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                    alivePlayers++;
                }
            }

            // Xử lý kill trước khi gửi thông báo
            if (killer != null && bgManager.getParticipants().contains(killer)) {
                bgManager.addKill(killer);
            }

            // Thông báo tiêu đề cho cái chết
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (killer != null && bgManager.getParticipants().contains(killer)) {
                    p.sendTitle(
                            ChatColor.RED + "☠ " + player.getName() + " đã chết!",
                            ChatColor.GOLD + "Giết bởi: " + killer.getName(),
                            10, 60, 20);
                } else {
                    p.sendTitle(
                            ChatColor.RED + "☠ " + player.getName() + " đã chết!",
                            ChatColor.GRAY + "Đã bị thông đít !",
                            10, 60, 20);
                }
            }

            // Thông báo cho tất cả người chơi
            Bukkit.broadcastMessage(ChatColor.RED + "• " + player.getName() + " đã chết!");
            if (killer != null && bgManager.getParticipants().contains(killer)) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "• " + killer.getName() + " đã giành được một điểm hạ gục!");
            }
            Bukkit.broadcastMessage("");
            Bukkit.broadcastMessage(ChatColor.GREEN + "• Số người còn sống: " + (alivePlayers - 1));
            Bukkit.broadcastMessage("");
            Bukkit.broadcastMessage("");

            // Xử lý người chơi chết
            player.setGameMode(GameMode.SPECTATOR);
            bgManager.getPlugin().getLogger().info("Set " + player.getName() + " to SPECTATOR mode on death");

            // Đặt lại trạng thái người chơi
            player.setHealth(20.0);
            player.setFoodLevel(20);
            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }

            // Teleport đến lobby-location
            Location lobbyLocation = bgManager.getLobbyLocation();
            if (lobbyLocation == null || lobbyLocation.getWorld() == null) {
                String worldName = bgManager.getPlugin().getConfig().getString("lobby-location.world", "warp_wheat");
                World fallbackWorld = Bukkit.getWorld(worldName);
                if (fallbackWorld == null) {
                    fallbackWorld = Bukkit.getWorld("world");
                }
                if (fallbackWorld != null) {
                    lobbyLocation = fallbackWorld.getSpawnLocation();
                } else {
                    lobbyLocation = Bukkit.getWorlds().get(0).getSpawnLocation();
                }
                bgManager.getPlugin().getLogger().warning("Invalid lobbyLocation (world: " + worldName
                        + "), using fallback: " + lobbyLocation.toString());
            } else {
                bgManager.getPlugin().getLogger().info("Loaded lobbyLocation: " + lobbyLocation.toString());
            }

            // Teleport với độ trễ 2 tick để đảm bảo ổn định
            Location finalLobbyLocation = lobbyLocation;
            new BukkitRunnable() {
                @Override
                public void run() {
                    player.teleport(finalLobbyLocation);
                    bgManager.getPlugin().getLogger().info(
                            "Teleported " + player.getName() + " to lobbyLocation: " + finalLobbyLocation.toString());
                    // Kiểm tra vị trí thực tế sau teleport
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            Location actualLocation = player.getLocation();
                            bgManager.getPlugin().getLogger().info("Actual location of " + player.getName()
                                    + " after teleport: " + actualLocation.toString());
                            if (!actualLocation.getWorld().getName().equals(finalLobbyLocation.getWorld().getName()) ||
                                    Math.abs(actualLocation.getX() - finalLobbyLocation.getX()) > 1 ||
                                    Math.abs(actualLocation.getY() - finalLobbyLocation.getY()) > 1 ||
                                    Math.abs(actualLocation.getZ() - finalLobbyLocation.getZ()) > 1) {
                                bgManager.getPlugin().getLogger().warning("Teleport mismatch for " + player.getName() +
                                        "! Expected: " + finalLobbyLocation.toString() +
                                        ", Actual: " + actualLocation.toString());
                                // Thử teleport lại
                                player.teleport(finalLobbyLocation);
                                bgManager.getPlugin().getLogger().info("Retried teleport for " + player.getName()
                                        + " to: " + finalLobbyLocation.toString());
                            }
                            // Đảm bảo chế độ SPECTATOR
                            if (player.getGameMode() != GameMode.SPECTATOR) {
                                player.setGameMode(GameMode.SPECTATOR);
                                bgManager.getPlugin().getLogger()
                                        .warning("Forced SPECTATOR mode for " + player.getName() + " after teleport");
                            }
                        }
                    }.runTaskLater(bgManager.getPlugin(), 2L);
                }
            }.runTaskLater(bgManager.getPlugin(), 2L);

            player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                    + ChatColor.RED + "Bạn đã chết và đang ở chế độ quan sát!");
            player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                    + ChatColor.YELLOW + "Dùng lệnh /bg leave để thoát.");
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        // Chỉ xử lý nếu người chơi đang trong BG
        if (bgManager.isRunning() && bgManager.getParticipants().contains(player)) {
            // Đặt vị trí tái sinh tại lobbyLocation
            Location lobbyLocation = bgManager.getLobbyLocation();
            if (lobbyLocation == null || lobbyLocation.getWorld() == null) {
                String worldName = bgManager.getPlugin().getConfig().getString("lobby-location.world", "warp_wheat");
                World fallbackWorld = Bukkit.getWorld(worldName);
                if (fallbackWorld == null) {
                    fallbackWorld = Bukkit.getWorld("world");
                }
                if (fallbackWorld != null) {
                    lobbyLocation = fallbackWorld.getSpawnLocation();
                } else {
                    lobbyLocation = Bukkit.getWorlds().get(0).getSpawnLocation();
                }
                bgManager.getPlugin().getLogger().warning("Invalid lobbyLocation for respawn (world: " + worldName
                        + "), using fallback: " + lobbyLocation.toString());
            } else {
                bgManager.getPlugin().getLogger()
                        .info("Respawn lobbyLocation for " + player.getName() + ": " + lobbyLocation.toString());
            }

            event.setRespawnLocation(lobbyLocation);
            bgManager.getPlugin().getLogger()
                    .info("Set respawn location for " + player.getName() + " to: " + lobbyLocation.toString());

            // Đảm bảo chế độ SPECTATOR và trạng thái
            new BukkitRunnable() {
                @Override
                public void run() {
                    player.setGameMode(GameMode.SPECTATOR);
                    player.setHealth(20.0);
                    player.setFoodLevel(20);
                    for (PotionEffect effect : player.getActivePotionEffects()) {
                        player.removePotionEffect(effect.getType());
                    }
                    bgManager.getPlugin().getLogger().info("Set " + player.getName() + " to SPECTATOR mode on respawn");
                }
            }.runTaskLater(bgManager.getPlugin(), 1L);
        }
    }

}