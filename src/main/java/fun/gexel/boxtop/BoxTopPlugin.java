package fun.gexel.boxtop;

import fun.gexel.boxtop.commands.BoxTopCommand;
import fun.gexel.boxtop.commands.BoxTopTab;
import fun.gexel.boxtop.gui.BagConfigGUI;
import fun.gexel.boxtop.gui.DuelGUI;
import fun.gexel.boxtop.listeners.*;
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
    private BanManager banManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.dataManager    = new DataManager(this);
        this.soundManager   = new SoundManager(this);
        this.rewardManager  = new RewardManager(this, dataManager, soundManager);
        this.glowManager    = new GlowManager(this, dataManager);
        this.staminaManager = new StaminaManager(this);
        this.duelManager    = new DuelManager(this, dataManager, glowManager, soundManager);
        this.duelGUI        = new DuelGUI(this, dataManager);
        this.configGUI      = new BagConfigGUI(this, dataManager, glowManager);
        this.spawnManager   = new BagSpawnManager(this, dataManager);
        this.banManager     = new BanManager(this, dataManager);


        // User-facing GUI (right-click on bag) — v3.1
        fun.gexel.boxtop.gui.UserGUI userGUI =
            new fun.gexel.boxtop.gui.UserGUI(this, dataManager, rewardManager);

        // Restaurar ArmorStands spawneados (con delay para esperar chunks)
        spawnManager.restoreOnStartup();

        ParticleTask particleTask = new ParticleTask(this, dataManager, glowManager);
        particleTask.runTaskTimer(this, 20L, 20L);

        getCommand("boxtop").setExecutor(
            new BoxTopCommand(dataManager, glowManager, duelManager, particleTask, configGUI, spawnManager, banManager)
        );
        getCommand("boxtop").setTabCompleter(new BoxTopTab(dataManager, banManager));

        // Build DuelListener first, then inject UserGUI to avoid circular dependency
        DuelListener duelListener = new DuelListener(this, dataManager, duelManager, duelGUI, configGUI);
        duelListener.setUserGUI(userGUI);

        getServer().getPluginManager().registerEvents(
            new HitListener(this, dataManager, rewardManager, staminaManager, soundManager, duelManager, banManager), this
        );
        getServer().getPluginManager().registerEvents(duelListener, this);
        getServer().getPluginManager().registerEvents(
            new UserGUIListener(userGUI, duelListener), this
        );
        getServer().getPluginManager().registerEvents(
            new BagConfigListener(this, dataManager, glowManager, configGUI, spawnManager), this
        );
        getServer().getPluginManager().registerEvents(
            new BanListener(this, banManager), this
        );
        getServer().getPluginManager().registerEvents(
            new WandListener(this, banManager), this
        );


        getServer().getPluginManager().registerEvents(
            new GlowListener(this, glowManager), this
        );
        getServer().getPluginManager().registerEvents(
            new LeashListener(this, dataManager), this
        );

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new BoxTopExpansion(this, dataManager).register();
        }

        glowManager.updateGlow();

        Metrics metrics = new Metrics(this, 29030);
        metrics.addCustomChart(new SimplePie("particles_enabled", () ->
            getConfig().getBoolean("particles.enabled") ? "Yes" : "No"
        ));

        // --- RATING PROMPT (Admin only, shown once) ---
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                org.bukkit.entity.Player p = event.getPlayer();
                if (p.hasPermission("boxtop.admin")) {
                    if (!getConfig().getBoolean("rating-prompt-shown", false)) {
                        Bukkit.getScheduler().runTaskLater(BoxTopPlugin.this, () -> {
                            if (!p.isOnline()) return;
                            p.sendMessage("§6========================================");
                            p.sendMessage("§eThank you for using §6§lBoxTop v" + getDescription().getVersion() + "§e!");
                            p.sendMessage("§fIf you enjoy the plugin, a 5-star review on Spigot means the world to us!");
                            p.sendMessage("§bPlease review it here: §nhttps://www.spigotmc.org/resources/130892");
                            p.sendMessage("§7(This message will only be shown once)");
                            p.sendMessage("§6========================================");
                            
                            getConfig().set("rating-prompt-shown", true);
                            saveConfig();
                        }, 100L); // 5 seconds after joining
                    }
                }
            }
        }, this);


        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (glowManager != null) glowManager.updateGlow();
        }, 100L, 100L);

        getLogger().info("BoxTop v3.3 enabled!");
    }


    @Override
    public void onDisable() {
        getLogger().info("BoxTop disabling...");
        if (banManager != null) banManager.shutdown();
        if (dataManager != null && glowManager != null) {
            for (java.util.UUID uuid : dataManager.getAllBagUUIDs()) {
                glowManager.removeGlow(uuid);
            }
        }
        if (dataManager != null) dataManager.shutdown();
        getLogger().info("BoxTop disabled.");
    }
}

