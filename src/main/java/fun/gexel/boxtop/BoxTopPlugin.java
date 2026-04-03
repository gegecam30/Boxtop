package fun.gexel.boxtop;

import fun.gexel.boxtop.commands.BoxTopCommand;
import fun.gexel.boxtop.commands.BoxTopTab;
import fun.gexel.boxtop.gui.DuelGUI;
import fun.gexel.boxtop.listeners.DuelListener;
import fun.gexel.boxtop.listeners.HitListener;
import fun.gexel.boxtop.managers.*;
import fun.gexel.boxtop.tasks.ParticleTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

// --- IMPORTS DE BSTATS ---
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie; 

public class BoxTopPlugin extends JavaPlugin {

    private DataManager dataManager;
    private RewardManager rewardManager;
    private GlowManager glowManager;
    private StaminaManager staminaManager;
    private SoundManager soundManager;
    private DuelManager duelManager;
    private DuelGUI duelGUI;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        // 1. Inicializar Managers
        this.dataManager = new DataManager(this);
        this.rewardManager = new RewardManager(this);
        this.glowManager = new GlowManager(this, dataManager);
        this.staminaManager = new StaminaManager(this);
        this.soundManager = new SoundManager(this);
        
        // 2. Inicializar Sistema de Duelos
        this.duelManager = new DuelManager(this, dataManager, glowManager, soundManager);
        this.duelGUI = new DuelGUI(this, dataManager);

        // 3. Tareas (Partículas) - ¡MOVIDO AQUÍ ARRIBA!
        // Creamos la tarea ANTES de registrar el comando para poder pasársela.
        ParticleTask particleTask = new ParticleTask(this, dataManager, glowManager);
        particleTask.runTaskTimer(this, 20L, 20L); 

        // 4. Comandos (Ahora sí conoce 'particleTask')
        getCommand("boxtop").setExecutor(new BoxTopCommand(dataManager, glowManager, duelManager, particleTask));
        getCommand("boxtop").setTabCompleter(new BoxTopTab());

        // 5. Listeners
        getServer().getPluginManager().registerEvents(
            new HitListener(this, dataManager, rewardManager, staminaManager, soundManager, duelManager), this
        );
        getServer().getPluginManager().registerEvents(
            new DuelListener(this, dataManager, duelManager, duelGUI), this
        );
        getServer().getPluginManager().registerEvents(
            new fun.gexel.boxtop.listeners.GlowListener(this, glowManager), this
        );

        // 6. PlaceholderAPI Hook
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new BoxTopExpansion(this, dataManager).register();
        }
        
        // Asegurar que el glow se aplique al iniciar
        glowManager.updateGlow();

        // 7. BSTATS METRICS
        int pluginId = 29030; 
        Metrics metrics = new Metrics(this, pluginId);
        
        metrics.addCustomChart(new SimplePie("particles_enabled", () ->
            getConfig().getBoolean("particles.enabled") ? "Yes" : "No"
        ));

        // 8. TAREA DE MANTENIMIENTO DE GLOW (Anti-Bug Blanco)
        // Ejecuta updateGlow() cada 100 ticks (5 segundos) es suficiente y ahorra recursos
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (glowManager != null) {
                glowManager.updateGlow();
            }
        }, 100L, 100L);

        getLogger().info("BoxTop v2.6 enabled with Arcade Duels!");
    }

    @Override
    public void onDisable() {
        getLogger().info("BoxTop disabled.");
        
        // Limpiar glows al apagar para que no se queden entidades brillantes por el mundo
        if (dataManager != null && glowManager != null) {
            for (java.util.UUID uuid : dataManager.getAllBagUUIDs()) {
                glowManager.removeGlow(uuid);
            }
        }
    }
}