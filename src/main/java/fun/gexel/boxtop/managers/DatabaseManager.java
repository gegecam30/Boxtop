package fun.gexel.boxtop.managers;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.objects.PlayerStat;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Gestiona estadísticas de jugadores usando H2 en modo embedded.
 *
 * Por qué H2 y no SQLite:
 *   SQLite-JDBC incluye código nativo (.dll/.so) que el maven-shade-plugin
 *   rompe al relocar los paquetes → UnsatisfiedLinkError en arranque.
 *   H2 es 100% Java puro, se reloca sin problemas y genera un único archivo .mv.db.
 *
 * Archivo generado: plugins/BoxTop/boxtop_stats.mv.db
 */
public class DatabaseManager {

    private final BoxTopPlugin plugin;
    private Connection connection;

    // Cache en memoria — O(1) para lecturas frecuentes del top y stats personales
    private final Map<UUID, PlayerStat> cache = new HashMap<>();

    public DatabaseManager(BoxTopPlugin plugin) {
        this.plugin = plugin;
        connect();
        createTable();
        loadAllIntoCache();
    }

    // -------------------------------------------------------
    // CONEXIÓN
    // -------------------------------------------------------

    private void connect() {
        try {
            // H2 relocado por shade — usamos el nombre relocado del driver
            Class.forName("fun.gexel.boxtop.utils.h2.Driver");

            File dbFile = new File(plugin.getDataFolder(), "boxtop_stats");
            // MODE=MySQL para sintaxis compatible; DB_CLOSE_ON_EXIT=FALSE evita cierre prematuro
            String url = "jdbc:h2:file:" + dbFile.getAbsolutePath()
                       + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;AUTO_RECONNECT=TRUE";

            connection = DriverManager.getConnection(url, "sa", "");
            plugin.getLogger().info("[BoxTop] H2 database connected. File: boxtop_stats.mv.db");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[BoxTop] Failed to connect to H2 database!", e);
        }
    }

    private void createTable() {
        if (connection == null) return;
        String sql = "CREATE TABLE IF NOT EXISTS player_stats ("
                   + "  uuid   VARCHAR(36) PRIMARY KEY NOT NULL,"
                   + "  name   VARCHAR(64) NOT NULL,"
                   + "  damage DOUBLE      NOT NULL DEFAULT 0"
                   + ")";
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[BoxTop] Failed to create player_stats table!", e);
        }
    }

    private void loadAllIntoCache() {
        if (connection == null) return;
        cache.clear();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, name, damage FROM player_stats")) {
            while (rs.next()) {
                UUID uuid   = UUID.fromString(rs.getString("uuid"));
                String name = rs.getString("name");
                double dmg  = rs.getDouble("damage");
                cache.put(uuid, new PlayerStat(name, uuid, dmg));
            }
            plugin.getLogger().info("[BoxTop] Loaded " + cache.size() + " player record(s) into cache.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[BoxTop] Failed to load player stats!", e);
        }
    }

    // -------------------------------------------------------
    // API PÚBLICA
    // -------------------------------------------------------

    /**
     * Actualiza el cache inmediatamente (hilo principal) y persiste async.
     */
    public void addDamage(UUID uuid, String playerName, double damage) {
        PlayerStat stat = cache.get(uuid);
        if (stat == null) {
            stat = new PlayerStat(playerName, uuid, damage);
            cache.put(uuid, stat);
        } else {
            stat.addDamage(damage);
        }

        final double totalDamage = stat.getDamage();
        final String name = playerName;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            upsertPlayer(uuid, name, totalDamage)
        );
    }

    /** Nunca devuelve null. */
    public PlayerStat getPlayerStat(UUID uuid) {
        return cache.getOrDefault(uuid, new PlayerStat("Unknown", uuid, 0));
    }

    /** Top N ordenado por daño descendente, operado en memoria. */
    public List<PlayerStat> getTop(int limit) {
        List<PlayerStat> sorted = new ArrayList<>(cache.values());
        Collections.sort(sorted);
        return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
    }

    /** Llamar desde onDisable() para flush y cierre limpio. */
    public void close() {
        if (connection != null) {
            try {
                // H2 necesita SHUTDOWN explícito en modo FILE para flush final
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
    // INTERNAL
    // -------------------------------------------------------

    private void upsertPlayer(UUID uuid, String name, double damage) {
        if (connection == null) return;
        // MERGE INTO es el equivalente H2 de INSERT OR REPLACE
        String sql = "MERGE INTO player_stats (uuid, name, damage) KEY(uuid) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setDouble(3, damage);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[BoxTop] Failed to upsert player: " + name, e);
        }
    }
}
