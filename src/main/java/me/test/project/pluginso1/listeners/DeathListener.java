package me.test.project.pluginso1.listeners;

import me.test.project.pluginso1.BattlegroundManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class DeathListener implements Listener {
    private final BattlegroundManager bgManager;

    public DeathListener(BattlegroundManager bgManager) {
        this.bgManager = bgManager;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        
        // Kiểm tra nếu người chơi đang trong BG
        if (bgManager.isRunning() && bgManager.getParticipants().contains(player)) {
            // Tạo location để teleport
            Location deathLoc = new Location(player.getWorld(), 70.500, 173, 83.500);
            
            // Chuyển sang spectator và teleport
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(deathLoc);
            
            // Đếm số người còn sống
            int totalPlayers = bgManager.getParticipants().size();
            int alivePlayers = 0;
            for (Player p : bgManager.getParticipants()) {
                if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                    alivePlayers++;
                }
            }
            
            // Thông báo cho tất cả người chơi
            Bukkit.broadcastMessage(ChatColor.RED + player.getName() + " đã chết!");
            Bukkit.broadcastMessage(ChatColor.YELLOW + "Còn " + alivePlayers + "/" + totalPlayers + " người chơi còn sống!");
            
            // Thông báo riêng cho người chết
            player.sendMessage(ChatColor.RED + "Bạn đã chết! Đã chuyển sang chế độ Spectator.");
            player.sendMessage(ChatColor.YELLOW + "Dùng lệnh /bg leave để thoát.");
            
            bgManager.getPlugin().getLogger().info("Player " + player.getName() + " has died. Alive: " + alivePlayers + "/" + totalPlayers);
        }
    }
}