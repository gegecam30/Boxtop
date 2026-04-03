package fun.gexel.boxtop.managers;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.glow.GlowHandler;
import fun.gexel.boxtop.managers.glow.GlowLegacy;
import fun.gexel.boxtop.managers.glow.GlowModern;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.logging.Level;

public class GlowManager {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private final GlowHandler handler;

    public GlowManager(BoxTopPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;

        // --- DETECCIÓN MATEMÁTICA DE VERSIÓN ---
        // Parseamos "1.21.1-R0.1..." -> extraemos el 21
        boolean isLegacy = true; 
        String versionRaw = Bukkit.getBukkitVersion(); // Ej: "1.12.2-R0.1-SNAPSHOT"
        
        try {
            // 1. Quitamos lo que esté después del guión ("-R0.1...")
            String cleanVersion = versionRaw.split("-")[0]; 
            // 2. Separamos por puntos: "1.21.1" -> ["1", "21", "1"]
            String[] parts = cleanVersion.split("\\."); 
            
            if (parts.length >= 2) {
                int minor = Integer.parseInt(parts[1]); // Tomamos el segundo número (12 o 21)
                
                // Si es 1.13 o superior, es MODERNO
                if (minor >= 13) {
                    isLegacy = false;
                }
            }
        } catch (Exception e) {
            // Si algo falla, fallback de emergencia (asumimos Legacy por seguridad y avisamos)
            plugin.getLogger().log(Level.WARNING, "Error detectando versión numérica: " + versionRaw, e);
        }

        // --- CARGA DE ESTRATEGIA ---
        if (isLegacy) {
            this.handler = new GlowLegacy();
            plugin.getLogger().info("[BoxTop] Glow System: LEGACY MODE (1.12 detected via '" + versionRaw + "')");
        } else {
            this.handler = new GlowModern();
            plugin.getLogger().info("[BoxTop] Glow System: MODERN MODE (1.13+ detected via '" + versionRaw + "')");
        }
    }

    public void updateGlow() {
        boolean enabled = plugin.getConfig().getBoolean("glow.enabled", false);
        ChatColor color = getColor();
        
        // Pasamos null porque usamos MainScoreboard en ambas estrategias (Lógica v2.3)
        handler.apply(null, dataManager, color, enabled);
    }

    public void updateGlowForPlayer(Player player) {
        boolean enabled = plugin.getConfig().getBoolean("glow.enabled", false);
        ChatColor color = getColor();
        handler.apply(player, dataManager, color, enabled);
    }

    public void removeGlow(UUID uuid) {
        handler.remove(uuid);
    }

    private ChatColor getColor() {
        String colorName = plugin.getConfig().getString("glow.color", "GOLD").toUpperCase();
        try {
            return ChatColor.valueOf(colorName);
        } catch (Exception e) {
            return ChatColor.GOLD;
        }
    }
}