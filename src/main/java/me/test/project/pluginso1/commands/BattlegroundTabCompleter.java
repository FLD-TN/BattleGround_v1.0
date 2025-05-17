package me.test.project.pluginso1.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class BattlegroundTabCompleter implements TabCompleter {
    private final List<String> playerCommands = Arrays.asList("join", "leave", "status", "list", "topkill");
    private final List<String> adminCommands = Arrays.asList("start", "stop", "settime", "border");
    private final List<String> borderSubCommands = Arrays.asList("size", "set", "pause", "resume", "end");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            List<String> commands = new ArrayList<>(playerCommands); // All players can see basic commands
            
            // Add admin commands if player has permission
            if (sender.hasPermission("battleground.admin") || sender.isOp()) {
                commands.addAll(adminCommands);
            }
            
            // Filter based on what player has typed so far
            String partialCommand = args[0].toLowerCase();
            completions.addAll(commands.stream()
                    .filter(cmd -> cmd.toLowerCase().startsWith(partialCommand))
                    .collect(Collectors.toList()));
        }
        // Tab completion for border subcommands
        else if (args.length == 2 && args[0].equalsIgnoreCase("border") 
                && (sender.hasPermission("battleground.admin") || sender.isOp())) {
            String partial = args[1].toLowerCase();
            completions.addAll(borderSubCommands.stream()
                    .filter(cmd -> cmd.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList()));
        }
        
        return completions;
    }
}
