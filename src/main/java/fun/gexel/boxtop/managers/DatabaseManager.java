package fun.gexel.boxtop.managers;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.objects.PlayerStat;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Manages player statistics using H2 embedded database.
 *
 * Two tables:
 *   player_stats      — global damage per player (existing, unchanged)
 *   boxtop_bag_stats  — per-bag stats: damage, hits, best_hit (new in v3.1)
 *
 * All writes are asynchronous. Reads come from the in-memory cache (O(1)).
 */
public class DatabaseManager {

    private final BoxTopPlugin plugin;
    private Connection connection;

    // Global cache (existing behaviour — unchanged)
    private final Map<UUID, PlayerStat> cache = new HashMap<>();

    // Per-bag cache: bagName (lowercase) → playerUUID → stat
    private final Map<String, Map<UUID, PlayerStat>> bagCache = new HashMap<>();

    public DatabaseManager(BoxTopPlugin plugin) {
        this.plugin = plugin;
        connect();
        createTables();
        loadAllIntoCache();
    }

    // -------------------------------------------------------
    // CONNECTION
    // -------------------------------------------------------

    private void connect() {
        try {
            Class.forName("fun.gexel.boxtop.utils.h2.Driver");
            File dbFile = new File(plugin.getDataFolder(), "boxtop_stats");
            String url  = "jdbc:h2:file:" + dbFile.getAbsolutePath()
                        + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;AUTO_RECONNECT=TRUE";
            connection = DriverManager.getConnection(url, "sa", "");
            plugin.getLogger().info("[BoxTop] H2 database connected.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[BoxTop] Failed to connect to H2!", e);
        }
    }

    private void createTables() {
        if (connection == null) return;
        String[] sql = {
            // Existing table — kept exactly as-is for backward compat
            "CREATE TABLE IF NOT EXISTS player_stats ("
          + "  uuid   VARCHAR(36) PRIMARY KEY NOT NULL,"
          + "  name   VARCHAR(64) NOT NULL,"
          + "  damage DOUBLE      NOT NULL DEFAULT 0"
          + ")",
            // New per-bag stats table
            "CREATE TABLE IF NOT EXISTS boxtop_bag_stats ("
          + "  bag_name VARCHAR(64) NOT NULL,"
          + "  uuid     VARCHAR(36) NOT NULL,"
          + "  name     VARCHAR(64) NOT NULL,"
          + "  damage   DOUBLE      NOT NULL DEFAULT 0,"
          + "  hits     INT         NOT NULL DEFAULT 0,"
          + "  best_hit DOUBLE      NOT NULL DEFAULT 0,"
          + "  PRIMARY KEY (bag_name, uuid)"
          + ")"
        };
        try (Statement st = connection.createStatement()) {
            for (String s : sql) st.execute(s);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[BoxTop] Failed to create tables!", e);
        }
    }

    private void loadAllIntoCache() {
        if (connection == null) return;
        cache.clear();
        bagCache.clear();
        try (Statement st = connection.createStatement()) {
            // Global stats
            try (ResultSet rs = st.executeQuery("SELECT uuid, name, damage FROM player_stats")) {
                while (rs.next()) {
                    UUID   uuid = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("name");
                    double dmg  = rs.getDouble("damage");
                    cache.put(uuid, new PlayerStat(name, uuid, dmg));
                }
            }
            // Per-bag stats
            try (ResultSet rs = st.executeQuery(
                    "SELECT bag_name, uuid, name, damage, hits, best_hit FROM boxtop_bag_stats")) {
                int count = 0;
                while (rs.next()) {
                    String bagName = rs.getString("bag_name").toLowerCase();
                    UUID   uuid    = UUID.fromString(rs.getString("uuid"));
                    String name    = rs.getString("name");
                    double dmg     = rs.getDouble("damage");
                    int    hits    = rs.getInt("hits");
                    double bestHit = rs.getDouble("best_hit");
                    bagCache.computeIfAbsent(bagName, k -> new HashMap<>())
                            .put(uuid, new PlayerStat(name, uuid, dmg, hits, bestHit));
                    count++;
                }
                plugin.getLogger().info("[BoxTop] Loaded " + count + " per-bag record(s) into cache.");
            }
            plugin.getLogger().info("[BoxTop] Loaded " + cache.size() + " global record(s) into cache.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[BoxTop] Failed to load stats from H2!", e);
        }
    }

    // -------------------------------------------------------
    // PUBLIC API — GLOBAL (unchanged / backward-compatible)
    // -------------------------------------------------------

