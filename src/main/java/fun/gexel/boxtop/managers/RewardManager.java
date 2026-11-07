package fun.gexel.boxtop.managers;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.objects.BagData;
import fun.gexel.boxtop.objects.LootItem;
import fun.gexel.boxtop.objects.PlayerStat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * RewardManager v3.1 — Three independent reward layers with Feature Flag support.
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │  FEATURE FLAGS (config.yml)                                 │
 * │  features.milestones  → default false (hidden key)          │
 * │  features.loot-table  → default false (hidden key)          │
 * │  features.pinata      → default true  (public key)          │
 * └─────────────────────────────────────────────────────────────┘
 *
 * Layer 1 — Milestones:   Per-player, per-bag damage tracking.
 *                          Runs console commands every N damage.
 * Layer 2 — Loot Table:   Per-hit weighted random item drop with physical scatter.
 * Layer 3 — Piñata:       Shared counter for all players on a bag.
 *                          Burst drop + effects after N hits. Counter resets.
 *
 * Backward compat: The old global checkReward() is kept unchanged.
 */
public class RewardManager {

    private final BoxTopPlugin       plugin;
    private final DataManager        dataManager;
    private final RewardsFileManager rewardsManager;
    private final SoundManager       soundManager;

    // Piñata hit counters — in-memory only, resets on reload/restart (intentional)
    // bagName (lowercase) → total hits since last piñata
    private final Map<String, Integer> pinataCounters = new HashMap<>();

    private final Random rng = new Random();

    public RewardManager(BoxTopPlugin plugin, DataManager dataManager, SoundManager soundManager) {
        this.plugin         = plugin;
        this.dataManager    = dataManager;
        this.rewardsManager = dataManager.getRewardsFileManager();
        this.soundManager   = soundManager;
    }

    // -------------------------------------------------------
    // LEGACY API (global milestones from config.yml — unchanged)
    // -------------------------------------------------------

    public void checkReward(Player player, double oldDamage, double newDamage) {
        if (!plugin.getConfig().getBoolean("rewards.enabled")) return;

        double interval = plugin.getConfig().getDouble("rewards.interval", 500.0);
        if (interval <= 0) return;

        int oldMilestones = (int) (oldDamage / interval);
        int newMilestones = (int) (newDamage / interval);

        if (newMilestones > oldMilestones) {
            List<String> commands = plugin.getConfig().getStringList("rewards.commands");
            for (String cmd : commands) {
                String finalCmd = cmd.replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
            }
        }
    }

    // -------------------------------------------------------
    // NEW v3.1 API — per-bag, three layers
    // -------------------------------------------------------

    /**
     * Main entry point called by HitListener on every valid punch.
     *
     * @param player       The player who hit the bag
     * @param bagData      BagData for the bag that was hit
     * @param hitDamage    Final damage of this specific hit
     * @param oldBagDamage Player's total bag damage BEFORE this hit (for milestone math)
     * @param bagLocation  Physical location of the bag (for drops)
     */
    public void checkAll(Player player, BagData bagData, double hitDamage,
                         double oldBagDamage, Location bagLocation) {
        String bagName = bagData.getName();

        // --- Layer 1: Milestones (per-player, per-bag) ---
        if (plugin.getConfig().getBoolean("features.milestones", false)
                && rewardsManager.isMilestonesEnabled(bagName)) {
            checkMilestone(player, bagName, oldBagDamage, oldBagDamage + hitDamage);
        }

        // --- Layer 2: Loot Table (per-hit random drop) ---
        if (plugin.getConfig().getBoolean("features.loot-table", false)
                && rewardsManager.isLootTableEnabled(bagName)) {
            rollLootTable(bagName, bagLocation);
        }

        // --- Layer 3: Piñata (shared counter, all players) ---
        if (plugin.getConfig().getBoolean("features.pinata", true)
                && rewardsManager.isPinataEnabled(bagName)) {
            tickPinata(bagName, bagLocation);
        }
    }

    // -------------------------------------------------------
    // LAYER 1 — MILESTONES
    // -------------------------------------------------------

    private void checkMilestone(Player player, String bagName,
                                 double oldDamage, double newDamage) {
        double interval = rewardsManager.getMilestoneInterval(bagName);
        if (interval <= 0) return;

        int oldLevel = (int) (oldDamage / interval);
        int newLevel = (int) (newDamage / interval);
        if (newLevel <= oldLevel) return;

        List<String> commands = rewardsManager.getMilestoneCommands(bagName);
        for (String cmd : commands) {
            String finalCmd = cmd.replace("%player%", player.getName())
                                 .replace("%bag%", bagName);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
        }
    }

    // -------------------------------------------------------
    // LAYER 2 — LOOT TABLE
    // -------------------------------------------------------

