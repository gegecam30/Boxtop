package fun.gexel.boxtop.managers.glow;

import fun.gexel.boxtop.managers.DataManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.UUID;

public class GlowModern implements GlowHandler {

    private static final String TEAM_NAME = "BoxTopGlow";

    @Override
    public void apply(Player viewer, DataManager data, ChatColor color, boolean enabled) {
        // EN 1.21 (v2.3 Logic): Usamos SOLO la MainScoreboard.
        // Si funcionaba antes, funcionará ahora.
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        
        Team team = sb.getTeam(TEAM_NAME);
        if (team == null) {
            team = sb.registerNewTeam(TEAM_NAME);
        }

        // API MODERNA (1.13+): .setColor
        try {
            if (team.getColor() != color) {
                team.setColor(color);
            }
        } catch (Exception ignored) {}

        injectEntities(team, data, enabled);
    }

    @Override
    public void remove(UUID uuid) {
        Entity entity = Bukkit.getEntity(uuid);
        if (entity != null) entity.setGlowing(false);
        
        String entry = uuid.toString(); // En tu v2.3 usabas UUID.toString()
        
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            Team t = sb.getTeam(TEAM_NAME);
            if (t != null) t.removeEntry(entry);
        } catch (Exception ignored) {}
    }

    private void injectEntities(Team team, DataManager data, boolean enabled) {
        for (UUID uuid : data.getAllBagUUIDs()) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && entity.isValid()) {
                if (enabled) {
                    if (!entity.isGlowing()) entity.setGlowing(true);
                    
                    // Lógica v2.3: Usar siempre UUID.toString()
                    String entry = entity.getUniqueId().toString();
                    if (!team.hasEntry(entry)) team.addEntry(entry);
                } else {
                    if (entity.isGlowing()) entity.setGlowing(false);
                    
                    String entry = entity.getUniqueId().toString();
                    if (team.hasEntry(entry)) team.removeEntry(entry);
                }
            }
        }
    }
}