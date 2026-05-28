package fun.gexel.boxtop.commands;

import fun.gexel.boxtop.gui.BagConfigGUI;
import fun.gexel.boxtop.managers.BagSpawnManager;
import fun.gexel.boxtop.managers.DataManager;
import fun.gexel.boxtop.managers.DuelManager;
import fun.gexel.boxtop.managers.GlowManager;
import fun.gexel.boxtop.objects.BagData;
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
    private final BagConfigGUI configGUI;
    private final BagSpawnManager spawnManager;

    public BoxTopCommand(DataManager dataManager, GlowManager glowManager,
                         DuelManager duelManager, ParticleTask particleTask,
                         BagConfigGUI configGUI, BagSpawnManager spawnManager) {
        this.dataManager  = dataManager;
        this.glowManager  = glowManager;
        this.duelManager  = duelManager;
        this.particleTask = particleTask;
        this.configGUI    = configGUI;
        this.spawnManager = spawnManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // --- HELP ---
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "BoxTop " + ChatColor.GRAY + "(v2.8)");
            sender.sendMessage(ChatColor.YELLOW + "/boxtop stats "          + ChatColor.GRAY + "- Your statistics");
            sender.sendMessage(ChatColor.YELLOW + "/boxtop accept <player> "+ ChatColor.GRAY + "- Accept duel invite");
            if (sender.hasPermission("boxtop.admin")) {
                sender.sendMessage(ChatColor.RED + "/boxtop spawn <name> "     + ChatColor.GRAY + "- Spawn a bag ArmorStand here");
                sender.sendMessage(ChatColor.RED + "/boxtop setentity <name> " + ChatColor.GRAY + "- Link bag to existing mob");
                sender.sendMessage(ChatColor.RED + "/boxtop unsetentity <name>"+ ChatColor.GRAY + "- Remove bag");
                sender.sendMessage(ChatColor.RED + "/boxtop edit <name> "      + ChatColor.GRAY + "- Open bag config GUI");
                sender.sendMessage(ChatColor.RED + "/boxtop list "             + ChatColor.GRAY + "- Interactive list");
                sender.sendMessage(ChatColor.RED + "/boxtop tp <name> "        + ChatColor.GRAY + "- Teleport to bag");
                sender.sendMessage(ChatColor.RED + "/boxtop reload "           + ChatColor.GRAY + "- Reload config");
            }
            return true;
        }

        // --- STATS ---
        if (args[0].equalsIgnoreCase("stats")) {
            if (!(sender instanceof Player)) { sender.sendMessage(dataManager.getMessage("only-players")); return true; }
            Player player = (Player) sender;
            PlayerStat stat = dataManager.getPlayerStat(player.getUniqueId());
            if (stat == null || stat.getDamage() <= 0) {
                sender.sendMessage(dataManager.getMessage("stats-no-data"));
            } else {
                sender.sendMessage(dataManager.getRawMessage("messages.stats-header"));
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    dataManager.getRawMessage("messages.stats-format")
                        .replace("{damage}", String.format("%.0f", stat.getDamage()))));
            }
            return true;
        }

        // --- RELOAD ---
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("boxtop.admin")) { sender.sendMessage(dataManager.getMessage("no-permission")); return true; }
            dataManager.reloadConfig();
            glowManager.updateGlow();
            particleTask.reloadVariables();
            // Refrescar ítems de sacos spawneados por si cambió custom-model-data en config
            for (BagData bag : dataManager.getAllBags()) {
                if (bag.isSpawned()) spawnManager.refreshItem(bag);
            }
            sender.sendMessage(dataManager.getMessage("reload"));
            return true;
        }

        // --- ACCEPT ---
        if (args[0].equalsIgnoreCase("accept")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            if (args.length < 2) { player.sendMessage(ChatColor.RED + "Usage: /boxtop accept <player>"); return true; }
            Player inviter = Bukkit.getPlayer(args[1]);
            if (inviter == null || !inviter.isOnline()) { player.sendMessage(ChatColor.RED + "That player is not online."); return true; }
            if (duelManager.hasInvite(player, inviter)) duelManager.acceptInvite(player, inviter);
            else player.sendMessage(ChatColor.RED + "No invitation from " + inviter.getName());
            return true;
        }

        // --- SPAWN (NUEVO) ---
        if (args[0].equalsIgnoreCase("spawn")) {
            if (!sender.hasPermission("boxtop.admin")) { sender.sendMessage(dataManager.getMessage("no-permission")); return true; }
            if (!(sender instanceof Player)) { sender.sendMessage(dataManager.getMessage("only-players")); return true; }
            if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /boxtop spawn <name>"); return true; }

            Player player = (Player) sender;
            String bagName = args[1].toLowerCase();

            if (dataManager.getBagUUID(bagName) != null) {
                sender.sendMessage(ChatColor.RED + "A bag named '" + bagName + "' already exists!");
                return true;
            }

            BagData bag = spawnManager.spawnBag(bagName, player.getLocation());
            if (bag != null) {
                glowManager.updateGlow();
                sender.sendMessage(dataManager.getMessage("entity-set").replace("{name}", bagName));
                sender.sendMessage(ChatColor.GRAY + "Tip: Use " + ChatColor.YELLOW + "/boxtop edit " + bagName
                    + ChatColor.GRAY + " to configure this bag.");
            } else {
                sender.sendMessage(ChatColor.RED + "Failed to spawn bag. Name may already exist.");
            }
            return true;
        }

        // --- EDIT ---
        if (args[0].equalsIgnoreCase("edit")) {
            if (!sender.hasPermission("boxtop.admin")) { sender.sendMessage(dataManager.getMessage("no-permission")); return true; }
            if (!(sender instanceof Player)) { sender.sendMessage(dataManager.getMessage("only-players")); return true; }
            if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /boxtop edit <name>"); return true; }
            String bagName = args[1].toLowerCase();
            if (dataManager.getBag(bagName) == null) { sender.sendMessage(dataManager.getMessage("entity-not-found").replace("{name}", bagName)); return true; }
            configGUI.open((Player) sender, bagName);
            return true;
        }

        // --- LIST ---
        if (args[0].equalsIgnoreCase("list")) {
            if (!sender.hasPermission("boxtop.admin")) { sender.sendMessage(dataManager.getMessage("no-permission")); return true; }
            Set<String> bags = dataManager.getBagNames();
            if (bags.isEmpty()) { sender.sendMessage(dataManager.getMessage("list-empty")); return true; }
            sender.sendMessage(dataManager.getRawMessage("messages.list-header"));
            for (String name : bags) {
                BagData bag = dataManager.getBag(name);
                String typeTag = (bag != null && bag.isSpawned())
                    ? ChatColor.LIGHT_PURPLE + "[S] "   // S = Spawned
                    : ChatColor.DARK_AQUA   + "[E] ";   // E = External entity

                TextComponent line = new TextComponent(ChatColor.GRAY + "- " + typeTag + ChatColor.YELLOW + name + " ");

                TextComponent tp = new TextComponent(ChatColor.AQUA + "[TP]");
                tp.setBold(true);
                tp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/boxtop tp " + name));
                tp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Teleport").create()));

                TextComponent edit = new TextComponent(ChatColor.GREEN + " [EDIT]");
                edit.setBold(true);
                edit.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/boxtop edit " + name));
                edit.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Open config GUI").create()));

                TextComponent del = new TextComponent(ChatColor.RED + " [REMOVE]");
                del.setBold(true);
                del.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/boxtop unsetentity " + name));
                del.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Delete bag").create()));

                line.addExtra(tp);
                line.addExtra(edit);
                line.addExtra(del);

                if (sender instanceof Player) ((Player) sender).spigot().sendMessage(line);
                else sender.sendMessage("- " + name);
            }
            return true;
        }

        // --- TP ---
        if (args[0].equalsIgnoreCase("tp")) {
            if (!sender.hasPermission("boxtop.admin")) return true;
            if (!(sender instanceof Player)) return true;
            if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /boxtop tp <name>"); return true; }
            UUID uuid = dataManager.getBagUUID(args[1].toLowerCase());
            if (uuid == null) { sender.sendMessage(ChatColor.RED + "Bag not found."); return true; }
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) { ((Player) sender).teleport(entity.getLocation()); sender.sendMessage(ChatColor.GREEN + "Teleported to " + args[1]); }
            else sender.sendMessage(ChatColor.RED + "Entity not loaded.");
            return true;
        }

        // --- SET ENTITY ---
        if (args[0].equalsIgnoreCase("setentity")) {
            if (!sender.hasPermission("boxtop.admin")) return true;
            if (!(sender instanceof Player)) return true;
            if (args.length < 2) { sender.sendMessage(dataManager.getMessage("usage-set")); return true; }
            Player player = (Player) sender;
            String bagName = args[1].toLowerCase();
            if (dataManager.getBagUUID(bagName) != null) { sender.sendMessage(ChatColor.RED + "Name already exists!"); return true; }
            Entity target = getTargetEntity(player, 5);
            if (target != null) {
                dataManager.addBoxingBag(bagName, target.getUniqueId());
                glowManager.updateGlow();
                sender.sendMessage(dataManager.getMessage("entity-set").replace("{name}", bagName));
            } else {
                sender.sendMessage(ChatColor.RED + "No valid entity found. Look directly at the mob.");
            }
            return true;
        }

        // --- UNSET ENTITY ---
        if (args[0].equalsIgnoreCase("unsetentity")) {
            if (!sender.hasPermission("boxtop.admin")) return true;
            if (args.length < 2) { sender.sendMessage(dataManager.getMessage("usage-unset")); return true; }
            String bagName = args[1].toLowerCase();
            BagData bag = dataManager.getBag(bagName);
            UUID bagUUID = dataManager.getBagUUID(bagName);

            // Si es un saco spawneado, eliminar el ArmorStand físicamente
            if (bag != null && bag.isSpawned()) {
                spawnManager.removeBag(bag);
            }

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
            if (toEntity.clone().normalize().dot(direction) < 0.7) continue;
            double distToLine = toEntity.clone().crossProduct(direction).length();
            if (distToLine < 2.0) {
                double dist = entityLoc.distance(eye);
                if (dist < minDistance) { minDistance = dist; target = e; }
            }
        }
        return target;
    }
}
