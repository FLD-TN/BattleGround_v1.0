package me.test.project.pluginso1.listeners;

import me.test.project.pluginso1.BattlegroundManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

public class WorldListener implements Listener {
    private final BattlegroundManager bgManager;

    public WorldListener(BattlegroundManager bgManager) {
        this.bgManager = bgManager;
    }

    @EventHandler
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World newWorld = player.getWorld();

        if (newWorld.getName().equals("newbox")) {
            // Entry control is now handled by WorldGuard __global__ region flags and membership
            if (!bgManager.getParticipants().contains(player) && !player.isOp()) {
                player.teleport(Bukkit.getWorld("world").getSpawnLocation());
                player.sendMessage(ChatColor.RED + "Bạn không có quyền vào khu vực này!");
            }
        }
    }
}
