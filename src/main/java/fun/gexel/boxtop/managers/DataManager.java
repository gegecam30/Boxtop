package fun.gexel.boxtop.managers;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.objects.BagData;
import fun.gexel.boxtop.objects.PlayerStat;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * DataManager refactorizado.
 *
 * RESPONSABILIDADES:
 *  - Gestión de BagData (configuración por saco) → data.yml
 *  - Mensajes y acceso al config.yml
 *  - Delegación de estadísticas de jugadores → DatabaseManager
 *
 * MIGRACIÓN TRANSPARENTE:
 *  Compatible con el formato antiguo de data.yml (uuid como string simple).
 *  Al guardar por primera vez, se escribe el nuevo formato con todos los campos.
 */
public class DataManager {

    private final BoxTopPlugin plugin;

    // --- ALMACENAMIENTO DE SACOS (YAML) ---
    private final Map<String, BagData> boxingBags = new LinkedHashMap<>(); // LinkedHashMap preserva orden de inserción
    private File dataFile;
    private FileConfiguration dataConfig;

    // --- ESTADÍSTICAS DE JUGADORES (SQLite) ---
    private final DatabaseManager databaseManager;

    // -------------------------------------------------------
    // CONSTRUCTOR
    // -------------------------------------------------------

    public DataManager(BoxTopPlugin plugin) {
        this.plugin = plugin;
        this.databaseManager = new DatabaseManager(plugin);
        createDataFile();
        loadBags();
    }

    // -------------------------------------------------------
    // GESTIÓN DEL data.yml
    // -------------------------------------------------------

    private void createDataFile() {
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void saveDataFile() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------
    // CARGA DE SACOS (con migración transparente)
    // -------------------------------------------------------

    /**
     * Carga todos los sacos desde data.yml.
     * Soporta el formato antiguo (uuid como string directo) y el nuevo (objeto completo).
     */
    private void loadBags() {
        boxingBags.clear();
        if (!dataConfig.contains("boxing-bags")) return;

        for (String bagName : dataConfig.getConfigurationSection("boxing-bags").getKeys(false)) {
            try {
                BagData bag = BagData.deserialize(bagName, dataConfig);
                boxingBags.put(bagName, bag);
            } catch (Exception e) {
                plugin.getLogger().warning("[BoxTop] Error loading bag '" + bagName + "': " + e.getMessage());
            }
        }

        plugin.getLogger().info("[BoxTop] Loaded " + boxingBags.size() + " boxing bag(s).");
    }

    // -------------------------------------------------------
    // RECARGA
    // -------------------------------------------------------

    public void reloadConfig() {
        plugin.reloadConfig();
        createDataFile();
        loadBags();
    }

    // -------------------------------------------------------
    // API DE SACOS — CRUD
    // -------------------------------------------------------

    /**
     * Añade un nuevo saco con configuración por defecto (heredada del config.yml global).
     * Los valores globales del config.yml se usan como punto de partida.
     */
    public BagData addBoxingBag(String name, java.util.UUID uuid) {
        BagData bag = new BagData(name, uuid);

        // Heredar valores globales del config.yml como defaults iniciales
        bag.setGlowEnabled(plugin.getConfig().getBoolean("glow.enabled", true));
        bag.setGlowColor(parseColor(plugin.getConfig().getString("glow.color", "GOLD")));
        bag.setParticlesEnabled(plugin.getConfig().getBoolean("particles.enabled", true));
        bag.setParticleType(plugin.getConfig().getString("particles.type", "VILLAGER_HAPPY"));
        bag.setPhysicsEnabled(plugin.getConfig().getBoolean("physics.enabled", true));
        bag.setKnockbackStrength(plugin.getConfig().getDouble("physics.knockback-strength", 0.4));
        bag.setVerticalStrength(plugin.getConfig().getDouble("physics.vertical-strength", 0.1));

        boxingBags.put(name.toLowerCase(), bag);
        saveBag(bag);
        return bag;
    }

    /**
     * Elimina un saco por nombre.
     * @return true si existía y fue eliminado, false si no se encontró.
     */
    public boolean removeBoxingBag(String name) {
        String key = name.toLowerCase();
        if (!boxingBags.containsKey(key)) return false;

        boxingBags.remove(key);
        dataConfig.set("boxing-bags." + key, null);
        saveDataFile();
        return true;
    }

    /**
     * Persiste un BagData específico en el YAML.
     * Solo reescribe ese saco, no todo el archivo.
     */
    public void saveBag(BagData bag) {
        bag.serialize(dataConfig);
        saveDataFile();
    }

    // -------------------------------------------------------
    // API DE SACOS — CONSULTAS
    // -------------------------------------------------------

    public BagData getBag(String name) {
        return boxingBags.get(name.toLowerCase());
    }

    public BagData getBagByUUID(java.util.UUID uuid) {
        for (BagData bag : boxingBags.values()) {
            if (bag.getUuid().equals(uuid)) return bag;
        }
        return null;
    }

    public java.util.UUID getBagUUID(String name) {
        BagData bag = boxingBags.get(name.toLowerCase());
        return bag != null ? bag.getUuid() : null;
    }

    public boolean isBoxingBag(java.util.UUID uuid) {
        for (BagData bag : boxingBags.values()) {
            if (bag.getUuid().equals(uuid)) return true;
        }
        return false;
    }

    public Set<String> getBagNames() {
        return boxingBags.keySet();
    }

    public Collection<BagData> getAllBags() {
        return boxingBags.values();
    }

    /**
     * Shortcut: retorna solo los UUIDs (para compatibilidad con GlowManager y ParticleTask).
     */
    public Collection<java.util.UUID> getAllBagUUIDs() {
        List<java.util.UUID> uuids = new ArrayList<>();
        for (BagData bag : boxingBags.values()) {
            uuids.add(bag.getUuid());
        }
        return uuids;
    }

    // -------------------------------------------------------
    // DELEGACIÓN A DatabaseManager (estadísticas de jugadores)
    // -------------------------------------------------------

    public void addDamage(java.util.UUID uuid, String playerName, double damage) {
        databaseManager.addDamage(uuid, playerName, damage);
    }

    public PlayerStat getPlayerStat(java.util.UUID uuid) {
        return databaseManager.getPlayerStat(uuid);
    }

    public List<PlayerStat> getTop(int limit) {
        return databaseManager.getTop(limit);
    }

    /**
     * Debe llamarse desde onDisable() para cerrar la conexión SQLite limpiamente.
     */
    public void shutdown() {
        databaseManager.close();
    }

    // -------------------------------------------------------
    // MENSAJES (sin cambios respecto al original)
    // -------------------------------------------------------

    public String getMessage(String path) {
        String msg = plugin.getConfig().getString(path);
        if (msg == null) msg = plugin.getConfig().getString("messages." + path);
        if (msg == null) return "Msg error: " + path;
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        return ChatColor.translateAlternateColorCodes('&', prefix + msg);
    }

    public String getRawMessage(String path) {
        String msg = plugin.getConfig().getString(path);
        if (msg == null) return path;
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public List<String> getStringList(String path) {
        if (plugin.getConfig().contains(path)) {
            return plugin.getConfig().getStringList(path);
        }
        return new ArrayList<>();
    }

    // -------------------------------------------------------
    // HELPERS PRIVADOS
    // -------------------------------------------------------

    private ChatColor parseColor(String s) {
        try {
            return ChatColor.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return ChatColor.GOLD;
        }
    }
}
