package fun.gexel.boxtop;

import fun.gexel.boxtop.managers.DataManager;
import fun.gexel.boxtop.objects.PlayerStat;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class BoxTopExpansion extends PlaceholderExpansion {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;

    public BoxTopExpansion(BoxTopPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "boxtop";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Gexel";
    }

    @Override
    public @NotNull String getVersion() {
        return "2.6";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        // 1. TOP 10 GLOBAL
        // %boxtop_name_1% / %boxtop_damage_1%
        List<PlayerStat> top = dataManager.getTop(10);

        if (params.startsWith("name_")) {
            int position = getPositionFromParams(params);
            if (position < 1 || position > top.size()) return "---";
            return top.get(position - 1).getPlayerName();
        }

        if (params.startsWith("damage_")) {
            int position = getPositionFromParams(params);
            if (position < 1 || position > top.size()) return "0";
            return String.format("%.0f", top.get(position - 1).getDamage());
        }
        
        // 2. ESTADÍSTICA PERSONAL (%boxtop_personal%)
        // Muestra tu daño acumulado
        if (params.equals("personal")) {
            if (player == null) return "0";
            // Obtenemos el stat (si no existe, devuelve uno vacío con 0 daño)
            PlayerStat stat = dataManager.getPlayerStat(player.getUniqueId());
            return String.format("%.0f", stat.getDamage());
        }

        return null;
    }

    private int getPositionFromParams(String params) {
        try {
            String[] parts = params.split("_");
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}