    public void addDamage(UUID uuid, String playerName, double damage) {
        PlayerStat stat = cache.get(uuid);
        if (stat == null) {
            stat = new PlayerStat(playerName, uuid, damage);
            cache.put(uuid, stat);
        } else {
            stat.addDamage(damage);
        }
        final double total = stat.getDamage();
        final String name  = playerName;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
            () -> upsertGlobal(uuid, name, total));
    }

    /** Never returns null. */
    public PlayerStat getPlayerStat(UUID uuid) {
        return cache.getOrDefault(uuid, new PlayerStat("Unknown", uuid, 0));
    }

    /** Top N sorted by damage descending, operates on cache. */
    public List<PlayerStat> getTop(int limit) {
        List<PlayerStat> sorted = new ArrayList<>(cache.values());
        Collections.sort(sorted);
        return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
    }

    // -------------------------------------------------------
    // PUBLIC API — PER-BAG (new in v3.1)
    // -------------------------------------------------------

    /**
     * Increments per-bag stats: damage + hit count + best hit.
     * Updates cache immediately (main thread), persists async.
     */
    public void addBagDamage(String bagName, UUID uuid, String playerName, double damage) {
        String key = bagName.toLowerCase();
        Map<UUID, PlayerStat> map = bagCache.computeIfAbsent(key, k -> new HashMap<>());
        PlayerStat stat = map.get(uuid);
        if (stat == null) {
            stat = new PlayerStat(playerName, uuid, damage, 1, damage);
            map.put(uuid, stat);
        } else {
            stat.addDamageAndHit(damage);
        }
        final double total   = stat.getDamage();
        final int    hits    = stat.getHits();
        final double bestHit = stat.getBestHit();
        final String name    = playerName;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
            () -> upsertBag(key, uuid, name, total, hits, bestHit));
    }

    /** Never returns null. */
    public PlayerStat getBagPlayerStat(String bagName, UUID uuid) {
        Map<UUID, PlayerStat> map = bagCache.get(bagName.toLowerCase());
        if (map != null && map.containsKey(uuid)) return map.get(uuid);
        return new PlayerStat("Unknown", uuid, 0, 0, 0.0);
    }

    /** Top N for a specific bag, sorted by damage descending. */
    public List<PlayerStat> getBagTop(String bagName, int limit) {
        Map<UUID, PlayerStat> map = bagCache.get(bagName.toLowerCase());
        if (map == null || map.isEmpty()) return Collections.emptyList();
        List<PlayerStat> sorted = new ArrayList<>(map.values());
        Collections.sort(sorted);
        return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
    }

    /** Returns 1-based rank, or -1 if player has no data for this bag. */
    public int getPlayerBagRank(String bagName, UUID uuid) {
        Map<UUID, PlayerStat> map = bagCache.get(bagName.toLowerCase());
        if (map == null || !map.containsKey(uuid)) return -1;
        List<PlayerStat> sorted = new ArrayList<>(map.values());
        Collections.sort(sorted);
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getUuid().equals(uuid)) return i + 1;
        }
        return -1;
    }

    /** Wipes all stats for a specific bag (admin reset). Async H2 delete. */
    public void clearBagStats(String bagName) {
        final String key = bagName.toLowerCase();
        bagCache.remove(key);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (connection == null) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM boxtop_bag_stats WHERE LOWER(bag_name) = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
                plugin.getLogger().info("[BoxTop] Cleared ranking for bag: " + key);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                    "[BoxTop] Failed to clear ranking for bag: " + key, e);
            }
        });
    }

    // -------------------------------------------------------
    // SHUTDOWN
    // -------------------------------------------------------

    public void close() {
        if (connection != null) {
            try {
                try (Statement st = connection.createStatement()) {
                    st.execute("SHUTDOWN");
                } catch (Exception ignored) {}
                connection.close();
                plugin.getLogger().info("[BoxTop] Database closed cleanly.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[BoxTop] Error closing database.", e);
            }
        }
    }

    // -------------------------------------------------------
    // INTERNAL WRITES (async)
    // -------------------------------------------------------

    private void upsertGlobal(UUID uuid, String name, double damage) {
        if (connection == null) return;
        String sql = "MERGE INTO player_stats (uuid, name, damage) KEY(uuid) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setDouble(3, damage);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[BoxTop] Failed to upsert global player: " + name, e);
        }
    }

    private void upsertBag(String bagName, UUID uuid, String name,
                           double damage, int hits, double bestHit) {
        if (connection == null) return;
        String sql = "MERGE INTO boxtop_bag_stats "
                   + "(bag_name, uuid, name, damage, hits, best_hit) "
                   + "KEY(bag_name, uuid) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, bagName);
            ps.setString(2, uuid.toString());
            ps.setString(3, name);
            ps.setDouble(4, damage);
            ps.setInt   (5, hits);
            ps.setDouble(6, bestHit);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[BoxTop] Failed to upsert bag stat: " + name, e);
        }
    }
}
