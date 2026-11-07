package fun.gexel.boxtop.listeners;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.BanManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * BanListener — intercepts all actions by banned players hanging on the BoxTop post.
 * Enforces Option A + B (event cancellation + Weakness/Mining Fatigue) and broadcasts mock taunts.
 */
public class BanListener implements Listener {

    private final BoxTopPlugin plugin;
    private final BanManager banManager;

    public BanListener(BoxTopPlugin plugin, BanManager banManager) {
        this.plugin = plugin;
        this.banManager = banManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!banManager.isBanned(player)) return;

        BanManager.BannedPlayerEntry entry = banManager.getBannedEntry(player.getUniqueId());
        if (entry == null) return;
        Location assigned = banManager.getAssignedLocation(entry);
        if (assigned == null || assigned.getWorld() == null) return;

        if (!event.getTo().getWorld().equals(assigned.getWorld())) {
            event.setTo(assigned.clone());
        } else {
            if (event.getTo().distanceSquared(assigned) > 36.0) { // 6 blocks hard clamp
                event.setTo(assigned.clone());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!banManager.isBanned(player)) return;

        event.setCancelled(true);

        if (banManager.isMockMessagesEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> player.chat(banManager.getRandomMockMessage()));
        } else {
            player.sendMessage(ChatColor.RED + "✦ You are hanging from the BoxTop post! You cannot execute commands.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!banManager.isBanned(player)) return;

        if (banManager.isMockMessagesEnabled()) {
            event.setMessage(banManager.getRandomMockMessage());
        } else {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "✦ Punching bags cannot talk!");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player && banManager.isBanned(event.getDamager().getUniqueId())) {
            // Option A: completely cancel damage dealt by hanging player
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (banManager.isBanned(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (banManager.isBanned(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (banManager.isBanned(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (banManager.isBanned(player)) {
            banManager.spawnOfflineDummy(player.getUniqueId(), player.getName());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (banManager.isBanned(player)) {
            BanManager.BannedPlayerEntry entry = banManager.getBannedEntry(player.getUniqueId());
            if (entry != null) {
                if (entry.isExpired()) {
                    banManager.unbanPlayer(player.getUniqueId());
                    player.sendMessage(ChatColor.GREEN + "✦ Your BoxTop ban duration has expired! You are now free.");
                    return;
                }
                Location assigned = banManager.getAssignedLocation(entry);
                if (assigned != null && assigned.getWorld() != null) {
                    player.teleport(assigned);
                    banManager.setupOnlineLeash(player);
                }
                // Option B: Apply permanent Weakness & Mining Fatigue
                applyStatusPunishment(player);
            }
        }
    }

    private void applyStatusPunishment(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, Integer.MAX_VALUE, 255, false, false));
        try {
            // SLOW_DIGGING is Mining Fatigue in Bukkit
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_DIGGING, Integer.MAX_VALUE, 255, false, false));
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerUnleash(org.bukkit.event.player.PlayerUnleashEntityEvent event) {
        if (banManager.isBanBagTarget(event.getEntity()) || 
           (event.getEntity().getCustomName() != null && event.getEntity().getCustomName().contains("BoxTop Leash"))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "✦ You cannot unleash a banned target from the BoxTop post!");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityUnleash(org.bukkit.event.entity.EntityUnleashEvent event) {
        if (banManager.isBanBagTarget(event.getEntity()) || 
           (event.getEntity().getCustomName() != null && event.getEntity().getCustomName().contains("BoxTop Leash"))) {
            if (event.getEntity() instanceof org.bukkit.entity.LivingEntity) {
                org.bukkit.entity.LivingEntity live = (org.bukkit.entity.LivingEntity) event.getEntity();
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (live.isValid()) {
                        try {
                            for (org.bukkit.entity.Entity drop : live.getWorld().getNearbyEntities(live.getLocation(), 3, 3, 3)) {
                                if (drop instanceof org.bukkit.entity.Item && ((org.bukkit.entity.Item) drop).getItemStack().getType().name().contains("LEAD")) {
                                    drop.remove();
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }, 1L);
            }
        }
    }
}
