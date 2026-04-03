package fun.gexel.boxtop.commands;

import fun.gexel.boxtop.managers.DataManager;
import fun.gexel.boxtop.managers.DuelManager;
import fun.gexel.boxtop.managers.GlowManager;
import fun.gexel.boxtop.objects.PlayerStat;
import fun.gexel.boxtop.tasks.ParticleTask;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public class BoxTopCommand implements CommandExecutor {

    private final DataManager dataManager;
    private final GlowManager glowManager;
    private final DuelManager duelManager;
    private final ParticleTask particleTask;

    // --- CONSTRUCTOR CORREGIDO ---
    // Ahora acepta los 4 parámetros que le envías desde el Main
    public BoxTopCommand(DataManager dataManager, GlowManager glowManager, DuelManager duelManager, ParticleTask particleTask) {
        this.dataManager = dataManager;
        this.glowManager = glowManager;
        this.duelManager = duelManager;
        this.particleTask = particleTask; // Ahora sí se asigna correctamente
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // --- HELP MENU ---
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "BoxTop " + ChatColor.GRAY + "(v2.6)");
            sender.sendMessage(ChatColor.YELLOW + "/boxtop stats " + ChatColor.GRAY + "- View your statistics");
            sender.sendMessage(ChatColor.YELLOW + "/boxtop accept <player> " + ChatColor.GRAY + "- Accept invite");
            if (sender.hasPermission("boxtop.admin")) {
                sender.sendMessage(ChatColor.RED + "/boxtop setentity <name> " + ChatColor.GRAY + "- Create bag (Look at mob)");
                sender.sendMessage(ChatColor.RED + "/boxtop unsetentity <name> " + ChatColor.GRAY + "- Delete bag");
                sender.sendMessage(ChatColor.RED + "/boxtop list " + ChatColor.GRAY + "- Interactive list");
                sender.sendMessage(ChatColor.RED + "/boxtop tp <name> " + ChatColor.GRAY + "- Teleport to bag");
                sender.sendMessage(ChatColor.RED + "/boxtop reload " + ChatColor.GRAY + "- Reload config");
            }
            return true;
        }

        // --- STATS ---
        if (args[0].equalsIgnoreCase("stats")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(dataManager.getMessage("only-players"));
                return true;
            }
            Player player = (Player) sender;
            PlayerStat stat = dataManager.getPlayerStat(player.getUniqueId());

            if (stat == null || stat.getDamage() <= 0) {
                sender.sendMessage(dataManager.getMessage("stats-no-data"));
            } else {
                sender.sendMessage(dataManager.getRawMessage("messages.stats-header"));
                String format = dataManager.getRawMessage("messages.stats-format");
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                        format.replace("{damage}", String.format("%.0f", stat.getDamage()))));
            }
            return true;
        }

        // --- RELOAD OPTIMIZADO ---
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("boxtop.admin")) {
                sender.sendMessage(dataManager.getMessage("no-permission"));
                return true;
            }
            
            // 1. Recargar Configuración de Disco
            dataManager.reloadConfig();
            
            // 2. Recargar Glow
            glowManager.updateGlow();
            
            // 3. Recargar Variables de Partículas
            particleTask.reloadVariables();
            
            sender.sendMessage(dataManager.getMessage("reload"));
            return true;
        }

        // --- ACCEPT DUEL ---
        if (args[0].equalsIgnoreCase("accept")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;

            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /boxtop accept <player>");
                return true;
            }

            Player inviter = Bukkit.getPlayer(args[1]);
            if (inviter == null || !inviter.isOnline()) {
                player.sendMessage(ChatColor.RED + "That player is not online.");
                return true;
            }

            if (duelManager.hasInvite(player, inviter)) {
                duelManager.acceptInvite(player, inviter);
            } else {
                player.sendMessage(ChatColor.RED + "You don't have an invitation from " + inviter.getName());
            }
            return true;
        }

        // --- LIST (INTERACTIVO) ---
        if (args[0].equalsIgnoreCase("list")) {
            if (!sender.hasPermission("boxtop.admin")) {
                sender.sendMessage(dataManager.getMessage("no-permission"));
                return true;
            }

            Set<String> bags = dataManager.getBagNames();
            if (bags.isEmpty()) {
                sender.sendMessage(dataManager.getMessage("list-empty"));
                return true;
            }

            sender.sendMessage(dataManager.getRawMessage("messages.list-header"));

            for (String name : bags) {
                // Base: "- nombre "
                TextComponent mainText = new TextComponent(ChatColor.GRAY + "- " + ChatColor.YELLOW + name + " ");
                
                // Botón [TP]
                TextComponent tpBtn = new TextComponent(ChatColor.AQUA + "[TP]");
                tpBtn.setBold(true);
                tpBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/boxtop tp " + name));
                tpBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to Teleport").create()));
                
                // Botón [REMOVE]
                TextComponent delBtn = new TextComponent(ChatColor.RED + " [REMOVE]");
                delBtn.setBold(true);
                delBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/boxtop unsetentity " + name));
                delBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to Delete").create()));

                mainText.addExtra(tpBtn);
                mainText.addExtra(delBtn);
                
                if (sender instanceof Player) {
                    ((Player) sender).spigot().sendMessage(mainText);
                } else {
                    sender.sendMessage("- " + name);
                }
            }
            return true;
        }

        // --- TP (NUEVO) ---
        if (args[0].equalsIgnoreCase("tp")) {
            if (!sender.hasPermission("boxtop.admin")) return true;
            if (!(sender instanceof Player)) return true;
            
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /boxtop tp <bagName>");
                return true;
            }

            String name = args[1].toLowerCase(); 
            UUID uuid = dataManager.getBagUUID(name);
            
            if (uuid == null) {
                sender.sendMessage(ChatColor.RED + "Bag not found.");
                return true;
            }

            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) {
                ((Player) sender).teleport(entity.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Teleported to " + name);
            } else {
                sender.sendMessage(ChatColor.RED + "Error: Bag entity is not loaded or dead.");
            }
            return true;
        }

        // --- SET ENTITY (MEJORADO CON RAYTRACE) ---
        if (args[0].equalsIgnoreCase("setentity")) {
            if (!sender.hasPermission("boxtop.admin")) return true;
            if (!(sender instanceof Player)) return true;
            
            if (args.length < 2) {
                sender.sendMessage(dataManager.getMessage("usage-set"));
                return true;
            }

            Player player = (Player) sender;
            String bagName = args[1].toLowerCase();

            // 1. CHECK DE NOMBRE ÚNICO
            if (dataManager.getBagUUID(bagName) != null) {
                sender.sendMessage(ChatColor.RED + "Error: A bag with the name '" + bagName + "' already exists!");
                return true;
            }

            // 2. RAYTRACING MANUAL (Compatible 1.12)
            Entity target = getTargetEntity(player, 5); 

            if (target != null) {
                dataManager.addBoxingBag(bagName, target.getUniqueId());
                glowManager.updateGlow();
                sender.sendMessage(dataManager.getMessage("entity-set").replace("{name}", bagName));
            } else {
                sender.sendMessage(ChatColor.RED + "No valid entity found. Please LOOK directly at the mob/NPC.");
            }
            return true;
        }

        // --- UNSET ENTITY ---
        if (args[0].equalsIgnoreCase("unsetentity")) {
            if (!sender.hasPermission("boxtop.admin")) return true;
            if (args.length < 2) {
                sender.sendMessage(dataManager.getMessage("usage-unset"));
                return true;
            }
            
            String bagName = args[1].toLowerCase();
            UUID bagUUID = dataManager.getBagUUID(bagName);
            
            if (dataManager.removeBoxingBag(bagName)) {
                if (bagUUID != null) glowManager.removeGlow(bagUUID);
                sender.sendMessage(dataManager.getMessage("entity-unset").replace("{name}", bagName));
            } else {
                sender.sendMessage(dataManager.getMessage("entity-not-found").replace("{name}", bagName));
            }
            return true;
        }

        return true;
    }

    /**
     * RAYTRACING MANUAL MEJORADO (Compatible 1.12 - 1.21)
     */
    private Entity getTargetEntity(Player player, int range) {
        List<Entity> nearby = player.getNearbyEntities(range, range, range);
        Vector eye = player.getEyeLocation().toVector();
        Vector direction = player.getEyeLocation().getDirection().normalize();
        
        Entity target = null;
        double minDistance = Double.MAX_VALUE;

        for (Entity e : nearby) {
            if (e == player) continue;
            Vector entityLoc = e.getLocation().toVector().add(new Vector(0, 0.9, 0));
            Vector toEntity = entityLoc.clone().subtract(eye);
            double dot = toEntity.clone().normalize().dot(direction);
            if (dot < 0.7) continue; 
            double distToLine = toEntity.clone().crossProduct(direction).length();
            if (distToLine < 2.0) { 
                double distToPlayer = entityLoc.distance(eye);
                if (distToPlayer < minDistance) {
                    minDistance = distToPlayer;
                    target = e;
                }
            }
        }
        return target;
    }
}