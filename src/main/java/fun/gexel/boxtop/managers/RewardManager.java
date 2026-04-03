package fun.gexel.boxtop.managers;

import fun.gexel.boxtop.BoxTopPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.List;

public class RewardManager {

    private final BoxTopPlugin plugin;

    public RewardManager(BoxTopPlugin plugin) {
        this.plugin = plugin;
    }

    public void checkReward(Player player, double oldDamage, double newDamage) {
        if (!plugin.getConfig().getBoolean("rewards.enabled")) return;

        double interval = plugin.getConfig().getDouble("rewards.interval", 500.0);
        if (interval <= 0) return;

        // MATEMÁTICA PURA:
        // Calculamos cuántas veces completó el intervalo antes y después del golpe.
        // Ejemplo: Intervalo 500. 
        // Antes: 490 daño (0 veces) -> Ahora: 510 daño (1 vez) -> ¡CAMBIO! -> PREMIO
        int oldMilestones = (int) (oldDamage / interval);
        int newMilestones = (int) (newDamage / interval);

        if (newMilestones > oldMilestones) {
            giveReward(player);
        }
    }

    private void giveReward(Player player) {
        List<String> commands = plugin.getConfig().getStringList("rewards.commands");
        
        for (String cmd : commands) {
            // Reemplazamos %player% y ejecutamos desde la consola para evitar permisos
            String finalCmd = cmd.replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
        }
    }
}