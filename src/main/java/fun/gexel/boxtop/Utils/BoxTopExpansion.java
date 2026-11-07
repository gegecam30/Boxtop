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

        // 3. PER-BAG RANKING — %boxtop_top<rank>_<type>_<bagname>%
        // Examples: %boxtop_top1_name_arena1%  %boxtop_top2_damage_arena_main%
        if (params.startsWith("top")) {
            String rest = params.substring(3); // e.g. "1_name_arena" or "2_damage_arena"
            String[] parts = rest.split("_", 3); // limit=3 preserves bag names with underscores
            if (parts.length < 3) return null;
            int rank;
            try { rank = Integer.parseInt(parts[0]); } catch (NumberFormatException e) { return null; }
            String type    = parts[1]; // "name" or "damage"
            String bagName = parts[2]; // e.g. "arena1" or "arena_main"
            if (rank < 1) return null;
            List<PlayerStat> bagTop = dataManager.getBagTop(bagName, rank + 1);
            if (rank > bagTop.size()) return type.equals("name") ? "---" : "0";
            PlayerStat ps = bagTop.get(rank - 1);
            return type.equals("name")
                ? ps.getPlayerName()
                : String.format("%.0f", ps.getDamage());
        }

        // 4. PER-BAG PERSONAL — %boxtop_personal_<type>_<bagname>%
        // Examples: %boxtop_personal_damage_arena1%  %boxtop_personal_rank_arena1%
        if (params.startsWith("personal_")) {
            String rest = params.substring(9); // e.g. "damage_arena" or "rank_arena" or "hits_arena"
            int idx = rest.indexOf("_");
            if (idx < 0 || player == null) return "0";
            String type    = rest.substring(0, idx); // "damage", "hits", "rank"
            String bagName = rest.substring(idx + 1); // bag name
            switch (type) {
                case "damage": {
                    PlayerStat ps = dataManager.getBagPlayerStat(bagName, player.getUniqueId());
                    return String.format("%.0f", ps.getDamage());
                }
                case "hits": {
                    PlayerStat ps = dataManager.getBagPlayerStat(bagName, player.getUniqueId());
                    return String.valueOf(ps.getHits());
                }
                case "rank": {
                    int r = dataManager.getPlayerBagRank(bagName, player.getUniqueId());
                    return r < 0 ? "---" : "#" + r;
                }
                default: return null;
            }
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