package fun.gexel.boxtop.managers.glow;

import fun.gexel.boxtop.managers.DataManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.UUID;

public class GlowLegacy implements GlowHandler {

    private static final String TEAM_NAME = "BoxTopGlow";

    @Override
    public void apply(Player viewer, DataManager data, ChatColor color, boolean enabled) {
        // 1. Siempre inyectamos en la MainScoreboard (Global)
        inject(Bukkit.getScoreboardManager().getMainScoreboard(), data, color, enabled);
        
        // 2. FIX: Solo intentamos inyectar en la scoreboard del jugador SI EXISTE.
        // Esto evita el NullPointerException cuando lo llama el ParticleTask (que pasa null).
        if (viewer != null) {
            inject(viewer.getScoreboard(), data, color, enabled);
        }
    }

    private void inject(Scoreboard sb, DataManager data, ChatColor color, boolean enabled) {
        if (sb == null) return;
        Team team = sb.getTeam(TEAM_NAME);
        if (team == null) team = sb.registerNewTeam(TEAM_NAME);

        // API LEGACY: .setPrefix
        String prefix = color.toString();
        try {
            if (!team.getPrefix().equals(prefix)) team.setPrefix(prefix);
            if (team.getSuffix() != null && !team.getSuffix().isEmpty()) team.setSuffix("");
        } catch (Exception ignored) {}

        for (UUID uuid : data.getAllBagUUIDs()) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && entity.isValid()) {
                String entry = getEntryName(entity); // Nombre para jugadores, UUID para mobs
                if (enabled) {
                    if (!entity.isGlowing()) entity.setGlowing(true);
                    if (!team.hasEntry(entry)) team.addEntry(entry);
                } else {
                    if (entity.isGlowing()) entity.setGlowing(false);
                    if (team.hasEntry(entry)) team.removeEntry(entry);
                }
            }
        }
    }

    @Override
    public void remove(UUID uuid) {
        Entity entity = Bukkit.getEntity(uuid);
        if (entity != null) entity.setGlowing(false);
        String entry = (entity != null) ? getEntryName(entity) : uuid.toString();

        try {
            Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(TEAM_NAME);
            if (t != null) t.removeEntry(entry);
        } catch (Exception ignored) {}
    }

    private String getEntryName(Entity entity) {
        if (entity instanceof Player) return entity.getName();
        return entity.getUniqueId().toString();
    }
}