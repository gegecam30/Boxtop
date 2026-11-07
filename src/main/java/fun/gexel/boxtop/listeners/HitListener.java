package fun.gexel.boxtop.listeners;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.*;
import fun.gexel.boxtop.objects.BagData;
import fun.gexel.boxtop.objects.PlayerStat;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
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
    private final BanManager banManager;

    private final Map<UUID, Long> lastHitMap = new HashMap<>();

    public HitListener(BoxTopPlugin plugin, DataManager dataManager, RewardManager rewardManager,
                       StaminaManager staminaManager, SoundManager soundManager, DuelManager duelManager,
                       BanManager banManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.rewardManager = rewardManager;
        this.staminaManager = staminaManager;
        this.soundManager = soundManager;
        this.duelManager = duelManager;
        this.banManager = banManager;
    }

    // -------------------------------------------------------
    // GOLPE PRINCIPAL
    // -------------------------------------------------------

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onHit(EntityDamageByEntityEvent event) {
        UUID bagUUID = event.getEntity().getUniqueId();
        boolean isBoxTopBag = dataManager.isBoxingBag(bagUUID);
        boolean isBannedBag = banManager != null && banManager.isBanBagTarget(event.getEntity());

        if (!isBoxTopBag && !isBannedBag) return;

        Entity bag = event.getEntity();
        BagData bagData = isBoxTopBag ? dataManager.getBagByUUID(bagUUID) : null;
        healBag(bag);

        if (!(event.getDamager() instanceof Player)) {
            event.setCancelled(true);
            return;
        }

        Player player = (Player) event.getDamager();

        // --- DUELOS ---
        if (isBoxTopBag && duelManager.isBagInDuel(bagUUID)) {
            if (duelManager.isPlayerInDuel(bagUUID, player)) {
                duelManager.addDuelDamage(bagUUID, player, event.getFinalDamage());
                soundManager.playHitSound(player, false);
                if (bagData != null) applyPhysics(player, bag, bagData);
            } else {
                player.sendMessage(dataManager.getMessage("duels.messages.bag-busy"));
            }
            event.setCancelled(true);
            return;
        }

        // --- ENTRENAMIENTO (SACO O JUGADOR BANEADO) ---
        if (staminaManager.canHit(player)) {
            if (bagData != null) {
                processPlayerHit(player, event.getFinalDamage(), bag, bagData);
            } else if (isBannedBag) {
                processBanTargetHit(player, event.getFinalDamage(), bag);
            } else {
                processPlayerHitLegacy(player, event.getFinalDamage(), bag);
            }
        }

        event.setCancelled(true);
    }

    // -------------------------------------------------------
    // DAÑO AMBIENTAL
    // -------------------------------------------------------

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onEnvironmentalDamage(EntityDamageEvent event) {
        if (!dataManager.isBoxingBag(event.getEntity().getUniqueId())
         && (banManager == null || !banManager.isBanBagTarget(event.getEntity()))) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK &&
            event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE &&
            event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.FIRE ||
                event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK ||
                event.getCause() == EntityDamageEvent.DamageCause.LAVA)
                event.getEntity().setFireTicks(0);
        }
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (dataManager.isBoxingBag(event.getEntity().getUniqueId())
         || (banManager != null && banManager.isBanBagTarget(event.getEntity())))
            event.setCancelled(true);
    }


    // -------------------------------------------------------
    // PROCESAMIENTO DEL GOLPE
    // -------------------------------------------------------

    private void processPlayerHit(Player player, double baseDamage, Entity bag, BagData bagData) {
        double bonusDamage = 0.0;
        PlayerStat stat = dataManager.getPlayerStat(player.getUniqueId());
        double currentTotal = stat != null ? stat.getDamage() : 0.0;

        // Capture per-bag damage BEFORE update (needed for milestone threshold math)
        PlayerStat bagStat   = dataManager.getBagPlayerStat(bagData.getName(), player.getUniqueId());
        double oldBagDamage  = bagStat.getDamage();

        if (plugin.getConfig().getBoolean("musculature.enabled")) {
            double threshold   = plugin.getConfig().getDouble("musculature.damage-threshold", 2000.0);
            double bonusPerLvl = plugin.getConfig().getDouble("musculature.bonus-damage", 0.5);
            double maxBonus    = plugin.getConfig().getDouble("musculature.max-bonus", 20.0);
            if (threshold > 0)
                bonusDamage = Math.min((int)(currentTotal / threshold) * bonusPerLvl, maxBonus);
        }

        double finalDamage = (baseDamage + bonusDamage) * getMaterialMultiplier(bagData.getMaterial());

        if (bagData.getMaterial().isReturnDamageEnabled())
            checkReturnDamage(player, bag.getUniqueId());

        // Update global stats (existing — unchanged)
        dataManager.addDamage(player.getUniqueId(), player.getName(), finalDamage);
        rewardManager.checkReward(player, currentTotal, currentTotal + finalDamage);

        // Update per-bag stats + trigger reward layers
        dataManager.addBagDamage(player.getUniqueId(), player.getName(), finalDamage, bagData.getName());
        if (bagData.getName().startsWith("banned_")) {
            dataManager.addBagDamage(player.getUniqueId(), player.getName(), finalDamage, "Banned:" + bagData.getName().substring(7));
        }
        rewardManager.checkAll(player, bagData, finalDamage, oldBagDamage, bag.getLocation());

        boolean isMuscle = bonusDamage > 0;
        soundManager.playHitSoundForMaterial(player, isMuscle, bagData.getMaterial());
        sendActionBar(player, finalDamage, bonusDamage, bagData);
        applyPhysics(player, bag, bagData);
    }



    private void processPlayerHitLegacy(Player player, double baseDamage, Entity bag) {
        dataManager.addDamage(player.getUniqueId(), player.getName(), baseDamage);
        soundManager.playHitSound(player, false);
        if (plugin.getConfig().getBoolean("physics.enabled")) {
            double kb = plugin.getConfig().getDouble("physics.knockback-strength", 0.4);
            double vt = plugin.getConfig().getDouble("physics.vertical-strength", 0.1);
            bag.setVelocity(player.getLocation().getDirection().normalize().multiply(kb).setY(vt));
        }
    }

    private void processBanTargetHit(Player player, double baseDamage, Entity bag) {
        String banTargetName = banManager != null ? banManager.getBanTargetName(bag) : "BannedPlayer";
        double bonusDamage = 0.0;
        PlayerStat stat = dataManager.getPlayerStat(player.getUniqueId());
        double currentTotal = stat != null ? stat.getDamage() : 0.0;

        if (plugin.getConfig().getBoolean("musculature.enabled")) {
            double threshold   = plugin.getConfig().getDouble("musculature.damage-threshold", 2000.0);
            double bonusPerLvl = plugin.getConfig().getDouble("musculature.bonus-damage", 0.5);
            double maxBonus    = plugin.getConfig().getDouble("musculature.max-bonus", 20.0);
            if (threshold > 0)
                bonusDamage = Math.min((int)(currentTotal / threshold) * bonusPerLvl, maxBonus);
        }

        double finalDamage = baseDamage + bonusDamage;

        // Registrar daño general + daño por saco Banned:Name
        dataManager.addDamage(player.getUniqueId(), player.getName(), finalDamage);
        dataManager.addBagDamage(player.getUniqueId(), player.getName(), finalDamage, "Banned:" + banTargetName);
        rewardManager.checkReward(player, currentTotal, currentTotal + finalDamage);

        boolean isMuscle = bonusDamage > 0;
        soundManager.playHitSoundForMaterial(player, isMuscle, BagData.BagMaterial.SAND);


        String dmgText = String.format("%.1f", finalDamage);
        if (bonusDamage > 0) dmgText += ChatColor.GOLD + " (+" + String.format("%.1f", bonusDamage) + " STR)";
        String timeStr = "";
        if (banManager != null) {
            BanManager.BannedPlayerEntry entry = banManager.getBannedEntry(bag.getUniqueId());
            if (entry != null && entry.expirationTime > 0) {
                timeStr = " [" + BanManager.formatRemainingTime(entry.expirationTime) + "]";
            }
        }
        String msg = ChatColor.RED + "✦ Banned Target: " + ChatColor.YELLOW + banTargetName
                   + ChatColor.GRAY + " [" + ChatColor.RED + "-" + dmgText + " dmg" + ChatColor.GRAY + "]" + ChatColor.GRAY + timeStr;

        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
        } catch (Exception e) {
            player.sendMessage(msg);
        }

        if (plugin.getConfig().getBoolean("physics.enabled", true)) {
            double kb = plugin.getConfig().getDouble("physics.knockback-strength", 0.35);
            double vt = plugin.getConfig().getDouble("physics.vertical-strength", 0.1);
            bag.setVelocity(player.getLocation().getDirection().normalize().multiply(kb).setY(vt));
        }
    }


    // -------------------------------------------------------
    // FÍSICAS
    // -------------------------------------------------------

    /**
     * Aplica el golpe físico al saco.
     *
     * Funciona igual para sacos /spawn (Zombie sin IA) y /setentity (cualquier mob):
     * las LivingEntities sin IA SÍ procesan setVelocity() — el servidor aplica el vector
     * en el siguiente tick de física aunque la entidad no tenga movimiento propio.
     * Esto produce un rebote natural que se siente exactamente igual que golpear cualquier
     * otra entidad del juego, sin código especial ni animaciones manuales.
     */
    private void applyPhysics(Player player, Entity bag, BagData bagData) {
        double knockback = bagData.getEffectiveKnockback();
        double vertical  = bagData.getEffectiveVertical();
        if (knockback == 0.0 && vertical == 0.0) return;

        Vector dir = player.getLocation().getDirection()
                .normalize().multiply(knockback).setY(vertical);
        bag.setVelocity(dir);
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    private void checkReturnDamage(Player player, UUID bagUUID) {
        if (!plugin.getConfig().getBoolean("return-damage.enabled", true)) return;

        long thresholdMs = plugin.getConfig().getLong("return-damage.threshold-ms", 150L);
        double dmg = plugin.getConfig().getDouble("return-damage.damage", 0.5);
        String msg = plugin.getConfig().getString("return-damage.message", "&cYour hand hurts from hitting too fast!");

        long now = System.currentTimeMillis();
        UUID key = new UUID(
            player.getUniqueId().getMostSignificantBits()  ^ bagUUID.getMostSignificantBits(),
            player.getUniqueId().getLeastSignificantBits() ^ bagUUID.getLeastSignificantBits()
        );
        Long last = lastHitMap.get(key);
        lastHitMap.put(key, now);
        if (last != null && (now - last) < thresholdMs) {
            player.damage(dmg);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    private double getMaterialMultiplier(BagData.BagMaterial material) {
        switch (material) {
            case STONE: return 0.6;
            case WOOL:  return 1.2;
            default:    return 1.0;
        }
    }

    private void sendActionBar(Player player, double finalDamage, double bonusDamage,
                                BagData bagData) {
        String dmgText = String.format("%.1f", finalDamage);
        if (bonusDamage > 0)
            dmgText += ChatColor.GOLD + " (+" + String.format("%.1f", bonusDamage) + " STR)";

        String msg;
        if (bagData.getName().startsWith("banned_")) {
            String targetName = bagData.getName().substring(7);
            String timeStr = "";
            if (banManager != null) {
                BanManager.BannedPlayerEntry entry = banManager.getBannedEntry(bagData.getUuid());
                if (entry != null && entry.expirationTime > 0) {
                    timeStr = " [" + BanManager.formatRemainingTime(entry.expirationTime) + "]";
                }
            }
            msg = ChatColor.RED + "✦ Banned Target: " + ChatColor.YELLOW + targetName
                + ChatColor.GRAY + " [" + ChatColor.RED + "-" + dmgText + " dmg" + ChatColor.GRAY + "]" + ChatColor.GRAY + timeStr;
        } else {
            if (bagData.getMaterial() != BagData.BagMaterial.SAND)
                dmgText += ChatColor.GRAY + " [" + bagData.getMaterial().getDisplayName() + "]";
            msg = dataManager.getRawMessage("hit-actionbar").replace("{damage}", dmgText);
        }


        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
        } catch (Exception e) {
            player.sendMessage(msg);
        }
    }


    private void healBag(Entity bag) {
        if (!(bag instanceof LivingEntity)) return;
        LivingEntity live = (LivingEntity) bag;
        double maxHealth = 20.0;
        try {
            if (live.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null)
                maxHealth = live.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            else
                maxHealth = live.getMaxHealth();
        } catch (Throwable e) {
            try { maxHealth = live.getMaxHealth(); } catch (Exception ignored) {}
        }
        live.setHealth(maxHealth);
        live.setFireTicks(0);
    }
}
