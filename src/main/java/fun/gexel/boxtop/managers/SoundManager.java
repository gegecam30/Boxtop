package fun.gexel.boxtop.managers;

import com.cryptomorin.xseries.XSound;
import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.objects.BagData;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Optional;

public class SoundManager {

    private final BoxTopPlugin plugin;

    public SoundManager(BoxTopPlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------
    // API PÚBLICA
    // -------------------------------------------------------

    /** Sonido estándar de golpe (para compatibilidad con código existente) */
    public void playHitSound(Player player, boolean isMuscleHit) {
        if (!plugin.getConfig().getBoolean("sounds.enabled")) return;

        String path = isMuscleHit ? "sounds.muscle-sound" : "sounds.hit-sound";
        String soundName = plugin.getConfig().getString(path, "ENTITY_PLAYER_ATTACK_WEAK");
        float volume = (float) plugin.getConfig().getDouble("sounds.volume", 1.0);
        float pitch  = (float) plugin.getConfig().getDouble("sounds.pitch", 1.0);

        playSoundSafe(player, soundName, volume, pitch);
    }

    /**
     * Sonido de golpe con variación por material del saco.
     * Cada material tiene su sonido característico.
     */
    public void playHitSoundForMaterial(Player player, boolean isMuscleHit, BagData.BagMaterial material) {
        if (!plugin.getConfig().getBoolean("sounds.enabled")) return;

        float volume = (float) plugin.getConfig().getDouble("sounds.volume", 1.0);
        float pitch  = (float) plugin.getConfig().getDouble("sounds.pitch", 1.0);

        String soundName;

        switch (material) {
            case STONE:
                // Golpe pesado y sordo — como golpear piedra
                soundName = isMuscleHit
                        ? "BLOCK_STONE_BREAK"
                        : "BLOCK_STONE_HIT";
                break;

            case WOOL:
                // Golpe suave y apagado — como golpear lana
                soundName = isMuscleHit
                        ? "BLOCK_WOOL_BREAK"
                        : "BLOCK_WOOL_HIT";
                break;

            case SAND:
            default:
                // Comportamiento original del config.yml
                String path = isMuscleHit ? "sounds.muscle-sound" : "sounds.hit-sound";
                soundName = plugin.getConfig().getString(path, "ENTITY_PLAYER_ATTACK_WEAK");
                break;
        }

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

    // -------------------------------------------------------
    // HELPER INTERNO (cross-version, sin cambios)
    // -------------------------------------------------------

    private void playSoundSafe(Player player, String soundName, float volume, float pitch) {
        // 1. Intento nativo (1.13+)
        try {
            Sound nativeSound = Sound.valueOf(soundName.toUpperCase());
            player.playSound(player.getLocation(), nativeSound, volume, pitch);
            return;
        } catch (IllegalArgumentException ignored) {}

        // 2. XSeries (1.12 compat)
        Optional<XSound> xSound = XSound.matchXSound(soundName);
        if (xSound.isPresent()) {
            xSound.get().play(player, volume, pitch);
            return;
        }

        // 3. Intento como sonido personalizado / de Resource Pack (String)
        try {
            player.playSound(player.getLocation(), soundName.toLowerCase(), volume, pitch);
        } catch (Throwable t) {
            plugin.getLogger().warning("[BoxTop] No se pudo reproducir el sonido: " + soundName);
        }
    }

    /**
     * Plays the piñata burst sound at a world location, audible to all nearby players.
     * Tries modern names first, falls back through legacy names for 1.12 compat.
     */
    public void playPinataSound(Location location) {
        if (location == null || location.getWorld() == null) return;
        String[] candidates = {
            "ENTITY_FIREWORK_ROCKET_LAUNCH",
            "ENTITY_FIREWORK_LAUNCH",
            "FIREWORK_LAUNCH"
        };
        for (String name : candidates) {
            try {
                Sound s = Sound.valueOf(name);
                location.getWorld().playSound(location, s, 1.5f, 0.75f);
                // Play a second celebratory tone slightly higher
                location.getWorld().playSound(location, s, 1.0f, 1.2f);
                return;
            } catch (IllegalArgumentException ignored) {}
        }
    }
}

