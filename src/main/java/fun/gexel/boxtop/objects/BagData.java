package fun.gexel.boxtop.objects;

import org.bukkit.ChatColor;
import java.util.UUID;

/**
 * Representa la configuración individual de un saco de boxeo.
 *
 * NUEVO en v2.8:
 *  - Campo 'spawned' (boolean): indica si este saco fue creado por /boxtop spawn
 *    (ArmorStand gestionado por el plugin) o por /boxtop setentity (entidad externa).
 *  - Campo 'customModelData' (int): valor para el resource pack. 0 = sin modelo custom.
 *  - Ambos campos se serializan/deserializan con compatibilidad hacia atrás.
 */
public class BagData {

    // --- IDENTIDAD ---
    private final String name;
    private final UUID uuid;

    // --- TIPO DE SACO ---
    private boolean spawned;        // true = ArmorStand creado por el plugin
    private int customModelData;    // 0 = sin resource pack, >0 = modelo custom

    // --- GLOW ---
    private boolean glowEnabled;
    private ChatColor glowColor;

    // --- PARTÍCULAS ---
    private boolean particlesEnabled;
    private String particleType;

    // --- FÍSICAS ---
    private boolean physicsEnabled;
    private double knockbackStrength;
    private double verticalStrength;

    // --- MATERIAL DEL SACO ---
    private BagMaterial material;

    // -------------------------------------------------------
    // CONSTRUCTOR
    // -------------------------------------------------------

    public BagData(String name, UUID uuid) {
        this.name = name;
        this.uuid = uuid;

        this.spawned          = false;
        this.customModelData  = 0;
        this.glowEnabled      = true;
        this.glowColor        = ChatColor.GOLD;
        this.particlesEnabled = true;
        this.particleType     = "VILLAGER_HAPPY";
        this.physicsEnabled   = true;
        this.knockbackStrength = 0.4;
        this.verticalStrength  = 0.1;
        this.material         = BagMaterial.SAND;
    }

    // -------------------------------------------------------
    // ENUM DE MATERIALES
    // -------------------------------------------------------

    public enum BagMaterial {
        SAND ("Sand",  1.0,  1.0,  false),
        STONE("Stone", 0.2,  0.05, false),
        WOOL ("Wool",  3.0,  0.4,  true);

        private final String displayName;
        private final double knockbackMultiplier;
        private final double verticalMultiplier;
        private final boolean returnDamageEnabled;

        BagMaterial(String d, double kb, double vt, boolean rd) {
            this.displayName = d; this.knockbackMultiplier = kb;
            this.verticalMultiplier = vt; this.returnDamageEnabled = rd;
        }

        public String  getDisplayName()         { return displayName; }
        public double  getKnockbackMultiplier() { return knockbackMultiplier; }
        public double  getVerticalMultiplier()  { return verticalMultiplier; }
        public boolean isReturnDamageEnabled()  { return returnDamageEnabled; }

        public static BagMaterial fromString(String s) {
            if (s == null) return SAND;
            try { return BagMaterial.valueOf(s.toUpperCase()); }
            catch (IllegalArgumentException e) { return SAND; }
        }
    }

    // -------------------------------------------------------
    // GETTERS
    // -------------------------------------------------------

    public String      getName()              { return name; }
    public UUID        getUuid()              { return uuid; }
    public boolean     isSpawned()            { return spawned; }
    public int         getCustomModelData()   { return customModelData; }
    public boolean     isGlowEnabled()        { return glowEnabled; }
    public ChatColor   getGlowColor()         { return glowColor; }
    public boolean     isParticlesEnabled()   { return particlesEnabled; }
    public String      getParticleType()      { return particleType; }
    public boolean     isPhysicsEnabled()     { return physicsEnabled; }
    public double      getKnockbackStrength() { return knockbackStrength; }
    public double      getVerticalStrength()  { return verticalStrength; }
    public BagMaterial getMaterial()          { return material; }

