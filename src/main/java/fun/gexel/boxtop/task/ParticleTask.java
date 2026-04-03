package fun.gexel.boxtop.tasks;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.DataManager;
import fun.gexel.boxtop.managers.GlowManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

public class ParticleTask extends BukkitRunnable {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private final GlowManager glowManager;
    
    // VARIABLES CACHE (Para no leer config cada tick)
    private Particle cachedParticle;
    private int cachedAmount;
    private double cachedOffsetY;
    private double cachedSpeed;
    private boolean isEnabled;

    public ParticleTask(BoxTopPlugin plugin, DataManager dataManager, GlowManager glowManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.glowManager = glowManager;
        reloadVariables(); // Carga inicial
    }

    /**
     * Este método se llamará DESDE EL COMANDO /bt reload.
     * Lee la configuración una sola vez y guarda los valores en memoria.
     */
    public void reloadVariables() {
        this.isEnabled = plugin.getConfig().getBoolean("particles.enabled", true);
        
        if (isEnabled) {
            String name = plugin.getConfig().getString("particles.type", "VILLAGER_HAPPY").toUpperCase();
            this.cachedParticle = resolveParticle(name);
            this.cachedAmount = plugin.getConfig().getInt("particles.amount", 1);
            this.cachedOffsetY = plugin.getConfig().getDouble("particles.offset-y", 2.2);
            this.cachedSpeed = plugin.getConfig().getDouble("particles.speed", 0.0);
            
            if (this.cachedParticle != null) {
                // Log limpio en consola para confirmar el cambio
                plugin.getLogger().info("[BoxTop] Particles loaded: " + this.cachedParticle.name());
            }
        }
    }

    // Traductor inteligente (Tu lógica Cross-Version intacta)
    private Particle resolveParticle(String name) {
        try { return Particle.valueOf(name); } catch (IllegalArgumentException e) {}

        String alternative = null;
        if (name.equals("VILLAGER_HAPPY")) alternative = "HAPPY_VILLAGER";
        else if (name.equals("HAPPY_VILLAGER")) alternative = "VILLAGER_HAPPY";
        else if (name.equals("TOTEM")) alternative = "TOTEM_OF_UNDYING";
        else if (name.equals("TOTEM_OF_UNDYING")) alternative = "TOTEM";
        else if (name.contains("EXPLOSION")) alternative = "EXPLOSION_NORMAL";
        else if (name.equals("FIREWORKS_SPARK")) alternative = "FIREWORK";

        if (alternative != null) {
            try { return Particle.valueOf(alternative); } catch (IllegalArgumentException ignored) {}
        }
        
        // Fallback silencioso a HEART si falla todo
        try { return Particle.valueOf("HEART"); } catch (Exception ignored) {}
        try { return Particle.valueOf("REDSTONE"); } catch (Exception ignored) {}
        return null;
    }

    @Override
    public void run() {
        // 1. MANTENIMIENTO DE GLOW (Siempre activo)
        if (plugin.getConfig().getBoolean("glow.enabled", true)) {
             glowManager.updateGlow(); 
        }

        // 2. RENDERIZADO OPTIMIZADO
        // Usamos las variables cacheadas. Cero lectura de disco/config aquí.
        if (!isEnabled || cachedParticle == null) return;

        for (UUID uuid : dataManager.getAllBagUUIDs()) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && entity.isValid()) {
                Location loc = entity.getLocation().add(0, cachedOffsetY, 0);
                try {
                    entity.getWorld().spawnParticle(cachedParticle, loc, cachedAmount, 0.2, 0.2, 0.2, cachedSpeed);
                } catch (Exception ignored) {}
            }
        }
    }
}