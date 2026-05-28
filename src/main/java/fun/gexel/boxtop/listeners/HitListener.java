package fun.gexel.boxtop.listeners;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.*;
import fun.gexel.boxtop.objects.BagData;
import fun.gexel.boxtop.objects.PlayerStat;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class HitListener implements Listener {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private final RewardManager rewardManager;
    private final StaminaManager staminaManager;
    private final SoundManager soundManager;
    private final DuelManager duelManager;

    // --- DETECCIÓN DE GOLPES RÁPIDOS (para daño de retorno en saco de LANA) ---
    // Almacena el timestamp del último golpe de cada jugador por saco
    private final Map<UUID, Long> lastHitMap = new HashMap<>();

    // Umbral: si golpea en menos de 3 ticks (150ms) consecutivos → daño de retorno
    private static final long RAPID_HIT_THRESHOLD_MS = 150L;
    // Daño de retorno al jugador cuando golpea muy rápido un saco de LANA
    private static final double RETURN_DAMAGE = 0.5;

    public HitListener(BoxTopPlugin plugin, DataManager dataManager, RewardManager rewardManager,
                       StaminaManager staminaManager, SoundManager soundManager, DuelManager duelManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.rewardManager = rewardManager;
        this.staminaManager = staminaManager;
        this.soundManager = soundManager;
        this.duelManager = duelManager;
    }

    // -------------------------------------------------------
    // EVENTO PRINCIPAL DE GOLPE
    // -------------------------------------------------------

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        UUID bagUUID = event.getEntity().getUniqueId();
        if (!dataManager.isBoxingBag(bagUUID)) return;

        Entity bag = event.getEntity();
        healBag(bag);

        // Obtenemos el BagData del saco golpeado
        BagData bagData = dataManager.getBagByUUID(bagUUID);

        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();

            // --- LÓGICA DE DUELOS (sin cambios) ---
            if (duelManager.isBagInDuel(bagUUID)) {
                if (duelManager.isPlayerInDuel(bagUUID, player)) {
                    double damage = event.getFinalDamage();
                    duelManager.addDuelDamage(bagUUID, player, damage);
                    soundManager.playHitSound(player, false);
                    if (bagData != null) applyPhysics(player, bag, bagData);
                } else {
                    player.sendMessage(dataManager.getMessage("duels.messages.bag-busy"));
                }
                event.setCancelled(true);
                return;
            }

            // --- LÓGICA DE ENTRENAMIENTO ---
            if (staminaManager.canHit(player)) {
                if (bagData != null) {
                    processPlayerHit(player, event.getFinalDamage(), bag, bagData);
                } else {
                    // Fallback: si por alguna razón no hay BagData, usar config global
                    processPlayerHitLegacy(player, event.getFinalDamage(), bag);
                }
            }
        }

        event.setCancelled(true);
    }

    // -------------------------------------------------------
    // PROCESAMIENTO DE GOLPE CON BagData
    // -------------------------------------------------------

    private void processPlayerHit(Player player, double baseDamage, Entity bag, BagData bagData) {
        double bonusDamage = 0.0;
        PlayerStat stat = dataManager.getPlayerStat(player.getUniqueId());
        double currentTotalDamage = (stat != null) ? stat.getDamage() : 0.0;

        // --- SISTEMA DE MUSCULATURA (usa config global, es un stat del jugador) ---
        if (plugin.getConfig().getBoolean("musculature.enabled")) {
            double threshold    = plugin.getConfig().getDouble("musculature.damage-threshold", 2000.0);
            double bonusPerLevel = plugin.getConfig().getDouble("musculature.bonus-damage", 0.5);
            double maxBonus     = plugin.getConfig().getDouble("musculature.max-bonus", 20.0);

            if (threshold > 0) {
                int strengthLevel = (int) (currentTotalDamage / threshold);
                bonusDamage = Math.min(strengthLevel * bonusPerLevel, maxBonus);
            }
        }

        // --- MODIFICADOR POR MATERIAL DEL SACO ---
        // El tipo de saco afecta cómo se registra el daño
        double materialDamageMultiplier = getMaterialDamageMultiplier(bagData.getMaterial());
        double finalDamage = (baseDamage + bonusDamage) * materialDamageMultiplier;

        // --- DAÑO DE RETORNO (solo saco de LANA) ---
        if (bagData.getMaterial().isReturnDamageEnabled()) {
            checkAndApplyReturnDamage(player, bag.getUniqueId());
        }

        // --- GUARDAR DAÑO Y RECOMPENSAS ---
        dataManager.addDamage(player.getUniqueId(), player.getName(), finalDamage);
        rewardManager.checkReward(player, currentTotalDamage, currentTotalDamage + finalDamage);

        // --- FEEDBACK VISUAL Y SONIDO ---
        boolean isMuscleHit = bonusDamage > 0;
        soundManager.playHitSoundForMaterial(player, isMuscleHit, bagData.getMaterial());
        sendActionBar(player, finalDamage, bonusDamage, bagData.getMaterial());

        // --- FÍSICAS DEL SACO (per-bag) ---
        applyPhysics(player, bag, bagData);
    }

    /**
     * Determina si el jugador golpeó muy rápido y aplica daño de retorno.
     * Simula el cansancio/herida del box real al golpear sin control.
     */
    private void checkAndApplyReturnDamage(Player player, UUID bagUUID) {
        long now = System.currentTimeMillis();
        // Usamos una clave combinada jugador+saco para tracking individual
        UUID trackKey = new UUID(player.getUniqueId().getMostSignificantBits() ^ bagUUID.getMostSignificantBits(),
                                 player.getUniqueId().getLeastSignificantBits() ^ bagUUID.getLeastSignificantBits());

        Long lastHit = lastHitMap.get(trackKey);
        lastHitMap.put(trackKey, now);

        if (lastHit != null && (now - lastHit) < RAPID_HIT_THRESHOLD_MS) {
            // Golpe demasiado rápido — daño de retorno
            player.damage(RETURN_DAMAGE);
            player.sendMessage(ChatColor.RED + "¡Golpeaste muy rápido! Tu mano sufre el impacto.");
        }
    }

    /**
     * Multiplicador de daño registrado según material.
     * Saco de piedra registra menos daño (requiere más fuerza).
     */
    private double getMaterialDamageMultiplier(BagData.BagMaterial material) {
        switch (material) {
            case STONE: return 0.6; // Registra menos daño — es más duro
            case WOOL:  return 1.2; // Registra ligeramente más daño — cede
            default:    return 1.0; // SAND: normal
        }
    }

    // -------------------------------------------------------
    // FÍSICAS PER-BAG
    // -------------------------------------------------------

    /**
     * Aplica físicas usando los valores específicos del saco.
     * Si physicsEnabled=false en el saco, getEffectiveKnockback() devuelve 0.
     */
    private void applyPhysics(Player player, Entity bag, BagData bagData) {
        double knockback = bagData.getEffectiveKnockback();
        double vertical  = bagData.getEffectiveVertical();

        if (knockback == 0.0 && vertical == 0.0) return; // Físicas desactivadas o material stone sin movimiento

        Vector direction = player.getLocation().getDirection()
                .normalize()
                .multiply(knockback)
                .setY(vertical);
        bag.setVelocity(direction);
    }

    // -------------------------------------------------------
    // ACTION BAR
    // -------------------------------------------------------

    private void sendActionBar(Player player, double finalDamage, double bonusDamage, BagData.BagMaterial material) {
        String dmgText = String.format("%.1f", finalDamage);

        if (bonusDamage > 0) {
            dmgText += ChatColor.GOLD + " (+" + String.format("%.1f", bonusDamage) + " STR)";
        }

        // Indicador de material si no es el default
        if (material != BagData.BagMaterial.SAND) {
            dmgText += ChatColor.GRAY + " [" + material.getDisplayName() + "]";
        }

        String msg = dataManager.getRawMessage("hit-actionbar").replace("{damage}", dmgText);

        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
        } catch (Exception e) {
            player.sendMessage(msg);
        }
    }

    // -------------------------------------------------------
    // FALLBACK LEGACY (por si getBagByUUID devuelve null en casos extremos)
    // -------------------------------------------------------

    private void processPlayerHitLegacy(Player player, double baseDamage, Entity bag) {
        dataManager.addDamage(player.getUniqueId(), player.getName(), baseDamage);
        soundManager.playHitSound(player, false);

        if (plugin.getConfig().getBoolean("physics.enabled")) {
            double knockback = plugin.getConfig().getDouble("physics.knockback-strength", 0.4);
            double vertical  = plugin.getConfig().getDouble("physics.vertical-strength", 0.1);
            Vector dir = player.getLocation().getDirection().normalize().multiply(knockback).setY(vertical);
            bag.setVelocity(dir);
        }
    }

    // -------------------------------------------------------
    // DAÑO AMBIENTAL Y FUEGO (sin cambios)
    // -------------------------------------------------------

    @EventHandler
    public void onEnvironmentalDamage(EntityDamageEvent event) {
        if (!dataManager.isBoxingBag(event.getEntity().getUniqueId())) return;

        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK &&
            event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE &&
            event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {

            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.FIRE ||
                event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK ||
                event.getCause() == EntityDamageEvent.DamageCause.LAVA) {
                event.getEntity().setFireTicks(0);
            }
        }
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (dataManager.isBoxingBag(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------
    // HELPER: CURAR EL SACO
    // -------------------------------------------------------

    private void healBag(Entity bag) {
        if (bag instanceof LivingEntity) {
            LivingEntity liveBag = (LivingEntity) bag;
            double maxHealth = 20.0;
            try {
                if (liveBag.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                    maxHealth = liveBag.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
                } else {
                    maxHealth = liveBag.getMaxHealth();
                }
            } catch (Throwable e) {
                maxHealth = liveBag.getMaxHealth();
            }
            liveBag.setHealth(maxHealth);
            liveBag.setFireTicks(0);
        }
    }
}
