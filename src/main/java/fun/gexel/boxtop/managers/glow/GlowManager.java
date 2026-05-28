package fun.gexel.boxtop.managers;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.glow.GlowHandler;
import fun.gexel.boxtop.managers.glow.GlowLegacy;
import fun.gexel.boxtop.managers.glow.GlowModern;
import fun.gexel.boxtop.objects.BagData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.UUID;
import java.util.logging.Level;

/**
 * GlowManager refactorizado para respetar la configuración individual de cada BagData.
 *
 * Problema anterior: updateGlow() leía un solo color/enabled del config.yml global
 * y lo aplicaba a TODOS los sacos con un único Team de scoreboard.
 *
 * Solución: un Team de scoreboard por color activo. Cada saco se asigna al Team
 * correspondiente a su color, o se remueve si tiene glow desactivado.
 * Prefijo de team: "BT_" + ChatColor.name() — ej: "BT_GOLD", "BT_RED"
 */
public class GlowManager {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private final boolean isLegacy;

    // Prefijo de los teams gestionados por este plugin
    private static final String TEAM_PREFIX = "BT_";

    public GlowManager(BoxTopPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.isLegacy = detectLegacy();
    }

    // -------------------------------------------------------
    // API PÚBLICA
    // -------------------------------------------------------

    /**
     * Aplica el glow a todos los sacos según su BagData individual.
     * Llamado periódicamente y al cambiar configuración.
     */
    public void updateGlow() {
        for (BagData bag : dataManager.getAllBags()) {
            applyBag(bag, null);
        }
    }

    /**
     * Aplica/refresca el glow para un jugador específico que acaba de unirse
     * o cambiar de mundo (necesita que su scoreboard reciba los teams).
     */
    public void updateGlowForPlayer(Player player) {
        for (BagData bag : dataManager.getAllBags()) {
            applyBag(bag, player);
        }
    }

    /**
     * Elimina el glow de un saco específico (al borrarlo).
     */
    public void removeGlow(UUID uuid) {
        Entity entity = Bukkit.getEntity(uuid);
        if (entity != null) entity.setGlowing(false);

        // Quitar de todos los teams por si estaba en alguno
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        String entry = (entity != null) ? getEntryName(entity) : uuid.toString();

        for (Team team : main.getTeams()) {
            if (team.getName().startsWith(TEAM_PREFIX)) {
                if (team.hasEntry(entry)) team.removeEntry(entry);
            }
        }
    }

    // -------------------------------------------------------
    // LÓGICA INTERNA POR SACO
    // -------------------------------------------------------

    private void applyBag(BagData bag, Player viewer) {
        Entity entity = Bukkit.getEntity(bag.getUuid());
        if (entity == null || !entity.isValid()) return;

        if (!bag.isGlowEnabled()) {
            // Glow desactivado para este saco: apagar y quitar de todos los teams
            if (entity.isGlowing()) entity.setGlowing(false);
            removeEntryFromAllTeams(getEntryName(entity));
            return;
        }

        // Glow activado: asignar al team correcto según su color
        entity.setGlowing(true);
        String teamName = TEAM_PREFIX + bag.getGlowColor().name(); // ej: "BT_GOLD"
        String entry = getEntryName(entity);

        // Aplicar en MainScoreboard (visible para todos)
        applyToScoreboard(
            Bukkit.getScoreboardManager().getMainScoreboard(),
            teamName, bag.getGlowColor(), entry
        );

        // Aplicar también en la scoreboard personal del jugador si se especificó
        if (viewer != null && isLegacy) {
            Scoreboard ps = viewer.getScoreboard();
            if (ps != null && ps != Bukkit.getScoreboardManager().getMainScoreboard()) {
                applyToScoreboard(ps, teamName, bag.getGlowColor(), entry);
            }
        }

        // Si el saco estaba en un team de color diferente, quitarlo de ahí
        removeEntryFromOtherTeams(
            Bukkit.getScoreboardManager().getMainScoreboard(),
            teamName, entry
        );
    }

    private void applyToScoreboard(Scoreboard sb, String teamName, ChatColor color, String entry) {
        if (sb == null) return;
        Team team = sb.getTeam(teamName);
        if (team == null) {
            team = sb.registerNewTeam(teamName);
        }

        // Asignar color (API moderna) o prefix (API legacy)
        try {
            if (isLegacy) {
                String prefix = color.toString();
                if (!team.getPrefix().equals(prefix)) team.setPrefix(prefix);
            } else {
                if (team.getColor() != color) team.setColor(color);
            }
        } catch (Exception ignored) {}

        if (!team.hasEntry(entry)) team.addEntry(entry);
    }

    /**
     * Si el entry estaba en un team de OTRO color, lo removemos
     * para que no aparezca duplicado con color incorrecto.
     */
    private void removeEntryFromOtherTeams(Scoreboard sb, String correctTeamName, String entry) {
        for (Team team : sb.getTeams()) {
            if (!team.getName().startsWith(TEAM_PREFIX)) continue;
            if (team.getName().equals(correctTeamName)) continue;
            if (team.hasEntry(entry)) team.removeEntry(entry);
        }
    }

    private void removeEntryFromAllTeams(String entry) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team team : main.getTeams()) {
            if (team.getName().startsWith(TEAM_PREFIX) && team.hasEntry(entry)) {
                team.removeEntry(entry);
            }
        }
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    private String getEntryName(Entity entity) {
        if (entity instanceof Player) return entity.getName();
        return entity.getUniqueId().toString();
    }

    private boolean detectLegacy() {
        String versionRaw = Bukkit.getBukkitVersion();
        try {
            String clean = versionRaw.split("-")[0];
            String[] parts = clean.split("\\.");
            if (parts.length >= 2) {
                return Integer.parseInt(parts[1]) < 13;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error detecting version: " + versionRaw, e);
        }
        return true; // fallback seguro
    }
}
