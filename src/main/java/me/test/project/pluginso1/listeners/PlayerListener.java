package me.test.project.pluginso1.listeners;

import me.test.project.pluginso1.BattlegroundManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

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

        // Kiểm tra nếu BG đang chạy và người chơi là participant
        if (bgManager.isRunning() && bgManager.getParticipants().contains(player)) {
            // Cho phép các lệnh /bg leave, /bg status, /bg list, /bg topkill
            if (command.startsWith("/bg leave") || command.startsWith("/bg status") ||
                    command.startsWith("/bg list") || command.startsWith("/bg topkill")) {
                return; // Lệnh được phép, không hủy
            }

            // Hủy các lệnh khác
            event.setCancelled(true);
            player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] " +
                    ChatColor.RED
                    + "Bạn chỉ có thể sử dụng các lệnh: /bg leave, /bg status, /bg list, /bg topkill trong Battleground!");
            bgManager.getPlugin().getLogger()
                    .info("Cancelled command '" + command + "' for player " + player.getName() +
                            " (participant in running Battleground)");
            return;
        }

        // Ngăn /bg join khi trận đã bắt đầu
        if (bgManager.isRunning() && command.startsWith("/bg join")) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "BattleGround" + ChatColor.GRAY + "] " +
                    ChatColor.RED + "Không thể tham gia khi trận đấu đã bắt đầu!");
            bgManager.getPlugin().getLogger().info("Cancelled /bg join for player " + player.getName() +
                    " (match already running)");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        bgManager.unregisterPlayer(event.getPlayer());
    }
}