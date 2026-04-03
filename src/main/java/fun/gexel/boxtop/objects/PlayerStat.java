package fun.gexel.boxtop.objects;

import java.util.UUID;

public class PlayerStat implements Comparable<PlayerStat> {
    private final String playerName;
    private final UUID uuid;
    private double damage;

    public PlayerStat(String playerName, UUID uuid, double damage) {
        this.playerName = playerName;
        this.uuid = uuid;
        this.damage = damage;
    }

    public void addDamage(double amount) {
        this.damage += amount;
    }

    // --- GETTERS (Estos son necesarios para que DataManager pueda leer los datos) ---
    
    public double getDamage() { 
        return damage; 
    }
    
    public String getPlayerName() { 
        return playerName; 
    }
    
    // ESTE ES EL QUE FALTABA Y CAUSABA EL ERROR
    public UUID getUuid() { 
        return uuid; 
    }

    // --- Lógica de Ordenamiento (Mayor a menor) ---
    @Override
    public int compareTo(PlayerStat o) {
        // Ordena descendente (el que tiene más daño va primero)
        return Double.compare(o.damage, this.damage);
    }
}