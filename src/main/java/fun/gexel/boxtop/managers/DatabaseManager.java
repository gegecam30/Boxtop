package fun.gexel.boxtop.managers;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.objects.PlayerStat;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Gestiona todas las operaciones de base de datos SQLite
 * para las estadísticas de jugadores.
 *
 * DISEÑO:
 * - Un único archivo boxtop_stats.db en la carpeta del plugin.
 * - Tabla "player_stats": uuid (PK), name, damage.
 * - Todas las escrituras son async para no bloquear el hilo principal.
 * - Las lecturas del top se hacen en memoria (cache) para las queries frecuentes.
 */
public class DatabaseManager {

    private final BoxTopPlugin plugin;
    private Connection connection;

    // Cache en memoria — se sincroniza con la DB al inicio y en cada write
    private final Map<UUID, PlayerStat> cache = new HashMap<>();

    // -------------------------------------------------------
    // INICIALIZACIÓN
    // -------------------------------------------------------

    public DatabaseManager(BoxTopPlugin plugin) {
        this.plugin = plugin;
        connect();
        createTable();
        loadAllIntoCache();
    }

    private void connect() {
        try {
            // Cargamos el driver de SQLite que viene incluido en el JDK desde Java 8
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(plugin.getDataFolder(), "boxtop_stats.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            // Optimizaciones de rendimiento para SQLite
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");   // Write-Ahead Logging: más rápido en writes
                st.execute("PRAGMA synchronous=NORMAL"); // Balance entre seguridad y velocidad
            }

            plugin.getLogger().info("[BoxTop] SQLite database connected successfully.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[BoxTop] Failed to connect to SQLite database!", e);
        }
    }

    private void createTable() {
        if (connection == null) return;
        String sql = "CREATE TABLE IF NOT EXISTS player_stats (" +
                     "  uuid   TEXT PRIMARY KEY NOT NULL," +
                     "  name   TEXT NOT NULL," +
                     "  damage REAL NOT NULL DEFAULT 0" +
                     ");";
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[BoxTop] Failed to create player_stats table!", e);
        }
    }

    /**
     * Carga todos los registros de la DB al cache en memoria al iniciar.
     * Esto permite que getTop() y getPlayerStat() sean O(1) sin hits a disco.
     */
    private void loadAllIntoCache() {
        if (connection == null) return;
        cache.clear();
        String sql = "SELECT uuid, name, damage FROM player_stats";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                UUID uuid   = UUID.fromString(rs.getString("uuid"));
                String name = rs.getString("name");
                double dmg  = rs.getDouble("damage");
                cache.put(uuid, new PlayerStat(name, uuid, dmg));
            }
            plugin.getLogger().info("[BoxTop] Loaded " + cache.size() + " player records into cache.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[BoxTop] Failed to load player stats from DB!", e);
        }
    }

    // -------------------------------------------------------
    // API PÚBLICA
    // -------------------------------------------------------

    /**
     * Añade daño al jugador. Actualiza el cache inmediatamente (sync)
     * y persiste en la DB de forma asíncrona para no bloquear el tick.
     */
    public void addDamage(UUID uuid, String playerName, double damage) {
        // 1. Actualizar cache (inmediato, en el hilo principal)
        PlayerStat stat = cache.get(uuid);
        if (stat == null) {
            stat = new PlayerStat(playerName, uuid, damage);
            cache.put(uuid, stat);
        } else {
            stat.addDamage(damage);
        }

        // Capturamos el valor final para el lambda
        final double finalDamage = stat.getDamage();
        final String finalName   = playerName;

        // 2. Persistir en DB (asíncrono — no bloquea el servidor)
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            upsertPlayer(uuid, finalName, finalDamage);
        });
    }

    /**
     * Devuelve el stat de un jugador desde el cache.
     * Nunca devuelve null — retorna un PlayerStat vacío si no existe.
     */
    public PlayerStat getPlayerStat(UUID uuid) {
        return cache.getOrDefault(uuid, new PlayerStat("Unknown", uuid, 0));
    }

    /**
     * Devuelve el top N de jugadores, ordenado por daño descendente.
     * Opera enteramente en memoria (O(n log n) sobre el cache).
     */
    public List<PlayerStat> getTop(int limit) {
        List<PlayerStat> sorted = new ArrayList<>(cache.values());
        Collections.sort(sorted);
        return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
    }

    /**
     * Cierra la conexión limpiamente al apagar el plugin.
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("[BoxTop] Database connection closed.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[BoxTop] Error closing database connection.", e);
            }
        }
    }

    // -------------------------------------------------------
    // OPERACIONES INTERNAS (privadas)
    // -------------------------------------------------------

    /**
     * INSERT OR REPLACE — inserta si no existe, actualiza si ya existe.
     * Llamado siempre desde un hilo asíncrono.
     */
    private void upsertPlayer(UUID uuid, String name, double damage) {
        if (connection == null) return;
        String sql = "INSERT OR REPLACE INTO player_stats (uuid, name, damage) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setDouble(3, damage);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[BoxTop] Failed to upsert player " + name, e);
        }
    }
}
