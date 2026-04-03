package fun.gexel.boxtop.commands;

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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // --- ARGUMENTO 1 (Subcomando) ---
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            List<String> commands = new ArrayList<>();
            
            // Comandos de Usuario
            commands.add("stats");
            commands.add("accept"); // <-- Nuevo comando de duelos
            
            // Comandos de Admin
            if (sender.hasPermission("boxtop.admin")) {
                commands.add("setentity");
                commands.add("unsetentity");
                commands.add("reload");
                commands.add("list");
            }
            
            StringUtil.copyPartialMatches(args[0], commands, completions);
            Collections.sort(completions);
            return completions;
        }

        // --- ARGUMENTO 2 (Nombres de jugadores) ---
        // Si escribe: /boxtop accept <TAB>
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("accept")) {
                List<String> playerNames = new ArrayList<>();
                // Llenar lista con jugadores online
                for (Player p : Bukkit.getOnlinePlayers()) {
                    playerNames.add(p.getName());
                }
                
                // Filtrar por lo que el usuario ya escribió
                List<String> completions = new ArrayList<>();
                StringUtil.copyPartialMatches(args[1], playerNames, completions);
                Collections.sort(completions);
                return completions;
            }
        }
        
        return null; // Dejar que Bukkit maneje el resto
    }
}