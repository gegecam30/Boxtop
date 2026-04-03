package fun.gexel.boxtop.managers;

import fun.gexel.boxtop.BoxTopPlugin;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StaminaManager {

    private final BoxTopPlugin plugin;
    
    // Almacena cuándo termina el cooldown del jugador
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    
    // Almacena cuántos golpes lleva en la ráfaga actual
    private final Map<UUID, Integer> burstHits = new HashMap<>();
    
    // Almacena la última vez que golpeó (para resetear la ráfaga si descansa)
    private final Map<UUID, Long> lastHitTime = new HashMap<>();

    public StaminaManager(BoxTopPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean canHit(Player player) {
        if (!plugin.getConfig().getBoolean("stamina.enabled")) return true;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // 1. Chequear si está en Cooldown (Agotado)
        if (cooldowns.containsKey(uuid)) {
            long cooldownEnd = cooldowns.get(uuid);
            if (now < cooldownEnd) {
                long secondsLeft = (cooldownEnd - now) / 1000;
                String msg = plugin.getConfig().getString("stamina.message-tired", "&cTired ({time}s)")
                        .replace("{time}", String.valueOf(secondsLeft));
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                return false; // BLOQUEAR GOLPE
            } else {
                // El cooldown terminó
                cooldowns.remove(uuid);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                        plugin.getConfig().getString("stamina.message-recovered", "&aRecovered!")));
                burstHits.remove(uuid); // Reiniciar ráfaga
            }
        }

        // 2. Lógica de Ráfaga (Burst)
        int maxHits = plugin.getConfig().getInt("stamina.max-hits", 20);
        int resetTimeSeconds = plugin.getConfig().getInt("stamina.reset-burst-time", 5);
        
        long lastHit = lastHitTime.getOrDefault(uuid, 0L);
        
        // Si descansó unos segundos, reiniciamos el contador de ráfaga
        if (now - lastHit > (resetTimeSeconds * 1000L)) {
            burstHits.put(uuid, 0);
        }

        // Aumentar contador
        int currentHits = burstHits.getOrDefault(uuid, 0) + 1;
        burstHits.put(uuid, currentHits);
        lastHitTime.put(uuid, now);

        // Si llegó al límite -> Aplicar cansancio
        if (currentHits >= maxHits) {
            int cooldownSeconds = plugin.getConfig().getInt("stamina.cooldown-duration", 60);
            cooldowns.put(uuid, now + (cooldownSeconds * 1000L));
            
            // Avisar inmediatamente que se cansó
            String msg = plugin.getConfig().getString("stamina.message-tired", "&cTired ({time}s)")
                        .replace("{time}", String.valueOf(cooldownSeconds));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return false;
        }

        return true; // Puede golpear
    }
}