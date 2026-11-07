package fun.gexel.boxtop.managers;

import com.cryptomorin.xseries.XMaterial;
import fun.gexel.boxtop.BoxTopPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * BanManager — manages the multi-slot BoxTop Ban Zone / Gym Stage and punching bag system.
 */
public class BanManager {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private File bansFile;
    private FileConfiguration bansConfig;

    // UUID of banned player -> entry details
    private final Map<UUID, BannedPlayerEntry> bannedPlayers = new LinkedHashMap<>();

    // Registered Ban Posts: post_id -> slot data
    private final Map<String, BanPostSlot> banPosts = new LinkedHashMap<>();

    // Admin selection wand corners: Player UUID -> Location
    private final Map<UUID, Location> selectionCorner1 = new HashMap<>();
    private final Map<UUID, Location> selectionCorner2 = new HashMap<>();

    // Online player UUID -> UUID of invisible leash holder Villager
    private final Map<UUID, UUID> onlineLeashHolders = new HashMap<>();

    // Offline player UUID -> UUID of offline dummy Villager wearing player skull
    private final Map<UUID, UUID> offlineDummyBags = new HashMap<>();

    // Player UUID (online or offline) -> UUID of floating ArmorStand hologram
    private final Map<UUID, UUID> bannedHolograms = new HashMap<>();

    private Location legacyBanLocation;

    // Mock taunt messages configuration
    private boolean mockMessagesEnabled = true;
    private final List<String> mockMessages = new ArrayList<>();
    private final Random random = new Random();

    public static class BanPostSlot {
        public final String id;
        public final Location fenceLocation;
        public final Location centerLocation;
        public final boolean isGenerated;
        public UUID occupiedBy;

        public BanPostSlot(String id, Location fenceLocation, Location centerLocation, boolean isGenerated, UUID occupiedBy) {
            this.id = id;
            this.fenceLocation = fenceLocation;
            this.centerLocation = centerLocation;
            this.isGenerated = isGenerated;
            this.occupiedBy = occupiedBy;
        }
    }

    public static class BannedPlayerEntry {
        public final UUID uuid;
        public final String name;
        public final String reason;
        public final long expirationTime; // -1 for permanent
        public String assignedPostId; // e.g. "post_1", or null if using legacy ban location

        public BannedPlayerEntry(UUID uuid, String name, String reason, long expirationTime, String assignedPostId) {
            this.uuid = uuid;
            this.name = name;
            this.reason = reason;
            this.expirationTime = expirationTime;
            this.assignedPostId = assignedPostId;
        }

        public boolean isExpired() {
            return expirationTime > 0 && System.currentTimeMillis() > expirationTime;
        }
    }

    public BanManager(BoxTopPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        reload();
        restoreOnStartup();

        // Repeating snap-back tether, expiration checking, and hologram task (every 2 ticks)
        Bukkit.getScheduler().runTaskTimer(plugin, this::tetherCheck, 2L, 2L);
        // Visual leash synchronization (every 1 tick for maximum smoothness)
        Bukkit.getScheduler().runTaskTimer(plugin, this::visualSync, 1L, 1L);
    }

    // -------------------------------------------------------
    // REPEATING TETHER CHECK & EXPIRATION LOOP
    // -------------------------------------------------------

