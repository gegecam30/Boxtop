package fun.gexel.boxtop.listeners;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.GlowManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class GlowListener implements Listener {

    private final BoxTopPlugin plugin;
    private final GlowManager glowManager;

    public GlowListener(BoxTopPlugin plugin, GlowManager glowManager) {
        this.plugin = plugin;
        this.glowManager = glowManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Retraso para dejar cargar Scoreboards de otros plugins
        new BukkitRunnable() {
            @Override
            public void run() {
                if (event.getPlayer().isOnline()) {
                    glowManager.updateGlowForPlayer(event.getPlayer());
                }
            }
        }.runTaskLater(plugin, 20L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (event.getPlayer().isOnline()) {
                    glowManager.updateGlowForPlayer(event.getPlayer());
                }
            }
        }.runTaskLater(plugin, 10L);
    }
}