package fun.gexel.boxtop.objects;

import java.util.UUID;

public class PlayerStat implements Comparable<PlayerStat> {

    private final String playerName;
    private final UUID   uuid;
    private double damage;
    private int    hits;
    private double bestHit;

    // Legacy constructor — keeps global stats working unchanged
    public PlayerStat(String playerName, UUID uuid, double damage) {
        this(playerName, uuid, damage, 0, 0.0);
    }

    // Full constructor for per-bag stats loaded from H2
    public PlayerStat(String playerName, UUID uuid, double damage, int hits, double bestHit) {
        this.playerName = playerName;
        this.uuid       = uuid;
        this.damage     = damage;
        this.hits       = hits;
        this.bestHit    = bestHit;
    }

    // Global damage only (used by existing addDamage path)
    public void addDamage(double amount) {
        this.damage += amount;
    }

    // Per-bag version: tracks damage + hit count + best hit in one call
    public void addDamageAndHit(double amount) {
        this.damage += amount;
        this.hits++;
        if (amount > this.bestHit) this.bestHit = amount;
    }

    // --- GETTERS ---

    public double getDamage()     { return damage; }
    public String getPlayerName() { return playerName; }
    public UUID   getUuid()       { return uuid; }
    public int    getHits()       { return hits; }
    public double getBestHit()    { return bestHit; }

    // --- Sort descending by damage ---
    @Override
    public int compareTo(PlayerStat o) {
        return Double.compare(o.damage, this.damage);
    }
}