    private void rollLootTable(String bagName, Location loc) {
        double chance = rewardsManager.getLootTableChance(bagName);
        if (rng.nextDouble() * 100.0 >= chance) return; // No drop this hit

        List<LootItem> items = rewardsManager.getLootTableItems(bagName);
        if (items.isEmpty()) return;

        LootItem selected = weightedRandom(items);
        if (selected == null) return;

        ItemStack stack = selected.buildItem();
        if (stack == null) return;

        dropWithScatter(loc, stack);
    }

    // -------------------------------------------------------
    // LAYER 3 — PIÑATA
    // -------------------------------------------------------

    private void tickPinata(String bagName, Location loc) {
        String key = bagName.toLowerCase();
        int current = pinataCounters.getOrDefault(key, 0) + 1;

        int required = rewardsManager.getPinataHitsRequired(bagName);

        if (current >= required) {
            pinataCounters.put(key, 0); // reset counter
            triggerPinata(bagName, loc);
        } else {
            pinataCounters.put(key, current);
        }
    }

    private void triggerPinata(String bagName, Location loc) {
        if (loc == null || loc.getWorld() == null) return;

        // 1. Visual burst (cross-version particle)
        spawnPinataParticles(loc);

        // 2. Sound (audible to all nearby)
        soundManager.playPinataSound(loc);

        // 3. Drop all piñata items with scatter
        List<LootItem> items = rewardsManager.getPinataItems(bagName);
        for (LootItem loot : items) {
            ItemStack stack = loot.buildItem();
            if (stack != null) dropWithScatter(loc, stack);
        }
    }

    // -------------------------------------------------------
    // PIÑATA STATE
    // -------------------------------------------------------

    /** Returns current piñata hit count for a bag (for GUI progress display). */
    public int getPinataCounter(String bagName) {
        return pinataCounters.getOrDefault(bagName.toLowerCase(), 0);
    }

    /** Resets the in-memory piñata counter for a bag. */
    public void resetPinataCounter(String bagName) {
        pinataCounters.remove(bagName.toLowerCase());
    }

    // -------------------------------------------------------
    // HELPERS — SCATTER DROP
    // -------------------------------------------------------

    /**
     * Drops an item at the given location with a random outward velocity
     * so items scatter around the bag instead of piling up underneath.
     * Any nearby player can pick them up — no ownership.
     */
    private void dropWithScatter(Location loc, ItemStack stack) {
        if (loc.getWorld() == null) return;

        Item dropped = loc.getWorld().dropItem(loc.clone().add(0, 0.5, 0), stack);

        double speed = 0.22 + rng.nextDouble() * 0.12; // 0.22 – 0.34
        double angle = rng.nextDouble() * 2 * Math.PI;
        double vx    = Math.cos(angle) * speed;
        double vz    = Math.sin(angle) * speed;
        double vy    = 0.28 + rng.nextDouble() * 0.18; // pop upward

        dropped.setVelocity(new Vector(vx, vy, vz));
        dropped.setPickupDelay(20); // 1 second grace before pickup (prevents mis-clicks)
    }

    // -------------------------------------------------------
    // HELPERS — PARTICLE BURST
    // -------------------------------------------------------

    private void spawnPinataParticles(Location loc) {
        if (loc.getWorld() == null) return;

        // Try large explosion first (most dramatic), fallback to smaller ones
        String[] particleNames = {
            "EXPLOSION_LARGE", "EXPLOSION_EMITTER", "EXPLOSION",
            "LARGE_EXPLOSION",  "HUGE_EXPLOSION"
        };
        for (String name : particleNames) {
            try {
                org.bukkit.Particle p = org.bukkit.Particle.valueOf(name);
                loc.getWorld().spawnParticle(p, loc, 3, 0.3, 0.3, 0.3, 0);
                break;
            } catch (Exception ignored) {}
        }

        // Add colorful TOTEM particles on top for flair
        try {
            org.bukkit.Particle totem = org.bukkit.Particle.valueOf("TOTEM");
            loc.getWorld().spawnParticle(totem, loc, 40, 0.7, 0.7, 0.7, 0.4);
        } catch (Exception ignored) {}

        // Confetti-like happy villager particles
        try {
            org.bukkit.Particle happy = org.bukkit.Particle.valueOf("VILLAGER_HAPPY");
            loc.getWorld().spawnParticle(happy, loc, 20, 1.0, 1.0, 1.0, 0.1);
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------
    // HELPERS — WEIGHTED RANDOM
    // -------------------------------------------------------

    private LootItem weightedRandom(List<LootItem> items) {
        int total = 0;
        for (LootItem item : items) total += item.getWeight();
        if (total <= 0) return null;

        int roll = rng.nextInt(total);
        int cumulative = 0;
        for (LootItem item : items) {
            cumulative += item.getWeight();
            if (roll < cumulative) return item;
        }
        return items.get(items.size() - 1); // fallback (shouldn't happen)
    }
}