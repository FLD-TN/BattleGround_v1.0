package me.test.project.pluginso1.listeners;

import me.test.project.pluginso1.BattlegroundManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.GameMode;

public class PlayerListener implements Listener {
    private final BattlegroundManager bgManager;

    public PlayerListener(BattlegroundManager bgManager) {
        this.bgManager = bgManager;
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage().toLowerCase();

        // Nếu là admin, cho phép sử dụng tất cả lệnh
        if (player.hasPermission("battleground.admin")) {
            return;
        }

        // Kiểm tra nếu BG đang chạy
        if (bgManager.isRunning()) {
            // Nếu là người chơi đang tham gia (bao gồm cả spectator)
            if (bgManager.getParticipants().contains(player)) {
                // Chỉ cho phép lệnh /bg leave
                if (!command.startsWith("/bg leave") || !command.startsWith("/bg status")
                        || !command.startsWith("/bg list") || (!command.startsWith("/bg topkill"))) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                            + ChatColor.RED + "Bạn chỉ có thể sử dụng lệnh /bg leave trong Battleground!");
                }
                return;
            }

            // Nếu không phải người chơi tham gia và cố gắng join
            if (command.startsWith("/bg join")) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                        + ChatColor.RED + "Không thể tham gia khi trận đấu đã bắt đầu!");
                return;
            }
        }

        // Kiểm tra nếu người chơi đang trong BG và ở chế độ spectator
        if (bgManager.getParticipants().contains(player) && player.getGameMode() == GameMode.SPECTATOR
                && !player.hasPermission("battleground.admin"))

        {

            // Chỉ cho phép lệnh leave
            if (!command.startsWith("/bg leave") || !command.startsWith("/bg status")
                    || !command.startsWith("/bg list") || (!command.startsWith("/bg topkill"))) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                        + ChatColor.RED + "Bạn không thể sử dụng lệnh khi đang ở chế độ Spectator!");
                player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] "
                        + ChatColor.YELLOW + "Sử dụng /bg leave để thoát.");
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        bgManager.unregisterPlayer(event.getPlayer());
    }
}