package me.test.project.pluginso1.commands;

import me.test.project.pluginso1.BattlegroundManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;


public class BattlegroundCommand implements CommandExecutor {
    private final BattlegroundManager bgManager;

    public BattlegroundCommand(BattlegroundManager bgManager) {
        this.bgManager = bgManager;
    }    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {            // Thông tin chung cho tất cả người chơi
            sender.sendMessage(ChatColor.GOLD + "✦ Battleground_v1.0 ✦");
            sender.sendMessage(ChatColor.GRAY + "➥ Author: FLD-TN");

            // Hiển thị lệnh dựa theo quyền
            if (sender.hasPermission("battleground.admin") || sender.isOp()) {
                sender.sendMessage(ChatColor.WHITE + "⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯");
                sender.sendMessage(ChatColor.WHITE + "• " + ChatColor.BLUE + "/bg join" + ChatColor.GRAY + " - đăng kí tham gia BattleGround");
                sender.sendMessage(ChatColor.WHITE + "• " + ChatColor.BLUE + "/bg start <thời gian> <kích thước border>" + ChatColor.GRAY + " - Bắt đầu trận đấu");
                sender.sendMessage(ChatColor.WHITE + "• " + ChatColor.BLUE + "/bg stop" + ChatColor.GRAY + " - Dừng trận đấu đang diễn ra");
                sender.sendMessage(ChatColor.WHITE + "• " + ChatColor.BLUE + "/bg settime <seconds>" + ChatColor.GRAY + " - Đặt thời gian bắt đầu");
                sender.sendMessage(ChatColor.WHITE + "• " + ChatColor.BLUE + "/bg border size" + ChatColor.GRAY + " - Xem kích thước hiện tại");
                sender.sendMessage(ChatColor.WHITE + "• " + ChatColor.BLUE + "/bg border set <size>" + ChatColor.GRAY + " - Đặt kích thước border");
                sender.sendMessage(ChatColor.WHITE + "• " + ChatColor.BLUE + "/bg border end" + ChatColor.GRAY + " - Thu nhỏ border về 0");
            } else {
                sender.sendMessage(ChatColor.WHITE + "⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯");
                sender.sendMessage(ChatColor.YELLOW + "Player Commands:");
                sender.sendMessage(ChatColor.WHITE + "• " + ChatColor.YELLOW + "/bg join" + ChatColor.GRAY + " - Tham gia trận đấu");
                sender.sendMessage(ChatColor.WHITE + "• " + ChatColor.YELLOW + "/bg leave" + ChatColor.GRAY + " - Rời khỏi trận đấu");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("join")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Chỉ người chơi dùng được lệnh này!");
                return true;
            }
            bgManager.registerPlayer((Player) sender);
        } else if (args[0].equalsIgnoreCase("leave")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Chỉ người chơi dùng được lệnh này!");
                return true;
            }
            bgManager.unregisterPlayer((Player) sender);
        } else if (args[0].equalsIgnoreCase("settime")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Chỉ người chơi dùng được lệnh này!");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("battleground.admin")) {
                sender.sendMessage(ChatColor.RED + "Bạn không có quyền!");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Dùng: /bg settime <seconds>");
                return true;
            }
            try {
                long seconds = Long.parseLong(args[1]);
                bgManager.setStartTime(seconds);
                sender.sendMessage(ChatColor.GREEN + "Đã đặt thời gian bắt đầu sau " + seconds + " giây!");
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Thời gian phải là số!");
            }        } else if (args[0].equalsIgnoreCase("start")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Chỉ người chơi dùng được lệnh này!");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("battleground.admin")) {
                sender.sendMessage(ChatColor.RED + "Bạn không có quyền!");
                return true;
            }

            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Dùng: /bg start <thời gian> <kích thước border>");
                sender.sendMessage(ChatColor.YELLOW + "Ví dụ: /bg start 300 200");
                return true;
            }

            try {
                // Đặt thời gian trận đấu
                int duration = Integer.parseInt(args[1]);
                if (duration < 30) {
                    sender.sendMessage(ChatColor.RED + "Thời gian trận đấu phải ít nhất 30 giây!");
                    return true;
                }
                bgManager.setMatchDuration(duration);

                // Đặt kích thước border
                double borderSize = Double.parseDouble(args[2]);
                if (borderSize < 50) {
                    sender.sendMessage(ChatColor.RED + "Kích thước border phải ít nhất 50 blocks!");
                    return true;
                }
                bgManager.setInitialBorderSize(borderSize);

                sender.sendMessage(ChatColor.GREEN + "Thiết lập trận đấu:");
                sender.sendMessage(ChatColor.YELLOW + "• Thời gian: " + duration + " giây");
                sender.sendMessage(ChatColor.YELLOW + "• Border ban đầu: " + borderSize + " blocks");
                sender.sendMessage(ChatColor.YELLOW + "• Border sẽ thu nhỏ 20% mỗi " + (duration/5) + " giây");
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Thời gian và kích thước border phải là số!");
                return true;
            }
            
            bgManager.start();
            sender.sendMessage(ChatColor.GREEN + "Đã bắt đầu trận Battleground!");
        } else if (args[0].equalsIgnoreCase("stop")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Chỉ người chơi dùng được lệnh này!");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("battleground.admin")) {
                sender.sendMessage(ChatColor.RED + "Bạn không có quyền!");
                return true;
            }
            if (!bgManager.isRunning()) {
                sender.sendMessage(ChatColor.RED + "Không có trận Battleground nào đang diễn ra!");
                return true;
            }
            bgManager.stop();
            sender.sendMessage(ChatColor.GREEN + "Đã dừng trận Battleground!");
        } else if (args[0].equalsIgnoreCase("border")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Chỉ người chơi dùng được lệnh này!");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("battleground.admin")) {
                sender.sendMessage(ChatColor.RED + "Bạn không có quyền!");
                return true;
            }
            
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Dùng: /bg border [size|set <size>]");
                return true;
            }
            if (args[1].equalsIgnoreCase("size")) {
                double size = bgManager.getBorderSize();
                if (size == -1 || size == 0) {
                    sender.sendMessage(ChatColor.RED + "Border chưa được thiết lập!");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "Kích thước border hiện tại: " + (int) size + " block (khu vực: " + (int) size + "x" + (int) size + " block)");
                }
            } else if (args[1].equalsIgnoreCase("set")) {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Dùng: /bg border set <size>");
                    return true;
                }
                try {
                    double size = Double.parseDouble(args[2]);
                    // if (size <= 0) {
                    //     sender.sendMessage(ChatColor.RED + "Kích thước phải là số dương!");
                    //     return true;
                    // }
                    bgManager.setBorderSize(size);
                    sender.sendMessage(ChatColor.GREEN + "Đã đặt kích thước border thành " + (int) size + " block (khu vực: " + (int) size + "x" + (int) size + " block)");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Kích thước phải là số!");
                }
            } else if (args[1].equalsIgnoreCase("end")) {
                // Kiểm tra quyền admin
                if (!sender.hasPermission("battleground.admin")) {
                    sender.sendMessage(ChatColor.RED + "Bạn không có quyền sử dụng lệnh này!");
                    return true;
                }

                // Kiểm tra game có đang chạy không
                if (!bgManager.isRunning()) {
                    sender.sendMessage(ChatColor.RED + "Không có trận đấu nào đang diễn ra!");
                    return true;
                }

                bgManager.forceShrinkBorder();
                sender.sendMessage(ChatColor.GREEN + "Đã force thu nhỏ border về 0!");
                return true;
            } else {
                sender.sendMessage(ChatColor.RED + "Dùng: /bg border [size|set <size>|end]");
            }
        } 
        return true;
    }
}