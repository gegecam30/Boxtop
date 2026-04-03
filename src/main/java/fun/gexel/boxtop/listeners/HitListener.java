package fun.gexel.boxtop.listeners;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.*;
import fun.gexel.boxtop.objects.PlayerStat;
import net.md_5.bungee.api.ChatMessageType; // Importante
import net.md_5.bungee.api.chat.TextComponent; // Importante
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

import java.util.UUID;

public class HitListener implements Listener {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private final RewardManager rewardManager;
    private final StaminaManager staminaManager;
    private final SoundManager soundManager;
    private final DuelManager duelManager;

    public HitListener(BoxTopPlugin plugin, DataManager dataManager, RewardManager rewardManager, 
                       StaminaManager staminaManager, SoundManager soundManager, DuelManager duelManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.rewardManager = rewardManager;
        this.staminaManager = staminaManager;
        this.soundManager = soundManager;
        this.duelManager = duelManager;
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!dataManager.isBoxingBag(event.getEntity().getUniqueId())) return;

        Entity bag = event.getEntity();
        UUID bagUUID = bag.getUniqueId();
        healBag(bag);

        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();

            // LOGICA DUELOS
            if (duelManager.isBagInDuel(bagUUID)) {
                if (duelManager.isPlayerInDuel(bagUUID, player)) {
                    double damage = event.getFinalDamage();
                    duelManager.addDuelDamage(bagUUID, player, damage);
                    soundManager.playHitSound(player, false);
                    applyPhysics(player, bag);
                } else {
                    player.sendMessage(dataManager.getMessage("duels.messages.bag-busy"));
                }
                event.setCancelled(true);
                return;
            }

            // LOGICA ENTRENAMIENTO
            if (staminaManager.canHit(player)) {
                processPlayerHit(player, event.getFinalDamage(), bag);
            }

        } else {
            // Mobs ignorados
        }

        event.setCancelled(true);
    }

    private void processPlayerHit(Player player, double baseDamage, Entity bag) {
        double bonusDamage = 0.0;
        PlayerStat stat = dataManager.getPlayerStat(player.getUniqueId());
        double currentTotalDamage = (stat != null) ? stat.getDamage() : 0.0;

        if (plugin.getConfig().getBoolean("musculature.enabled")) {
            double threshold = plugin.getConfig().getDouble("musculature.damage-threshold", 2000.0);
            double bonusPerLevel = plugin.getConfig().getDouble("musculature.bonus-damage", 0.5);
            double maxBonus = plugin.getConfig().getDouble("musculature.max-bonus", 20.0);

            if (threshold > 0) {
                int strengthLevel = (int) (currentTotalDamage / threshold);
                bonusDamage = strengthLevel * bonusPerLevel;
                if (bonusDamage > maxBonus) bonusDamage = maxBonus;
            }
        }

        double finalDamage = baseDamage + bonusDamage;

        dataManager.addDamage(player.getUniqueId(), player.getName(), finalDamage);
        rewardManager.checkReward(player, currentTotalDamage, currentTotalDamage + finalDamage);

        boolean isMuscleHit = bonusDamage > 0;
        soundManager.playHitSound(player, isMuscleHit);

        String dmgText = String.format("%.1f", finalDamage);
        if (isMuscleHit) {
            dmgText += ChatColor.GOLD + " (+" + String.format("%.1f", bonusDamage) + " STR)";
        }
        
        String msg = dataManager.getRawMessage("hit-actionbar").replace("{damage}", dmgText);
        
        // --- FIX ACTION BAR (1.12 - 1.21 COMPATIBLE) ---
        // Usamos TextComponent.fromLegacyText para que los colores funcionen bien en versiones nuevas
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
        } catch (Exception e) {
            // Fallback muy raro si spigot no está actualizado
            player.sendMessage(msg); 
        }
        // -----------------------------------------------

        applyPhysics(player, bag);
    }

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

    private void applyPhysics(Player player, Entity bag) {
        if (plugin.getConfig().getBoolean("physics.enabled")) {
            double knockback = plugin.getConfig().getDouble("physics.knockback-strength", 0.4);
            double vertical = plugin.getConfig().getDouble("physics.vertical-strength", 0.1);
            
            Vector direction = player.getLocation().getDirection().normalize().multiply(knockback).setY(vertical);
            bag.setVelocity(direction);
        }
    }
}