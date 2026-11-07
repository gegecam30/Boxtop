package fun.gexel.boxtop.commands;

import fun.gexel.boxtop.managers.BanManager;
import fun.gexel.boxtop.managers.DataManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BoxTopTab implements TabCompleter {

    private final DataManager dataManager;
    private final BanManager banManager;

    public BoxTopTab(DataManager dataManager, BanManager banManager) {
        this.dataManager = dataManager;
        this.banManager = banManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        if (args.length == 1) {
            List<String> commands = new ArrayList<>();
            commands.add("stats");
            commands.add("accept");
            if (sender.hasPermission("boxtop.admin")) {
                commands.add("spawn");
                commands.add("setentity");
                commands.add("unsetentity");
                commands.add("edit");
                commands.add("reload");
                commands.add("list");
                commands.add("tp");
                commands.add("resetranking");
                commands.add("admin");
            }
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], commands, completions);
            Collections.sort(completions);
            return completions;
        }

        if (args.length == 2 && sender.hasPermission("boxtop.admin")) {
            // Comandos que reciben nombre de saco existente
            if (args[0].equalsIgnoreCase("edit")
             || args[0].equalsIgnoreCase("tp")
             || args[0].equalsIgnoreCase("unsetentity")
             || args[0].equalsIgnoreCase("resetranking")) {
                List<String> bagNames = new ArrayList<>(dataManager.getBagNames());
                List<String> completions = new ArrayList<>();
                StringUtil.copyPartialMatches(args[1], bagNames, completions);
                Collections.sort(completions);
                return completions;
            }

            if (args[0].equalsIgnoreCase("admin")) {
                List<String> subCmds = java.util.Arrays.asList("wand", "generate", "addpost", "clearposts", "ban", "unban", "setlegacy");
                List<String> completions = new ArrayList<>();
                StringUtil.copyPartialMatches(args[1], subCmds, completions);
                Collections.sort(completions);
                return completions;
            }
        }
        
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && sender.hasPermission("boxtop.admin")) {
            if (args[1].equalsIgnoreCase("ban")) {
                List<String> playerNames = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) playerNames.add(p.getName());
                List<String> completions = new ArrayList<>();
                StringUtil.copyPartialMatches(args[2], playerNames, completions);
                Collections.sort(completions);
                return completions;
            }
            if (args[1].equalsIgnoreCase("unban")) {
                List<String> bannedNames = new ArrayList<>();
                if (banManager != null) {
                    for (BanManager.BannedPlayerEntry entry : banManager.getAllBannedPlayers()) {
                        bannedNames.add(entry.name);
                    }
                }
                List<String> completions = new ArrayList<>();
                StringUtil.copyPartialMatches(args[2], bannedNames, completions);
                Collections.sort(completions);
                return completions;
            }
        }


        if (args.length == 2 && args[0].equalsIgnoreCase("accept")) {
            List<String> playerNames = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) playerNames.add(p.getName());
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[1], playerNames, completions);
            Collections.sort(completions);
            return completions;
        }

        return null;
    }

}