    // -------------------------------------------------------
    // SETTERS
    // -------------------------------------------------------

    public void setSpawned(boolean v)            { this.spawned = v; }
    public void setCustomModelData(int v)        { this.customModelData = v; }
    public void setGlowEnabled(boolean v)        { this.glowEnabled = v; }
    public void setGlowColor(ChatColor v)        { this.glowColor = v; }
    public void setParticlesEnabled(boolean v)   { this.particlesEnabled = v; }
    public void setParticleType(String v)        { this.particleType = v; }
    public void setPhysicsEnabled(boolean v)     { this.physicsEnabled = v; }
    public void setKnockbackStrength(double v)   { this.knockbackStrength = v; }
    public void setVerticalStrength(double v)    { this.verticalStrength = v; }
    public void setMaterial(BagMaterial v)       { this.material = v; }

    // -------------------------------------------------------
    // FÍSICA EFECTIVA
    // -------------------------------------------------------

    public double getEffectiveKnockback() {
        if (!physicsEnabled) return 0.0;
        return knockbackStrength * material.getKnockbackMultiplier();
    }

    public double getEffectiveVertical() {
        if (!physicsEnabled) return 0.0;
        return verticalStrength * material.getVerticalMultiplier();
    }

    // -------------------------------------------------------
    // SERIALIZACIÓN → YAML
    // -------------------------------------------------------

    public void serialize(org.bukkit.configuration.file.FileConfiguration cfg) {
        String b = "boxing-bags." + name;
        cfg.set(b + ".uuid",               uuid.toString());
        cfg.set(b + ".spawned",            spawned);
        cfg.set(b + ".custom-model-data",  customModelData);
        cfg.set(b + ".glow-enabled",       glowEnabled);
        cfg.set(b + ".glow-color",         glowColor.name());
        cfg.set(b + ".particles-enabled",  particlesEnabled);
        cfg.set(b + ".particle-type",      particleType);
        cfg.set(b + ".physics-enabled",    physicsEnabled);
        cfg.set(b + ".knockback-strength", knockbackStrength);
        cfg.set(b + ".vertical-strength",  verticalStrength);
        cfg.set(b + ".material",           material.name());
    }

    // -------------------------------------------------------
    // DESERIALIZACIÓN ← YAML
    // -------------------------------------------------------

    public static BagData deserialize(String name,
                                      org.bukkit.configuration.file.FileConfiguration cfg) {
        String b = "boxing-bags." + name;

        UUID uuid;
        if (cfg.isString(b)) {
            // Formato legacy (v2.6 y anterior)
            uuid = UUID.fromString(cfg.getString(b));
            return new BagData(name, uuid); // defaults para todo
        }

        uuid = UUID.fromString(cfg.getString(b + ".uuid", ""));
        BagData bag = new BagData(name, uuid);

        bag.spawned          = cfg.getBoolean(b + ".spawned",            false);
        bag.customModelData  = cfg.getInt(    b + ".custom-model-data",  0);
        bag.glowEnabled      = cfg.getBoolean(b + ".glow-enabled",       bag.glowEnabled);
        bag.glowColor        = parseChatColor(cfg.getString(b + ".glow-color"), bag.glowColor);
        bag.particlesEnabled = cfg.getBoolean(b + ".particles-enabled",  bag.particlesEnabled);
        bag.particleType     = cfg.getString( b + ".particle-type",      bag.particleType);
        bag.physicsEnabled   = cfg.getBoolean(b + ".physics-enabled",    bag.physicsEnabled);
        bag.knockbackStrength= cfg.getDouble( b + ".knockback-strength", bag.knockbackStrength);
        bag.verticalStrength = cfg.getDouble( b + ".vertical-strength",  bag.verticalStrength);
        bag.material         = BagMaterial.fromString(cfg.getString(b + ".material"));

        return bag;
    }

    private static ChatColor parseChatColor(String s, ChatColor fallback) {
        if (s == null) return fallback;
        try { return ChatColor.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return fallback; }
    }
}
