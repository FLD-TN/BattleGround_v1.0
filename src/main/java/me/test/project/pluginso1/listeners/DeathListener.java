package me.test.project.pluginso1.listeners;

import me.test.project.pluginso1.BattlegroundManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
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
        
        // Chỉ xử lý khi người chơi đang trong BG
        if (bgManager.isRunning() && bgManager.getParticipants().contains(player)) {
            // Đếm số người còn sống
            int alivePlayers = 0;
            for (Player p : bgManager.getParticipants()) {
                if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                    alivePlayers++;
                }
            }

            // Thông báo cho tất cả người chơi
            Bukkit.broadcastMessage(ChatColor.RED + "• " + player.getName() + " đã chết!");
            Bukkit.broadcastMessage(ChatColor.YELLOW + "• Số người còn sống: " + (alivePlayers-1) + "/" + 
                                  bgManager.getParticipants().size());

            // Xử lý người chơi chết
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(player.getLocation().clone().add(0, 0, 0)); // Giữ nguyên vị trí
            player.sendMessage(ChatColor.RED + "Bạn đã chết! Đã chuyển sang chế độ Spectator.");
            player.sendMessage(ChatColor.YELLOW + "Dùng lệnh /bg leave để thoát.");
        }
    }
}