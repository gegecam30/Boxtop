package fun.gexel.boxtop.managers.glow;

import fun.gexel.boxtop.managers.DataManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public interface GlowHandler {
    // Aplica el glow a un jugador específico (inyectando en su scoreboard)
    void apply(Player viewer, DataManager data, ChatColor color, boolean enabled);
    
    // Elimina el glow de una entidad específica para todos
    void remove(java.util.UUID entityUUID);
}