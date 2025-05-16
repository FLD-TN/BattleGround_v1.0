package me.test.project.pluginso1.listeners;

import me.test.project.pluginso1.BattlegroundManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public class TeleportListener implements Listener {
    private final BattlegroundManager bgManager;
    private final Location warLocation;

    public TeleportListener(BattlegroundManager bgManager, Location warLocation) {
        this.bgManager = bgManager;
        this.warLocation = warLocation;
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        World targetWorld = event.getTo().getWorld();
        
        if (targetWorld.getName().equals("newbox")) {
            // Entry control is now handled by WorldGuard __global__ region flags and membership
            if (!bgManager.getParticipants().contains(player) && !player.isOp()) {
                event.setCancelled(true);
                player.teleport(Bukkit.getWorld("world").getSpawnLocation());
                player.sendMessage(ChatColor.RED + "Bạn không thể teleport vào khu vực này!");
                Bukkit.getLogger().info(player.getName() + " denied teleport to newbox - Not a BG participant");
            }
        }
    }
}
