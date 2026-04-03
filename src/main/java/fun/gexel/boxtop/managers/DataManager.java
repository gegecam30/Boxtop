package fun.gexel.boxtop.managers;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.objects.PlayerStat;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class DataManager {

    private final BoxTopPlugin plugin;
    private final Map<UUID, PlayerStat> stats = new HashMap<>();
    private final Map<String, UUID> boxingBags = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    public DataManager(BoxTopPlugin plugin) {
        this.plugin = plugin;
        createDataFile();
        loadSystemConfig();
        loadStats();
    }

    public void reloadConfig() {
        plugin.reloadConfig();
        createDataFile();
        loadSystemConfig();
    }

    // --- GESTIÓN DE DATA.YML ---
    private void createDataFile() {
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
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

    // --- CARGA DE DATOS ---
    private void loadSystemConfig() {
        boxingBags.clear();
        if (dataConfig.contains("boxing-bags")) {
            for (String key : dataConfig.getConfigurationSection("boxing-bags").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(dataConfig.getString("boxing-bags." + key));
                    boxingBags.put(key, uuid);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("UUID invalido para saco: " + key);
                }
            }
        }
    }

    private void loadStats() {
        stats.clear();
        if (dataConfig.contains("stats")) {
            for (String uuidStr : dataConfig.getConfigurationSection("stats").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String name = dataConfig.getString("stats." + uuidStr + ".name");
                    double damage = dataConfig.getDouble("stats." + uuidStr + ".damage");
                    stats.put(uuid, new PlayerStat(name, uuid, damage));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // --- MÉTODOS PÚBLICOS (API) ---

    public void addBoxingBag(String name, UUID uuid) {
        boxingBags.put(name.toLowerCase(), uuid);
        saveBagsToConfig();
    }

    public boolean removeBoxingBag(String name) {
        if (boxingBags.containsKey(name.toLowerCase())) {
            boxingBags.remove(name.toLowerCase());
            dataConfig.set("boxing-bags." + name.toLowerCase(), null); 
            saveDataFile();
            return true;
        }
        return false;
    }

    public UUID getBagUUID(String name) {
        return boxingBags.get(name.toLowerCase());
    }

    public Collection<UUID> getAllBagUUIDs() {
        return boxingBags.values();
    }

    // [FIX 1] Agregado: Requerido por HitListener y DuelListener
    public boolean isBoxingBag(UUID uuid) {
        return boxingBags.containsValue(uuid);
    }

    // [FIX 2] Agregado: Requerido por BoxTopCommand (para listar sacos)
    public Set<String> getBagNames() {
        return boxingBags.keySet();
    }

    private void saveBagsToConfig() {
        for (Map.Entry<String, UUID> entry : boxingBags.entrySet()) {
            dataConfig.set("boxing-bags." + entry.getKey(), entry.getValue().toString());
        }
        saveDataFile();
    }

    // Stats
    public void addDamage(UUID uuid, String playerName, double damage) {
        PlayerStat stat = stats.get(uuid);
        if (stat == null) {
            stat = new PlayerStat(playerName, uuid, damage);
            stats.put(uuid, stat);
        } else {
            stat.addDamage(damage);
        }
        saveData(); 
    }

    // [FIX 3] Agregado: Requerido por HitListener y BoxTopCommand
    public PlayerStat getPlayerStat(UUID uuid) {
        // Devuelve el stat existente o uno vacío temporal (evita NullPointerException)
        return stats.getOrDefault(uuid, new PlayerStat("Unknown", uuid, 0));
    }

    public List<PlayerStat> getTop(int limit) {
        List<PlayerStat> sorted = new ArrayList<>(stats.values());
        Collections.sort(sorted);
        if (sorted.size() > limit) {
            return sorted.subList(0, limit);
        }
        return sorted;
    }

    public void saveData() {
        dataConfig.set("stats", null);
        for (PlayerStat stat : stats.values()) {
            String path = "stats." + stat.getUuid().toString();
            dataConfig.set(path + ".name", stat.getPlayerName());
            dataConfig.set(path + ".damage", stat.getDamage());
        }
        saveBagsToConfig(); 
    }

    // --- MENSAJES ---
    public String getMessage(String path) {
        String msg = plugin.getConfig().getString(path);
        if (msg == null) msg = plugin.getConfig().getString("messages." + path);
        if (msg == null) return "Msg error: " + path;
        return ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("messages.prefix") + msg);
    }
    
    public String getRawMessage(String path) {
        String msg = plugin.getConfig().getString(path);
        if (msg == null) return path;
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    // [FIX 4] Mantenido: Requerido por DuelManager
    public List<String> getStringList(String path) {
        if (plugin.getConfig().contains(path)) {
            return plugin.getConfig().getStringList(path);
        }
        return new ArrayList<>();
    }
}