    private void tetherCheck() {
        List<UUID> toExpire = new ArrayList<>();

        for (BannedPlayerEntry entry : bannedPlayers.values()) {
            // Check expiration first
            if (entry.isExpired()) {
                toExpire.add(entry.uuid);
                continue;
            }

            Location targetLoc = getAssignedLocation(entry);
            if (targetLoc == null || targetLoc.getWorld() == null) continue;

            Player online = Bukkit.getPlayer(entry.uuid);
            if (online != null && online.isOnline()) {
                // Online player: rubber band physics toward assigned post
                if (online.getWorld().equals(targetLoc.getWorld())) {
                    double distSq = online.getLocation().distanceSquared(targetLoc);
                    if (distSq > 36.0) { // Safety net (6+ blocks) -> hard teleport
                        online.teleport(targetLoc);
                    } else if (distSq > 6.25) { // 2.5+ blocks away -> damped pull (leaves space for knockback)
                        double dist = Math.sqrt(distSq);
                        // Strength scales with distance: gentle close, stronger far
                        double strength = Math.min(0.5, 0.1 * dist);
                        org.bukkit.util.Vector toCenter = targetLoc.toVector().subtract(online.getLocation().toVector()).normalize();
                        // Dampen existing velocity much less aggressively to let knockback play out
                        org.bukkit.util.Vector current = online.getVelocity().multiply(0.6);
                        org.bukkit.util.Vector pull = current.add(toCenter.multiply(strength));
                        // Clamp Y to prevent launching or burying underground
                        pull.setY(Math.max(-0.1, Math.min(pull.getY(), 0.15)));
                        online.setVelocity(pull);
                    }
                    updateHologramPosition(entry.uuid, targetLoc);
                } else {
                    // Different world -> hard teleport back to post immediately
                    online.teleport(targetLoc);
                }
            } else {
                // Offline dummy: same rubber band physics
                UUID dummyUUID = offlineDummyBags.get(entry.uuid);
                if (dummyUUID != null) {
                    Entity dummy = Bukkit.getEntity(dummyUUID);
                    if (dummy != null && dummy.isValid() && dummy.getWorld().equals(targetLoc.getWorld())) {
                        double dDistSq = dummy.getLocation().distanceSquared(targetLoc);
                        if (dDistSq > 36.0) { // 6 blocks
                            Location snap = targetLoc.clone();
                            snap.setYaw(dummy.getLocation().getYaw());
                            dummy.teleport(snap);
                        } else if (dDistSq > 4.0) { // 2.0 blocks away
                            double dDist = Math.sqrt(dDistSq);
                            double dStrength = Math.min(0.4, 0.1 * dDist);
                            org.bukkit.util.Vector dToCenter = targetLoc.toVector().subtract(dummy.getLocation().toVector()).normalize();
                            org.bukkit.util.Vector dCurrent = dummy.getVelocity().multiply(0.6);
                            org.bukkit.util.Vector dPull = dCurrent.add(dToCenter.multiply(dStrength));
                            dPull.setY(Math.max(-0.1, Math.min(dPull.getY(), 0.15)));
                            dummy.setVelocity(dPull);
                        }
                        updateHologramPosition(entry.uuid, targetLoc);

                        if (dummy instanceof LivingEntity) {
                            LivingEntity ld = (LivingEntity) dummy;
                            try {
                                if (!ld.isLeashed() || ld.getLeashHolder() == null) {
                                    attachLeashToFence(ld, targetLoc, entry.assignedPostId);
                                }
                            } catch (Exception e) {
                                attachLeashToFence(ld, targetLoc, entry.assignedPostId);
                            }
                        }
                    }
                }
            }
        }

        // Process any expired bans
        for (UUID uuid : toExpire) {
            BannedPlayerEntry entry = bannedPlayers.get(uuid);
            if (entry != null) {
                plugin.getLogger().info("[BoxTop] Ban for " + entry.name + " has expired automatically.");
                unbanPlayer(uuid);
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.sendMessage(ChatColor.GREEN + "✦ Your BoxTop ban duration has expired! You are now free.");
                }
            }
        }
    }

    // -------------------------------------------------------
    // VISUAL SYNCHRONIZATION (1 TICK)
    // -------------------------------------------------------

    private void visualSync() {
        for (Map.Entry<UUID, UUID> entry : onlineLeashHolders.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) continue;
            
            Entity holder = Bukkit.getEntity(entry.getValue());
            if (holder instanceof LivingEntity && holder.isValid()) {
                LivingEntity lh = (LivingEntity) holder;
                
                // Keep the visual leash holder exactly at the player's position
                lh.teleport(p.getLocation());
                
                // Reattach leash if broken (e.g., chunk unload/reload)
                try {
                    if (!lh.isLeashed() || lh.getLeashHolder() == null) {
                        BannedPlayerEntry bpe = bannedPlayers.get(p.getUniqueId());
                        if (bpe != null) {
                            Location loc = getAssignedLocation(bpe);
                            if (loc != null) attachLeashToFence(lh, loc, bpe.assignedPostId);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    public Location getAssignedLocation(BannedPlayerEntry entry) {
        if (entry.assignedPostId != null) {
            BanPostSlot slot = banPosts.get(entry.assignedPostId);
            if (slot != null && slot.centerLocation != null) {
                return slot.centerLocation.clone().add(0, 1, 0); // Stand right above center block
            }
        }
        return legacyBanLocation != null ? legacyBanLocation.clone() : null;
    }

    // -------------------------------------------------------
    // SELECTION CORNERS & WAND MANAGEMENT
    // -------------------------------------------------------

    public void setSelectionCorner1(Player player, Location loc) {
        if (player != null && loc != null) selectionCorner1.put(player.getUniqueId(), loc.clone());
    }

    public void setSelectionCorner2(Player player, Location loc) {
        if (player != null && loc != null) selectionCorner2.put(player.getUniqueId(), loc.clone());
    }

    public Location getSelectionCorner1(Player player) {
        return player != null ? selectionCorner1.get(player.getUniqueId()) : null;
    }

    public Location getSelectionCorner2(Player player) {
        return player != null ? selectionCorner2.get(player.getUniqueId()) : null;
    }

    // -------------------------------------------------------
    // AUTO-GENERATION OF 3x3 STRUCTURES & POST SLOTS
    // -------------------------------------------------------

    public int generatePosts(Player player, int spacing) {
        Location c1 = getSelectionCorner1(player);
        Location c2 = getSelectionCorner2(player);
        if (c1 == null || c2 == null || c1.getWorld() == null || !c1.getWorld().equals(c2.getWorld())) {
            return -1;
        }

        World world = c1.getWorld();
        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());
        int maxY = Math.max(c1.getBlockY(), c2.getBlockY()) + 15;

        // Ensure spacing accounts for 3x3 platform (3 blocks + spacing between platforms)
        int step = Math.max(1, spacing) + 3;
        int generatedCount = 0;

        for (int x = minX + 1; x <= maxX - 1; x += step) {
            for (int z = minZ + 1; z <= maxZ - 1; z += step) {
                // Find top solid ground block cy scanning downwards
                int floorY = -1;
                for (int y = maxY; y >= 0; y--) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType().isSolid() && !b.getType().name().contains("LEAVES")) {
                        floorY = y;
                        break;
                    }
                }
                if (floorY <= 0) continue;

                // Build 3x3 platform at floorY
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block pb = world.getBlockAt(x + dx, floorY, z + dz);
                        if (dx == 0 && dz == 0) {
                            setBlockSafe(pb, "RED_CONCRETE", "CONCRETE", (byte) 14);
                        } else {
                            setBlockSafe(pb, "WHITE_CONCRETE", "CONCRETE", (byte) 0);
                        }
                    }
                }

                // Build top fixture: Oak Fence at floorY + 5, Sea Lantern at floorY + 6
                Block fenceBlock = world.getBlockAt(x, floorY + 5, z);
                Block lanternBlock = world.getBlockAt(x, floorY + 6, z);
                setBlockSafe(fenceBlock, "OAK_FENCE", "FENCE", (byte) 0);
                setBlockSafe(lanternBlock, "SEA_LANTERN", "SEA_LANTERN", (byte) 0);

                // Register post slot
                String id = "post_" + (banPosts.size() + 1);
                Location centerLoc = new Location(world, x + 0.5, floorY, z + 0.5);
                Location fenceLoc = fenceBlock.getLocation().add(0.5, 0.5, 0.5);

                BanPostSlot slot = new BanPostSlot(id, fenceLoc, centerLoc, true, null);
                banPosts.put(id, slot);
                generatedCount++;
            }
        }

        if (generatedCount > 0) {
            saveBans();
        }
        return generatedCount;
    }

    private void setBlockSafe(Block block, String modernName, String legacyName, byte data) {
        try {
            Material m = Material.valueOf(modernName);
            block.setType(m);
        } catch (Throwable t) {
            try {
                Material m = Material.valueOf(legacyName);
                block.setType(m);
                try {
                    Method setMethod = block.getClass().getMethod("setData", byte.class);
                    setMethod.invoke(block, data);
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
    }

    public boolean addPost(Player player, Block fenceBlock) {
        if (fenceBlock == null || !fenceBlock.getType().name().contains("FENCE")) return false;
        String id = "post_" + (banPosts.size() + 1);
        Location fenceLoc = fenceBlock.getLocation().add(0.5, 0.5, 0.5);
        Location centerLoc = fenceBlock.getLocation().add(0.5, -5, 0.5);

        BanPostSlot slot = new BanPostSlot(id, fenceLoc, centerLoc, false, null);
        banPosts.put(id, slot);
        saveBans();
        return true;
    }

    public int clearPosts(Player player) {
        int cleared = 0;
        for (BanPostSlot slot : new ArrayList<>(banPosts.values())) {
            if (slot.isGenerated && slot.fenceLocation != null && slot.fenceLocation.getWorld() != null) {
                World world = slot.fenceLocation.getWorld();
                int cx = slot.centerLocation.getBlockX();
                int cy = slot.centerLocation.getBlockY();
                int cz = slot.centerLocation.getBlockZ();

                // Clear fence and sea lantern
                world.getBlockAt(cx, cy + 5, cz).setType(Material.AIR);
                world.getBlockAt(cx, cy + 6, cz).setType(Material.AIR);

                // Restore platform blocks to grass/dirt
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        world.getBlockAt(cx + dx, cy, cz + dz).setType(Material.GRASS);
                    }
                }
            }
            // If slot is occupied, unban the occupant or reassign
            if (slot.occupiedBy != null) {
                unbanPlayer(slot.occupiedBy);
            }
            cleared++;
        }
        banPosts.clear();
        saveBans();
        return cleared;
    }

    public Collection<BanPostSlot> getAllPosts() {
        return Collections.unmodifiableCollection(banPosts.values());
    }

    // -------------------------------------------------------
    // RELOAD & SAVE (bans.yml)
    // -------------------------------------------------------

    public void reload() {
        bansFile = new File(plugin.getDataFolder(), "bans.yml");
        if (!bansFile.exists()) {
            try {
                bansFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("[BoxTop] Could not create bans.yml: " + e.getMessage());
            }
        }
        bansConfig = YamlConfiguration.loadConfiguration(bansFile);

        // Load legacy ban-location
        if (bansConfig.contains("ban-location.world")) {
            World world = Bukkit.getWorld(bansConfig.getString("ban-location.world"));
            if (world != null) {
                double x = bansConfig.getDouble("ban-location.x");
                double y = bansConfig.getDouble("ban-location.y");
                double z = bansConfig.getDouble("ban-location.z");
                float yaw = (float) bansConfig.getDouble("ban-location.yaw");
                float pitch = (float) bansConfig.getDouble("ban-location.pitch");
                legacyBanLocation = new Location(world, x, y, z, yaw, pitch);
            }
        }

        // Load ban posts
        banPosts.clear();
        ConfigurationSection postsSec = bansConfig.getConfigurationSection("posts");
        if (postsSec != null) {
            for (String key : postsSec.getKeys(false)) {
                try {
                    World w = Bukkit.getWorld(postsSec.getString(key + ".world"));
                    if (w == null) continue;
                    Location fenceLoc = new Location(w, postsSec.getDouble(key + ".fence-x"), postsSec.getDouble(key + ".fence-y"), postsSec.getDouble(key + ".fence-z"));
                    Location centerLoc = new Location(w, postsSec.getDouble(key + ".center-x"), postsSec.getDouble(key + ".center-y"), postsSec.getDouble(key + ".center-z"));
                    boolean isGen = postsSec.getBoolean(key + ".generated", false);
                    String occStr = postsSec.getString(key + ".occupied-by", null);
                    UUID occUuid = (occStr != null && !occStr.isEmpty() && !occStr.equals("null")) ? UUID.fromString(occStr) : null;

                    banPosts.put(key, new BanPostSlot(key, fenceLoc, centerLoc, isGen, occUuid));
                } catch (Exception ignored) {}
            }
        }

        // Load banned players
        bannedPlayers.clear();
        ConfigurationSection section = bansConfig.getConfigurationSection("banned-players");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String name = section.getString(key + ".name", "Unknown");
                    String reason = section.getString(key + ".reason", "No reason specified");
                    long expirationTime = section.getLong(key + ".expiration-time", -1L);
                    String postId = section.getString(key + ".post-id", null);
                    if ("null".equals(postId) || (postId != null && postId.isEmpty())) postId = null;

                    bannedPlayers.put(uuid, new BannedPlayerEntry(uuid, name, reason, expirationTime, postId));

                    // Recheck post occupancy
                    if (postId != null && banPosts.containsKey(postId)) {
                        banPosts.get(postId).occupiedBy = uuid;
                    }
                } catch (Exception ignored) {}
            }
        }

        // Load mock taunt messages
        mockMessagesEnabled = bansConfig.getBoolean("mock-messages.enabled", true);
        mockMessages.clear();
        if (bansConfig.contains("mock-messages.messages")) {
            mockMessages.addAll(bansConfig.getStringList("mock-messages.messages"));
        } else {
            mockMessages.addAll(Arrays.asList(
                "I broke the server rules and now I'm hanging here as the official punching bag!",
                "Please punch me! I need to learn my lesson.",
                "I tried to type a command, but my hands are tied to this BoxTop post!",
                "Ouch! Being a BoxTop bag is hard work.",
                "Don't be like me, obey the server rules!",
                "My commands are locked! Come to the spawn and punch me instead."
            ));
            bansConfig.set("mock-messages.enabled", true);
            bansConfig.set("mock-messages.messages", mockMessages);
            try { bansConfig.save(bansFile); } catch (Exception ignored) {}
        }

        plugin.getLogger().info("[BoxTop] Loaded " + banPosts.size() + " post(s) and " + bannedPlayers.size() + " banned player(s) from bans.yml.");
    }

    public void saveBans() {
        if (legacyBanLocation != null && legacyBanLocation.getWorld() != null) {
            bansConfig.set("ban-location.world", legacyBanLocation.getWorld().getName());
            bansConfig.set("ban-location.x", legacyBanLocation.getX());
            bansConfig.set("ban-location.y", legacyBanLocation.getY());
            bansConfig.set("ban-location.z", legacyBanLocation.getZ());
            bansConfig.set("ban-location.yaw", legacyBanLocation.getYaw());
            bansConfig.set("ban-location.pitch", legacyBanLocation.getPitch());
        } else {
            bansConfig.set("ban-location", null);
        }

        bansConfig.set("posts", null);
        for (BanPostSlot slot : banPosts.values()) {
            String p = "posts." + slot.id;
            if (slot.fenceLocation != null && slot.fenceLocation.getWorld() != null) {
                bansConfig.set(p + ".world", slot.fenceLocation.getWorld().getName());
                bansConfig.set(p + ".fence-x", slot.fenceLocation.getX());
                bansConfig.set(p + ".fence-y", slot.fenceLocation.getY());
                bansConfig.set(p + ".fence-z", slot.fenceLocation.getZ());
                bansConfig.set(p + ".center-x", slot.centerLocation.getX());
                bansConfig.set(p + ".center-y", slot.centerLocation.getY());
                bansConfig.set(p + ".center-z", slot.centerLocation.getZ());
                bansConfig.set(p + ".generated", slot.isGenerated);
                bansConfig.set(p + ".occupied-by", slot.occupiedBy != null ? slot.occupiedBy.toString() : null);
            }
        }

        bansConfig.set("banned-players", null);
        for (BannedPlayerEntry entry : bannedPlayers.values()) {
            String path = "banned-players." + entry.uuid.toString();
            bansConfig.set(path + ".name", entry.name);
            bansConfig.set(path + ".reason", entry.reason);
            bansConfig.set(path + ".expiration-time", entry.expirationTime);
            bansConfig.set(path + ".post-id", entry.assignedPostId);
        }

        try {
            bansConfig.save(bansFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[BoxTop] Error saving bans.yml: " + e.getMessage());
        }
    }

    // -------------------------------------------------------
    // LOCATION & BAN MANAGEMENT
    // -------------------------------------------------------

    public Location getBanLocation() {
        return legacyBanLocation != null ? legacyBanLocation.clone() : null;
    }

    public void setBanLocation(Location loc) {
        this.legacyBanLocation = loc != null ? loc.clone() : null;
        saveBans();
    }

    public boolean isBanned(UUID uuid) {
        return bannedPlayers.containsKey(uuid);
    }

    public boolean isBanned(Player player) {
        return player != null && isBanned(player.getUniqueId());
    }

    public BannedPlayerEntry getBannedEntry(UUID uuid) {
        return bannedPlayers.get(uuid);
    }

    public Collection<BannedPlayerEntry> getAllBannedPlayers() {
        return Collections.unmodifiableCollection(bannedPlayers.values());
    }

    public boolean banPlayer(Player target, long durationMillis, String reason) {
        UUID uuid = target.getUniqueId();
        String name = target.getName();

        // Assign to first unoccupied slot
        BanPostSlot assignedSlot = null;
        for (BanPostSlot slot : banPosts.values()) {
            if (slot.occupiedBy == null || !bannedPlayers.containsKey(slot.occupiedBy)) {
                assignedSlot = slot;
                break;
            }
        }

        String postId = assignedSlot != null ? assignedSlot.id : null;
        if (postId == null && legacyBanLocation == null) {
            return false; // No posts and no legacy ban location set
        }

        long expiration = durationMillis <= 0 ? -1L : System.currentTimeMillis() + durationMillis;
        bannedPlayers.put(uuid, new BannedPlayerEntry(uuid, name, reason, expiration, postId));

        if (assignedSlot != null) {
            assignedSlot.occupiedBy = uuid;
        }
        saveBans();

        // Teleport target to post
        Location targetLoc = getAssignedLocation(bannedPlayers.get(uuid));
        if (targetLoc != null) {
            target.teleport(targetLoc);
        }

        setupOnlineLeash(target);
        return true;
    }

    public boolean banOfflinePlayer(OfflinePlayer target, long durationMillis, String reason) {
        UUID uuid = target.getUniqueId();
        String name = target.getName() != null ? target.getName() : "Player";

        BanPostSlot assignedSlot = null;
        for (BanPostSlot slot : banPosts.values()) {
            if (slot.occupiedBy == null || !bannedPlayers.containsKey(slot.occupiedBy)) {
                assignedSlot = slot;
                break;
            }
        }

        String postId = assignedSlot != null ? assignedSlot.id : null;
        if (postId == null && legacyBanLocation == null) {
            return false;
        }

        long expiration = durationMillis <= 0 ? -1L : System.currentTimeMillis() + durationMillis;
        bannedPlayers.put(uuid, new BannedPlayerEntry(uuid, name, reason, expiration, postId));

        if (assignedSlot != null) {
            assignedSlot.occupiedBy = uuid;
        }
        saveBans();

        if (target.isOnline() && target.getPlayer() != null) {
            Location targetLoc = getAssignedLocation(bannedPlayers.get(uuid));
            if (targetLoc != null) target.getPlayer().teleport(targetLoc);
            setupOnlineLeash(target.getPlayer());
        } else {
            spawnOfflineDummy(uuid, name);
        }
        return true;
    }

    public boolean unbanPlayer(UUID uuid) {
        if (!bannedPlayers.containsKey(uuid)) return false;
        BannedPlayerEntry entry = bannedPlayers.remove(uuid);

        if (entry != null && entry.assignedPostId != null) {
            BanPostSlot slot = banPosts.get(entry.assignedPostId);
            if (slot != null && uuid.equals(slot.occupiedBy)) {
                slot.occupiedBy = null;
            }
        }
        saveBans();

        removeOnlineLeash(uuid);
        removeOfflineDummy(uuid);

        if (entry != null && dataManager != null) {
            dataManager.removeBoxingBag("banned_" + entry.name.toLowerCase());
        }

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            online.sendMessage(ChatColor.GREEN + "✦ You have been pardoned from the BoxTop post!");
            // Clear weakness / mining fatigue
            online.removePotionEffect(PotionEffectType.WEAKNESS);
            online.removePotionEffect(PotionEffectType.SLOW_DIGGING);
            // Remove glowing effect completely
            online.setGlowing(false);
            // Remove from any BoxTop scoreboard teams
            org.bukkit.scoreboard.Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            for (org.bukkit.scoreboard.Team team : sb.getTeams()) {
                if (team.getName().startsWith("BT_") && team.hasEntry(online.getName())) {
                    team.removeEntry(online.getName());
                }
            }
        }
        return true;
    }

    // -------------------------------------------------------
    // DURATION PARSING & TIME FORMATTING
    // -------------------------------------------------------

    public static long parseDurationMillis(String input) {
        if (input == null || input.isEmpty() || input.equalsIgnoreCase("perm") || input.equalsIgnoreCase("permanent") || input.equals("-1")) {
            return -1L;
        }
        try {
            long total = 0;
            String numberStr = "";
            for (char c : input.toCharArray()) {
                if (Character.isDigit(c)) {
                    numberStr += c;
                } else if (Character.isLetter(c) && !numberStr.isEmpty()) {
                    long val = Long.parseLong(numberStr);
                    numberStr = "";
                    if (c == 'd' || c == 'D') total += val * 86400000L;
                    else if (c == 'h' || c == 'H') total += val * 3600000L;
                    else if (c == 'm' || c == 'M') total += val * 60000L;
                    else if (c == 's' || c == 'S') total += val * 1000L;
                }
            }
            if (!numberStr.isEmpty() && total == 0) {
                return Long.parseLong(numberStr) * 1000L; // default seconds
            }
            return total > 0 ? total : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    public static String formatRemainingTime(long expirationTime) {
        if (expirationTime <= 0) return "PERM";
        long diff = expirationTime - System.currentTimeMillis();
        if (diff <= 0) return "0s";
        long days = diff / 86400000L;
        long hours = (diff % 86400000L) / 3600000L;
        long mins = (diff % 3600000L) / 60000L;
        long secs = (diff % 60000L) / 1000L;

        if (days > 0) return days + "d " + hours + "h left";
        if (hours > 0) return hours + "h " + mins + "m left";
        if (mins > 0) return mins + "m " + secs + "s left";
        return secs + "s left";
    }

    // -------------------------------------------------------
    // TARGET IDENTIFICATION & MOCK MESSAGES
    // -------------------------------------------------------

    public boolean isBanBagTarget(Entity entity) {
        if (entity == null) return false;
        if (entity instanceof Player && isBanned(entity.getUniqueId())) {
            return true;
        }
        return offlineDummyBags.containsValue(entity.getUniqueId());
    }

    public String getBanTargetName(Entity entity) {
        if (entity instanceof Player && isBanned(entity.getUniqueId())) {
            return entity.getName();
        }
        for (Map.Entry<UUID, UUID> e : offlineDummyBags.entrySet()) {
            if (e.getValue().equals(entity.getUniqueId())) {
                BannedPlayerEntry entry = bannedPlayers.get(e.getKey());
                return entry != null ? entry.name : "BannedPlayer";
            }
        }
        return "BannedPlayer";
    }

    public boolean isMockMessagesEnabled() {
        return mockMessagesEnabled;
    }

    public String getRandomMockMessage() {
        if (mockMessages.isEmpty()) return "I broke the rules and now I'm the official punching bag!";
        return mockMessages.get(random.nextInt(mockMessages.size()));
    }

    // -------------------------------------------------------
    // LIFE CYCLE — ONLINE LEASH SIMULATION
    // -------------------------------------------------------

    public void setupOnlineLeash(Player player) {
        BannedPlayerEntry entry = bannedPlayers.get(player.getUniqueId());
        if (entry == null) return;
        Location loc = getAssignedLocation(entry);
        if (loc == null || loc.getWorld() == null) return;

        removeOnlineLeash(player.getUniqueId());
        removeOfflineDummy(player.getUniqueId());

        String bagName = "banned_" + player.getName().toLowerCase();
        if (dataManager != null) {
            dataManager.removeBoxingBag(bagName);
            dataManager.addBoxingBag(bagName, player.getUniqueId());
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !isBanned(player)) return;

            org.bukkit.entity.Bat holder = loc.getWorld().spawn(loc.clone(), org.bukkit.entity.Bat.class);
            try { holder.setAwake(false); } catch (Exception ignored) {}
            configureInvisibleHolder(holder);
            attachLeashToFence(holder, loc, entry.assignedPostId);


            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, Integer.MAX_VALUE, 255, false, false));
            try {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_DIGGING, Integer.MAX_VALUE, 255, false, false));
            } catch (Exception ignored) {}

            spawnHologram(player.getUniqueId(), player.getName(), loc, entry.expirationTime);

            onlineLeashHolders.put(player.getUniqueId(), holder.getUniqueId());
        }, 5L);
    }

    public void removeOnlineLeash(UUID playerUUID) {
        UUID villagerUUID = onlineLeashHolders.remove(playerUUID);
        if (villagerUUID != null) {
            Entity entity = Bukkit.getEntity(villagerUUID);
            if (entity != null) entity.remove();
        }
        removeHologram(playerUUID);
    }

    // -------------------------------------------------------
    // LIFE CYCLE — OFFLINE DUMMY WITH PLAYER HEAD
    // -------------------------------------------------------

    public void spawnOfflineDummy(UUID playerUUID, String playerName) {
        BannedPlayerEntry entry = bannedPlayers.get(playerUUID);
        if (entry == null) return;
        Location loc = getAssignedLocation(entry);
        if (loc == null || loc.getWorld() == null) return;

        removeOnlineLeash(playerUUID);
        removeOfflineDummy(playerUUID);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isBanned(playerUUID)) return;
            if (Bukkit.getPlayer(playerUUID) != null) {
                setupOnlineLeash(Bukkit.getPlayer(playerUUID));
                return;
            }

            Villager dummy = loc.getWorld().spawn(loc.clone(), Villager.class);
            configureOfflineDummy(dummy, playerUUID, playerName);
            attachLeashToFence(dummy, loc, entry.assignedPostId);

            String bagName = "banned_" + playerName.toLowerCase();
            if (dataManager != null) {
                dataManager.removeBoxingBag(bagName);
                dataManager.addBoxingBag(bagName, dummy.getUniqueId());
            }

            spawnHologram(playerUUID, playerName, loc, entry.expirationTime);
            offlineDummyBags.put(playerUUID, dummy.getUniqueId());
        }, 10L);
    }

    public void removeOfflineDummy(UUID playerUUID) {
        UUID villagerUUID = offlineDummyBags.remove(playerUUID);
        if (villagerUUID != null) {
            Entity entity = Bukkit.getEntity(villagerUUID);
            if (entity != null) entity.remove();
        }
        removeHologram(playerUUID);
    }

    // -------------------------------------------------------
    // HOLOGRAM MANAGEMENT (ARMORSTAND)
    // -------------------------------------------------------

    private void spawnHologram(UUID targetUUID, String targetName, Location baseLoc, long expirationTime) {
        removeHologram(targetUUID);
        if (baseLoc == null || baseLoc.getWorld() == null) return;

        Location holoLoc = baseLoc.clone().add(0, 2.1, 0);
        org.bukkit.entity.ArmorStand holo = holoLoc.getWorld().spawn(holoLoc, org.bukkit.entity.ArmorStand.class);
        String timeStr = expirationTime > 0 ? " [" + formatRemainingTime(expirationTime) + "]" : "";
        holo.setCustomName(ChatColor.RED + "✦ BANNED: " + ChatColor.YELLOW + targetName + ChatColor.GRAY + timeStr);
        holo.setCustomNameVisible(true);
        holo.setVisible(false);
        holo.setGravity(false);
        holo.setSmall(true);
        holo.setBasePlate(false);
        try { holo.setMarker(true); } catch (Exception ignored) {}
        try {
            Method m = holo.getClass().getMethod("setPersistent", boolean.class);
            m.invoke(holo, true);
        } catch (Exception ignored) {}

        bannedHolograms.put(targetUUID, holo.getUniqueId());
    }

    private void updateHologramPosition(UUID targetUUID, Location baseLoc) {
        UUID holoUUID = bannedHolograms.get(targetUUID);
        BannedPlayerEntry entry = bannedPlayers.get(targetUUID);
        if (holoUUID != null && baseLoc != null && baseLoc.getWorld() != null) {
            Entity holo = Bukkit.getEntity(holoUUID);
            if (holo != null && holo.isValid()) {
                Location targetLoc = baseLoc.clone().add(0, 2.1, 0);
                if (holo.getLocation().distanceSquared(targetLoc) > 0.01) {
                    holo.teleport(targetLoc);
                }
                if (entry != null) {
                    String timeStr = entry.expirationTime > 0 ? " [" + formatRemainingTime(entry.expirationTime) + "]" : "";
                    holo.setCustomName(ChatColor.RED + "✦ BANNED: " + ChatColor.YELLOW + entry.name + ChatColor.GRAY + timeStr);
                }
            }
        }
    }

    private void removeHologram(UUID targetUUID) {
        UUID holoUUID = bannedHolograms.remove(targetUUID);
        if (holoUUID != null) {
            Entity entity = Bukkit.getEntity(holoUUID);
            if (entity != null) entity.remove();
        }
    }

    public void restoreOnStartup() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Clean up old entities nearby all known slots and legacy location
            Set<World> worlds = new HashSet<>();
            if (legacyBanLocation != null && legacyBanLocation.getWorld() != null) worlds.add(legacyBanLocation.getWorld());
            for (BanPostSlot slot : banPosts.values()) {
                if (slot.centerLocation != null && slot.centerLocation.getWorld() != null) worlds.add(slot.centerLocation.getWorld());
            }

            for (World w : worlds) {
                for (Entity entity : w.getEntities()) {
                    if (entity.getCustomName() != null &&
                        (entity.getCustomName().contains("✦ BANNED:") || entity.getCustomName().contains("BoxTop Leash"))) {
                        entity.remove();
                    }
                }
            }

            onlineLeashHolders.clear();
            offlineDummyBags.clear();
            bannedHolograms.clear();

            for (BannedPlayerEntry entry : bannedPlayers.values()) {
                Player online = Bukkit.getPlayer(entry.uuid);
                if (online != null && online.isOnline()) {
                    Location loc = getAssignedLocation(entry);
                    if (loc != null) online.teleport(loc);
                    setupOnlineLeash(online);
                } else {
                    spawnOfflineDummy(entry.uuid, entry.name);
                }
            }
        }, 30L);
    }

    public void shutdown() {
        for (UUID uuid : onlineLeashHolders.values()) {
            Entity e = Bukkit.getEntity(uuid);
            if (e != null) e.remove();
        }
        for (UUID uuid : offlineDummyBags.values()) {
            Entity e = Bukkit.getEntity(uuid);
            if (e != null) e.remove();
        }
        for (UUID uuid : bannedHolograms.values()) {
            Entity e = Bukkit.getEntity(uuid);
            if (e != null) e.remove();
        }
        onlineLeashHolders.clear();
        offlineDummyBags.clear();
        bannedHolograms.clear();
    }

    // -------------------------------------------------------
    // ENTITY CONFIGURATION
    // -------------------------------------------------------

    private void configureInvisibleHolder(LivingEntity mob) {
        mob.setCustomName(ChatColor.DARK_GRAY + "BoxTop Leash");
        mob.setCustomNameVisible(false);
        mob.setSilent(true);
        mob.setFireTicks(0);
        mob.setRemoveWhenFarAway(false);
        mob.setInvulnerable(true);

        try { mob.setAI(false); } catch (Exception ignored) {}
        try { mob.setCollidable(false); } catch (Exception ignored) {}

        mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 255, false, false));
        mob.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));

        try {
            Method m = mob.getClass().getMethod("setPersistent", boolean.class);
            m.invoke(mob, true);
        } catch (Exception ignored) {}

        if (mob instanceof Villager) {
            Villager v = (Villager) mob;
            try { v.setProfession(Villager.Profession.valueOf("NONE")); }
            catch (Exception e) {
                try { v.setProfession(Villager.Profession.valueOf("FARMER")); } catch (Exception ignored) {}
            }
        }
    }

    private void configureOfflineDummy(LivingEntity mob, UUID playerUUID, String playerName) {
        mob.setCustomName(ChatColor.RED + "✦ BANNED: " + ChatColor.YELLOW + playerName);
        mob.setCustomNameVisible(false);
        mob.setSilent(true);
        mob.setFireTicks(0);
        mob.setRemoveWhenFarAway(false);

        try { mob.setAI(true); } catch (Exception ignored) {}
        try { mob.setCollidable(false); } catch (Exception ignored) {}

        mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 255, false, false));
        mob.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));

        double targetHealth = 1024.0;
        try {
            org.bukkit.attribute.AttributeInstance maxHpAttr =
                mob.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (maxHpAttr != null) maxHpAttr.setBaseValue(targetHealth);
        } catch (Throwable e) {
            try { @SuppressWarnings("deprecation") double fallback = 1024.0; mob.setMaxHealth(fallback); } catch (Exception ignored) {}
        }
        mob.setHealth(targetHealth);

        try {
            Method m = mob.getClass().getMethod("setPersistent", boolean.class);
            m.invoke(mob, true);
        } catch (Exception ignored) {}

        if (mob instanceof Villager) {
            Villager v = (Villager) mob;
            try { v.setProfession(Villager.Profession.valueOf("NONE")); }
            catch (Exception e) {
                try { v.setProfession(Villager.Profession.valueOf("FARMER")); } catch (Exception ignored) {}
            }
        }

        ItemStack skull = XMaterial.PLAYER_HEAD.parseItem();
        if (skull == null) skull = new ItemStack(org.bukkit.Material.getMaterial("SKULL_ITEM"), 1, (short) 3);
        if (skull != null) {
            ItemMeta meta = skull.getItemMeta();
            if (meta instanceof SkullMeta) {
                SkullMeta sm = (SkullMeta) meta;
                try {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(playerUUID);
                    sm.setOwningPlayer(op);
                } catch (Throwable e) {
                    try { @SuppressWarnings("deprecation") boolean ok = sm.setOwner(playerName); } catch (Exception ignored) {}
                }
                skull.setItemMeta(sm);
            }
            if (mob.getEquipment() != null) {
                mob.getEquipment().setHelmet(skull);
                mob.getEquipment().setHelmetDropChance(0.0f);
            }
        }
    }


    private void attachLeashToFence(LivingEntity mob, Location loc, String assignedPostId) {
        if (loc == null || loc.getWorld() == null) return;

        // If slot assigned, tie right to the fence of that slot
        if (assignedPostId != null) {
            BanPostSlot slot = banPosts.get(assignedPostId);
            if (slot != null && slot.fenceLocation != null) {
                try {
                    LeashHitch hitch = findOrSpawnHitch(slot.fenceLocation);
                    if (hitch != null) {
                        mob.setLeashHolder(hitch);
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }

        // Fallback finder around center
        BlockFenceFinder finder = findNearestFenceOrHitch(loc);
        if (finder.hitch != null) {
            mob.setLeashHolder(finder.hitch);
        } else if (finder.fenceLocation != null) {
            try {
                LeashHitch hitch = findOrSpawnHitch(finder.fenceLocation);
                if (hitch != null) mob.setLeashHolder(hitch);
            } catch (Exception e) {
                plugin.getLogger().fine("[BoxTop] Could not spawn LeashHitch at fence: " + e.getMessage());
            }
        }
    }

    private LeashHitch findOrSpawnHitch(Location fenceLoc) {
        World w = fenceLoc.getWorld();
        if (w == null) return null;
        for (Entity e : w.getNearbyEntities(fenceLoc, 1.5, 1.5, 1.5)) {
            if (e instanceof LeashHitch) {
                return (LeashHitch) e;
            }
        }
        return w.spawn(fenceLoc, LeashHitch.class);
    }

    private static class BlockFenceFinder {
        LeashHitch hitch = null;
        Location fenceLocation = null;
    }

    private BlockFenceFinder findNearestFenceOrHitch(Location center) {
        BlockFenceFinder res = new BlockFenceFinder();
        World w = center.getWorld();
        if (w == null) return res;

        for (Entity e : w.getNearbyEntities(center, 5, 5, 5)) {
            if (e instanceof LeashHitch) {
                res.hitch = (LeashHitch) e;
                return res;
            }
        }

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 6; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    org.bukkit.block.Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                    String typeName = b.getType().name();
                    if (typeName.contains("FENCE") || typeName.contains("WALL") || typeName.contains("POST")) {
                        res.fenceLocation = b.getLocation();
                        return res;
                    }
                }
            }
        }
        return res;
    }
}
