package fun.gexel.boxtop.listeners;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.BanManager;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class WandListener implements Listener {

    private final BoxTopPlugin plugin;
    private final BanManager banManager;

    public WandListener(BoxTopPlugin plugin, BanManager banManager) {
        this.plugin = plugin;
        this.banManager = banManager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onWandInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || (!item.getType().name().contains("GOLD") || !item.getType().name().contains("AXE"))) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) return;
        if (!meta.getDisplayName().contains("BoxTop Ban Zone Wand")) return;

        // Ensure player has admin permissions
        if (!player.hasPermission("boxtop.admin")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use the BoxTop Ban Zone Wand.");
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) {
            // Check if right clicking air or shift clicking air
            return;
        }

        event.setCancelled(true);

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            banManager.setSelectionCorner1(player, block.getLocation());
            player.sendMessage(ChatColor.YELLOW + "✦ Pos 1 set to (" + block.getX() + ", " + block.getY() + ", " + block.getZ() + ")!");
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            banManager.setSelectionCorner2(player, block.getLocation());
            player.sendMessage(ChatColor.YELLOW + "✦ Pos 2 set to (" + block.getX() + ", " + block.getY() + ", " + block.getZ() + ")!");
        }

        // If both corners are now selected, confirm and remove wand
        Location c1 = banManager.getSelectionCorner1(player);
        Location c2 = banManager.getSelectionCorner2(player);

        if (c1 != null && c2 != null && c1.getWorld() != null && c1.getWorld().equals(c2.getWorld())) {
            player.sendMessage(ChatColor.GREEN + "✦ Ban Zone perimeter selected from (" + c1.getBlockX() + ", " + c1.getBlockZ() + ") to (" + c2.getBlockX() + ", " + c2.getBlockZ() + ")!");
            player.sendMessage(ChatColor.GRAY + "  ▪ " + ChatColor.YELLOW + "[Auto Mode]: " + ChatColor.WHITE + "Run /boxtop admin generate [spacing] to auto-build 3x3 structures");
            player.sendMessage(ChatColor.GRAY + "  ▪ " + ChatColor.YELLOW + "[Manual Mode]: " + ChatColor.WHITE + "Run /boxtop admin addpost while looking at a fence");

            // Remove the wand from main hand automatically
            player.getInventory().setItemInMainHand(null);

            // Trigger temporary boundary particle animation so admin sees the perimeter
            spawnBoundaryParticles(player, c1, c2);
        }
    }

    private void spawnBoundaryParticles(Player player, Location c1, Location c2) {
        World world = c1.getWorld();
        if (world == null) return;

        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());
        int y = Math.max(c1.getBlockY(), c2.getBlockY()) + 1;

        // Run particle task for 5 seconds (10 iterations of 10 ticks)
        new org.bukkit.scheduler.BukkitRunnable() {
            int count = 0;
            @Override
            public void run() {
                if (count++ > 10 || !player.isOnline()) {
                    this.cancel();
                    return;
                }

                for (double x = minX; x <= maxX + 1; x += 0.5) {
                    spawnParticleSafe(world, new Location(world, x, y, minZ));
                    spawnParticleSafe(world, new Location(world, x, y, maxZ + 1));
                }
                for (double z = minZ; z <= maxZ + 1; z += 0.5) {
                    spawnParticleSafe(world, new Location(world, minX, y, z));
                    spawnParticleSafe(world, new Location(world, maxX + 1, y, z));
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void spawnParticleSafe(World world, Location loc) {
        try {
            // Try 1.13+ FLAME or REDSTONE particle
            org.bukkit.Particle particle = org.bukkit.Particle.valueOf("FLAME");
            world.spawnParticle(particle, loc, 1, 0, 0, 0, 0);
        } catch (Throwable t) {
            try {
                // 1.12.2 compatibility
                @SuppressWarnings("deprecation")
                org.bukkit.Effect effect = org.bukkit.Effect.valueOf("MOBSPAWNER_FLAMES");
                world.playEffect(loc, effect, 0);
            } catch (Exception ignored) {}
        }
    }
}
