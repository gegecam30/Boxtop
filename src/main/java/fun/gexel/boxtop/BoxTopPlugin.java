package fun.gexel.boxtop;

import fun.gexel.boxtop.commands.BoxTopCommand;
import fun.gexel.boxtop.commands.BoxTopTab;
import fun.gexel.boxtop.gui.BagConfigGUI;
import fun.gexel.boxtop.gui.DuelGUI;
import fun.gexel.boxtop.listeners.BagConfigListener;
import fun.gexel.boxtop.listeners.DuelListener;
import fun.gexel.boxtop.listeners.HitListener;
import fun.gexel.boxtop.managers.*;
import fun.gexel.boxtop.tasks.ParticleTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

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
    private BagConfigGUI configGUI;
    private BagSpawnManager spawnManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.dataManager    = new DataManager(this);
        this.rewardManager  = new RewardManager(this);
        this.glowManager    = new GlowManager(this, dataManager);
        this.staminaManager = new StaminaManager(this);
        this.soundManager   = new SoundManager(this);
        this.duelManager    = new DuelManager(this, dataManager, glowManager, soundManager);
        this.duelGUI        = new DuelGUI(this, dataManager);
        this.configGUI      = new BagConfigGUI(this, dataManager, glowManager);
        this.spawnManager   = new BagSpawnManager(this, dataManager);

        ParticleTask particleTask = new ParticleTask(this, dataManager, glowManager);
        particleTask.runTaskTimer(this, 20L, 20L);

        getCommand("boxtop").setExecutor(
            new BoxTopCommand(dataManager, glowManager, duelManager, particleTask, configGUI, spawnManager)
        );
        getCommand("boxtop").setTabCompleter(new BoxTopTab(dataManager));

        getServer().getPluginManager().registerEvents(
            new HitListener(this, dataManager, rewardManager, staminaManager, soundManager, duelManager), this
        );
        getServer().getPluginManager().registerEvents(
            new DuelListener(this, dataManager, duelManager, duelGUI, configGUI), this
        );
        getServer().getPluginManager().registerEvents(
            new BagConfigListener(this, dataManager, glowManager, configGUI), this
        );
        getServer().getPluginManager().registerEvents(
            new fun.gexel.boxtop.listeners.GlowListener(this, glowManager), this
        );

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new BoxTopExpansion(this, dataManager).register();
        }

        glowManager.updateGlow();

        Metrics metrics = new Metrics(this, 29030);
        metrics.addCustomChart(new SimplePie("particles_enabled", () ->
            getConfig().getBoolean("particles.enabled") ? "Yes" : "No"
        ));

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (glowManager != null) glowManager.updateGlow();
        }, 100L, 100L);

        getLogger().info("BoxTop v2.8 enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("BoxTop disabling...");
        if (dataManager != null && glowManager != null) {
            for (java.util.UUID uuid : dataManager.getAllBagUUIDs()) {
                glowManager.removeGlow(uuid);
            }
        }
        if (dataManager != null) dataManager.shutdown();
        getLogger().info("BoxTop disabled.");
    }
}
