package fun.gexel.boxtop.managers;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.objects.LootItem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages rewards.yml — the per-bag reward configuration file.
 *
 * Keeps data.yml clean by storing all reward config separately.
 *
 * Fallback chain per bag:
 *   1. rewards.yml → bags.<bagName>
 *   2. config.yml  → default-bag-rewards
 *   3. null        → rewards disabled for this bag
 *
 * Loot item lists are cached after the first parse to avoid
 * re-parsing on every hit.
 */
public class RewardsFileManager {

    private final BoxTopPlugin plugin;

    private File              rewardsFile;
    private FileConfiguration rewardsConfig;

    // Parsed item list caches — cleared on reload()
    private final Map<String, List<LootItem>> lootCache   = new HashMap<>();
    private final Map<String, List<LootItem>> pinataCache = new HashMap<>();

    public RewardsFileManager(BoxTopPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    // -------------------------------------------------------
    // RELOAD
    // -------------------------------------------------------

    public void reload() {
        rewardsFile = new File(plugin.getDataFolder(), "rewards.yml");

        if (!rewardsFile.exists()) {
            // Save the commented template bundled in the jar
            plugin.saveResource("rewards.yml", false);
        }

        // If save failed for some reason, create a blank file
        if (!rewardsFile.exists()) {
            try { rewardsFile.createNewFile(); }
            catch (IOException e) {
                plugin.getLogger().warning("[BoxTop] Could not create rewards.yml: " + e.getMessage());
            }
        }

        rewardsConfig = YamlConfiguration.loadConfiguration(rewardsFile);
        lootCache.clear();
        pinataCache.clear();
        plugin.getLogger().info("[BoxTop] rewards.yml loaded.");
    }

    // -------------------------------------------------------
    // INTERNAL — Resolve section with fallback
    // -------------------------------------------------------

    private ConfigurationSection getBagSection(String bagName) {
        String key = "bags." + bagName.toLowerCase();
        if (rewardsConfig.contains(key)) {
            return rewardsConfig.getConfigurationSection(key);
        }
        // Fallback to config.yml defaults
        if (plugin.getConfig().contains("default-bag-rewards")) {
            return plugin.getConfig().getConfigurationSection("default-bag-rewards");
        }
        return null;
    }

    // -------------------------------------------------------
    // MILESTONES
    // -------------------------------------------------------

    public boolean isMilestonesEnabled(String bagName) {
        ConfigurationSection s = getBagSection(bagName);
        return s != null && s.getBoolean("milestones.enabled", false);
    }

    public double getMilestoneInterval(String bagName) {
        ConfigurationSection s = getBagSection(bagName);
        return s != null ? s.getDouble("milestones.interval", 500.0) : 500.0;
    }

    public List<String> getMilestoneCommands(String bagName) {
        ConfigurationSection s = getBagSection(bagName);
        if (s == null) return Collections.emptyList();
        return s.getStringList("milestones.commands");
    }

    // -------------------------------------------------------
    // LOOT TABLE
    // -------------------------------------------------------

    public boolean isLootTableEnabled(String bagName) {
        ConfigurationSection s = getBagSection(bagName);
        return s != null && s.getBoolean("loot-table.enabled", false);
    }

    public double getLootTableChance(String bagName) {
        ConfigurationSection s = getBagSection(bagName);
        return s != null ? s.getDouble("loot-table.chance-per-hit", 5.0) : 5.0;
    }

    public List<LootItem> getLootTableItems(String bagName) {
        String key = bagName.toLowerCase();
        if (lootCache.containsKey(key)) return lootCache.get(key);

        ConfigurationSection bagSec = getBagSection(bagName);
        List<LootItem> items = Collections.emptyList();
        if (bagSec != null) {
            ConfigurationSection ltSec = bagSec.getConfigurationSection("loot-table");
            if (ltSec != null) items = LootItem.parseList(ltSec, "items");
        }
        lootCache.put(key, items);
        return items;
    }

    // -------------------------------------------------------
    // PIÑATA
    // -------------------------------------------------------

    public boolean isPinataEnabled(String bagName) {
        ConfigurationSection s = getBagSection(bagName);
        return s != null && s.getBoolean("pinata.enabled", false);
    }

    public int getPinataHitsRequired(String bagName) {
        ConfigurationSection s = getBagSection(bagName);
        return s != null ? s.getInt("pinata.hits-required", 100) : 100;
    }

    public List<LootItem> getPinataItems(String bagName) {
        String key = bagName.toLowerCase();
        if (pinataCache.containsKey(key)) return pinataCache.get(key);

        ConfigurationSection bagSec = getBagSection(bagName);
        List<LootItem> items = Collections.emptyList();
        if (bagSec != null) {
            ConfigurationSection pinataSec = bagSec.getConfigurationSection("pinata");
            if (pinataSec != null) items = LootItem.parseList(pinataSec, "items");
        }
        pinataCache.put(key, items);
        return items;
    }

    // -------------------------------------------------------
    // AUTO-GENERATE BAG SECTION ON SPAWN
    // -------------------------------------------------------

    /**
     * Called by BagSpawnManager when a new bag is created with /boxtop spawn.
     * Writes a fully-commented default section to rewards.yml so the admin
     * only needs to edit the numbers — no manual copy-paste required.
     *
     * Skips silently if the section already exists (safe to call on restart).
     */
    public void createDefaultBagSection(String bagName) {
        String key = "bags." + bagName.toLowerCase();
        if (rewardsConfig.contains(key)) return; // already exists, don't overwrite

        // Milestones — disabled by default
        rewardsConfig.set(key + ".milestones.enabled",  false);
        rewardsConfig.set(key + ".milestones.interval", 500.0);
        rewardsConfig.set(key + ".milestones.commands",
            java.util.Arrays.asList("eco give %player% 50", "give %player% golden_apple 1"));

        // Loot table — disabled by default
        rewardsConfig.set(key + ".loot-table.enabled",        false);
        rewardsConfig.set(key + ".loot-table.chance-per-hit", 5.0);
        rewardsConfig.set(key + ".loot-table.items", buildDefaultLootList());

        // Pinata — ENABLED by default (the public feature)
        rewardsConfig.set(key + ".pinata.enabled",       true);
        rewardsConfig.set(key + ".pinata.hits-required", 100);
        rewardsConfig.set(key + ".pinata.items", buildDefaultPinataList());

        try {
            rewardsConfig.save(rewardsFile);
            plugin.getLogger().info("[BoxTop] rewards.yml: created default section for bag '" + bagName + "'.");
        } catch (IOException e) {
            plugin.getLogger().warning("[BoxTop] Could not save rewards.yml after creating section for '" + bagName + "': " + e.getMessage());
        }
    }

    private java.util.List<java.util.Map<String, Object>> buildDefaultLootList() {
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        java.util.Map<String, Object> nugget = new java.util.LinkedHashMap<>();
        nugget.put("material", "GOLD_NUGGET"); nugget.put("amount", 1); nugget.put("weight", 70);
        java.util.Map<String, Object> ingot = new java.util.LinkedHashMap<>();
        ingot.put("material", "IRON_INGOT"); ingot.put("amount", 1); ingot.put("weight", 25);
        java.util.Map<String, Object> diamond = new java.util.LinkedHashMap<>();
        diamond.put("material", "DIAMOND"); diamond.put("amount", 1); diamond.put("weight", 5);
        list.add(nugget); list.add(ingot); list.add(diamond);
        return list;
    }

    private java.util.List<java.util.Map<String, Object>> buildDefaultPinataList() {
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        java.util.Map<String, Object> emerald = new java.util.LinkedHashMap<>();
        emerald.put("material", "EMERALD"); emerald.put("amount", 3);
        java.util.Map<String, Object> xp = new java.util.LinkedHashMap<>();
        xp.put("material", "EXPERIENCE_BOTTLE"); xp.put("amount", 5);
        java.util.Map<String, Object> gold = new java.util.LinkedHashMap<>();
        gold.put("material", "GOLD_INGOT"); gold.put("amount", 2);
        list.add(emerald); list.add(xp); list.add(gold);
        return list;
    }
}

