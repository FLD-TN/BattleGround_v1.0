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
        Player killer = player.getKiller();
        
        // Chỉ xử lý khi người chơi đang trong BG
        if (bgManager.isRunning() && bgManager.getParticipants().contains(player)) {
            // Đếm số người còn sống
            int alivePlayers = 0;
            for (Player p : bgManager.getParticipants()) {
                if (p.isOnline() && p.getGameMode() != GameMode.SPECTATOR) {
                    alivePlayers++;
                }
            }            // Xử lý kill trước khi gửi thông báo
            if (killer != null && bgManager.getParticipants().contains(killer)) {
                bgManager.addKill(killer);
            }

            // Title announcements for death
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (killer != null && bgManager.getParticipants().contains(killer)) {
                    p.sendTitle(
                        ChatColor.RED + "☠ " + player.getName() + " đã chết!",
                        ChatColor.GOLD + "Giết bởi: " + killer.getName(),
                        10, 60, 20
                    );
                } else {
                    p.sendTitle(
                        ChatColor.RED + "☠ " + player.getName() + " đã chết!",
                        ChatColor.GRAY + "Tử vong tự nhiên",
                        10, 60, 20
                    );
                }
            }

            // Thông báo cho tất cả người chơi
            Bukkit.broadcastMessage(ChatColor.RED + "• " + player.getName() + " đã chết!");
            if (killer != null && bgManager.getParticipants().contains(killer)) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "• " + killer.getName() + " đã giành được một điểm hạ gục!");
                
            }
            Bukkit.broadcastMessage("");
            Bukkit.broadcastMessage(ChatColor.GREEN + "• Số người còn sống: " + (alivePlayers-1));
            Bukkit.broadcastMessage("");
            Bukkit.broadcastMessage("");

            // Xử lý người chơi chết
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(player.getLocation().clone().add(0, 0, 0)); // Giữ nguyên vị trí
            player.sendMessage( ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "+ChatColor.RED + "Bạn đã chết! Đã chuyển sang chế độ Spectator.");
            player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "+ChatColor.YELLOW + "Dùng lệnh /bg leave để thoát.");
        }
    }
}