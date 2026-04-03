package fun.gexel.boxtop.managers;

import com.cryptomorin.xseries.XSound;
import fun.gexel.boxtop.BoxTopPlugin;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import java.util.Optional;

public class SoundManager {

    private final BoxTopPlugin plugin;

    public SoundManager(BoxTopPlugin plugin) {
        this.plugin = plugin;
    }

    public void playHitSound(Player player, boolean isMuscleHit) {
        if (!plugin.getConfig().getBoolean("sounds.enabled")) return;

        String path = isMuscleHit ? "sounds.muscle-sound" : "sounds.hit-sound";
        // Valor por defecto seguro para 1.21
        String soundName = plugin.getConfig().getString(path, "ENTITY_PLAYER_ATTACK_STRONG");
        
        float volume = (float) plugin.getConfig().getDouble("sounds.volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble("sounds.pitch", 1.0);

        playSoundSafe(player, soundName, volume, pitch);
    }

    public void playLevelUpSound(Player player) {
        if (!plugin.getConfig().getBoolean("musculature.enabled")) return;
        String soundName = plugin.getConfig().getString("musculature.level-up-sound", "ENTITY_PLAYER_LEVELUP");
        playSoundSafe(player, soundName, 1.0f, 1.0f);
    }
    
    public void playCustomSound(Player player, String configPath) {
        String soundName = plugin.getConfig().getString(configPath);
        if (soundName != null && !soundName.isEmpty()) {
            playSoundSafe(player, soundName, 1.0f, 1.0f);
        }
    }

    private void playSoundSafe(Player player, String soundName, float volume, float pitch) {
        // DEBUG: Esto saldrá en la consola. Si lo ves, el código funciona.
        // plugin.getLogger().info("DEBUG: Intentando reproducir sonido: " + soundName);

        // 1. INTENTO NATIVO (Mejor para 1.21)
        // Si el nombre en la config es exacto para la versión actual, úsalo directamente.
        try {
            Sound nativeSound = Sound.valueOf(soundName.toUpperCase());
            player.playSound(player.getLocation(), nativeSound, volume, pitch);
            return; // ¡Éxito! Salimos.
        } catch (IllegalArgumentException e) {
            // No es un sonido nativo de esta versión, pasamos al Plan B.
        }

        // 2. INTENTO XSERIES (Compatibilidad 1.12)
        Optional<XSound> xSound = XSound.matchXSound(soundName);
        if (xSound.isPresent()) {
            xSound.get().play(player, volume, pitch);
        } else {
            // Si llega aquí, el nombre está mal escrito en la config
            plugin.getLogger().warning("Sonido no encontrado/inválido: " + soundName);
        }
    }
}