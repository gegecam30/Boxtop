package fun.gexel.boxtop.tasks;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.DataManager;
import fun.gexel.boxtop.managers.GlowManager;
import fun.gexel.boxtop.objects.BagData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;

/**
 * ParticleTask refactorizado para respetar la configuración individual de cada BagData.
 *
 * Problema anterior: usaba un único tipo de partícula cacheado del config.yml global
 * y lo aplicaba a todos los sacos igual.
 *
 * Solución: itera los BagData y usa bag.getParticleType() / bag.isParticlesEnabled()
 * por saco. Mantiene un cache de Particle resuelta por nombre para no resolver
 * cada tick.
 */
public class ParticleTask extends BukkitRunnable {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private final GlowManager glowManager;

    // Cache: nombre de partícula → Particle resuelta
    // Se limpia en reloadVariables() para forzar re-resolución
    private final Map<String, Particle> particleCache = new HashMap<>();

    // Valores globales del config (offsetY, amount, speed) — siguen siendo globales
    // ya que son parámetros de presentación, no de identidad del saco
    private int    cachedAmount;
    private double cachedOffsetY;
    private double cachedSpeed;

    public ParticleTask(BoxTopPlugin plugin, DataManager dataManager, GlowManager glowManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.glowManager = glowManager;
        reloadVariables();
    }

    /**
     * Recarga los parámetros globales de partículas desde config.yml.
     * Los tipos por saco se resuelven dinámicamente desde BagData.
     */
    public void reloadVariables() {
        particleCache.clear(); // forzar re-resolución de todos los tipos
        this.cachedAmount  = plugin.getConfig().getInt("particles.amount", 1);
        this.cachedOffsetY = plugin.getConfig().getDouble("particles.offset-y", 2.2);
        this.cachedSpeed   = plugin.getConfig().getDouble("particles.speed", 0.0);
        plugin.getLogger().info("[BoxTop] Particle parameters reloaded.");
    }

    @Override
    public void run() {
        // 1. Mantenimiento de glow (sin cambios)
        if (plugin.getConfig().getBoolean("glow.enabled", true)) {
            glowManager.updateGlow();
        }

        // 2. Partículas por saco
        for (BagData bag : dataManager.getAllBags()) {
            if (!bag.isParticlesEnabled()) continue;

            Entity entity = Bukkit.getEntity(bag.getUuid());
            if (entity == null || !entity.isValid()) continue;

            // Resolver partícula usando cache
            Particle particle = resolveParticleCached(bag.getParticleType());
            if (particle == null) continue;

            Location loc = entity.getLocation().add(0, cachedOffsetY, 0);
            try {
                entity.getWorld().spawnParticle(
                    particle, loc,
                    cachedAmount,
                    0.2, 0.2, 0.2,
                    cachedSpeed
                );
            } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------
    // RESOLUCIÓN DE PARTÍCULA CON CACHE
    // -------------------------------------------------------

    private Particle resolveParticleCached(String name) {
        if (name == null) return null;
        String key = name.toUpperCase();

        if (particleCache.containsKey(key)) {
            return particleCache.get(key); // puede ser null si falló — evita reintentos cada tick
        }

        Particle resolved = resolveParticle(key);
        particleCache.put(key, resolved); // guardamos null también para no reintentar cada tick
        return resolved;
    }

    /**
     * Traductor cross-version con aliases (idéntico al original).
     */
    private Particle resolveParticle(String name) {
        try { return Particle.valueOf(name); } catch (IllegalArgumentException ignored) {}

        // Aliases cross-version
        String alt = null;
        if (name.equals("VILLAGER_HAPPY"))      alt = "HAPPY_VILLAGER";
        else if (name.equals("HAPPY_VILLAGER")) alt = "VILLAGER_HAPPY";
        else if (name.equals("TOTEM"))          alt = "TOTEM_OF_UNDYING";
        else if (name.equals("TOTEM_OF_UNDYING")) alt = "TOTEM";
        else if (name.contains("EXPLOSION"))    alt = "EXPLOSION_NORMAL";
        else if (name.equals("FIREWORKS_SPARK")) alt = "FIREWORK";

        if (alt != null) {
            try { return Particle.valueOf(alt); } catch (IllegalArgumentException ignored) {}
        }

        // Fallbacks seguros
        try { return Particle.valueOf("HEART"); }   catch (Exception ignored) {}
        try { return Particle.valueOf("REDSTONE"); } catch (Exception ignored) {}
        return null;
    }
}
