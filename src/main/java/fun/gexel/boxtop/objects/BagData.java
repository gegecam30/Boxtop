package fun.gexel.boxtop.objects;

import org.bukkit.ChatColor;

import java.util.UUID;

/**
 * Representa la configuración individual de un saco de boxeo.
 * Cada saco tiene su propio estado independiente.
 * Se serializa/deserializa desde data.yml.
 */
public class BagData {

    // --- IDENTIDAD ---
    private final String name;
    private final UUID uuid;

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
    // CONSTRUCTOR (Valores por defecto sensatos)
    // -------------------------------------------------------

    public BagData(String name, UUID uuid) {
        this.name = name;
        this.uuid = uuid;

        // Defaults — se sobreescriben al cargar desde YAML
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
        SAND("Sand",   1.0,  1.0,  false),   // Default: normal
        STONE("Stone", 0.2,  0.05, false),   // Heavy: casi no se mueve
        WOOL("Wool",   3.0,  0.4,  true);    // Soft: sale volando, daña al golpear rápido

        private final String displayName;
        /** Multiplicador aplicado al knockbackStrength del saco */
        private final double knockbackMultiplier;
        /** Multiplicador aplicado al verticalStrength del saco */
        private final double verticalMultiplier;
        /** Si true, golpear muy rápido inflige daño de retorno al jugador */
        private final boolean returnDamageEnabled;

        BagMaterial(String displayName,
                    double knockbackMultiplier,
                    double verticalMultiplier,
                    boolean returnDamageEnabled) {
            this.displayName          = displayName;
            this.knockbackMultiplier  = knockbackMultiplier;
            this.verticalMultiplier   = verticalMultiplier;
            this.returnDamageEnabled  = returnDamageEnabled;
        }

        public String getDisplayName()        { return displayName; }
        public double getKnockbackMultiplier(){ return knockbackMultiplier; }
        public double getVerticalMultiplier() { return verticalMultiplier; }
        public boolean isReturnDamageEnabled(){ return returnDamageEnabled; }

        /** Parsing seguro — nunca lanza excepción, fallback a SAND */
        public static BagMaterial fromString(String s) {
            if (s == null) return SAND;
            try {
                return BagMaterial.valueOf(s.toUpperCase());
            } catch (IllegalArgumentException e) {
                return SAND;
            }
        }
    }

    // -------------------------------------------------------
    // GETTERS
    // -------------------------------------------------------

    public String    getName()              { return name; }
    public UUID      getUuid()              { return uuid; }

    public boolean   isGlowEnabled()        { return glowEnabled; }
    public ChatColor getGlowColor()         { return glowColor; }

    public boolean   isParticlesEnabled()   { return particlesEnabled; }
    public String    getParticleType()      { return particleType; }

    public boolean   isPhysicsEnabled()     { return physicsEnabled; }
    public double    getKnockbackStrength() { return knockbackStrength; }
    public double    getVerticalStrength()  { return verticalStrength; }

    public BagMaterial getMaterial()        { return material; }

    // -------------------------------------------------------
    // SETTERS (cada uno modifica solo su saco, sin tocar otros)
    // -------------------------------------------------------

    public void setGlowEnabled(boolean v)        { this.glowEnabled = v; }
    public void setGlowColor(ChatColor v)        { this.glowColor = v; }

    public void setParticlesEnabled(boolean v)   { this.particlesEnabled = v; }
    public void setParticleType(String v)        { this.particleType = v; }

    public void setPhysicsEnabled(boolean v)     { this.physicsEnabled = v; }
    public void setKnockbackStrength(double v)   { this.knockbackStrength = v; }
    public void setVerticalStrength(double v)    { this.verticalStrength = v; }

    public void setMaterial(BagMaterial v)       { this.material = v; }

    // -------------------------------------------------------
    // FÍSICA EFECTIVA (knockback base × multiplicador del material)
    // -------------------------------------------------------

    /**
     * Knockback real que se aplicará al saco al ser golpeado.
     * Si las físicas están desactivadas en este saco, devuelve 0.
     */
    public double getEffectiveKnockback() {
        if (!physicsEnabled) return 0.0;
        return knockbackStrength * material.getKnockbackMultiplier();
    }

    /**
     * Fuerza vertical real que se aplicará al saco.
     * Si las físicas están desactivadas, devuelve 0.
     */
    public double getEffectiveVertical() {
        if (!physicsEnabled) return 0.0;
        return verticalStrength * material.getVerticalMultiplier();
    }

    // -------------------------------------------------------
    // SERIALIZACIÓN → YAML
    // -------------------------------------------------------

    /**
     * Escribe todos los campos de este saco en el FileConfiguration
     * bajo la clave "boxing-bags.<name>".
     */
    public void serialize(org.bukkit.configuration.file.FileConfiguration cfg) {
        String base = "boxing-bags." + name;
        cfg.set(base + ".uuid",               uuid.toString());
        cfg.set(base + ".glow-enabled",       glowEnabled);
        cfg.set(base + ".glow-color",         glowColor.name());
        cfg.set(base + ".particles-enabled",  particlesEnabled);
        cfg.set(base + ".particle-type",      particleType);
        cfg.set(base + ".physics-enabled",    physicsEnabled);
        cfg.set(base + ".knockback-strength", knockbackStrength);
        cfg.set(base + ".vertical-strength",  verticalStrength);
        cfg.set(base + ".material",           material.name());
    }

    // -------------------------------------------------------
    // DESERIALIZACIÓN ← YAML (static factory)
    // -------------------------------------------------------

    /**
     * Carga un BagData desde el FileConfiguration.
     * Compatible con el formato ANTIGUO (solo uuid como string)
     * para migración transparente.
     */
    public static BagData deserialize(String name,
                                      org.bukkit.configuration.file.FileConfiguration cfg) {
        String base = "boxing-bags." + name;

        // --- COMPATIBILIDAD HACIA ATRÁS ---
        // Formato antiguo: boxing-bags.saco1 = "uuid-string"
        // Formato nuevo:   boxing-bags.saco1.uuid = "uuid-string"
        UUID uuid;
        if (cfg.isString(base)) {
            // Formato legacy — solo había un UUID como valor directo
            uuid = UUID.fromString(cfg.getString(base));
        } else {
            uuid = UUID.fromString(cfg.getString(base + ".uuid", ""));
        }

        BagData bag = new BagData(name, uuid);

        // Si es formato legacy, todos los demás campos usan defaults — no hay nada que leer
        if (cfg.isString(base)) return bag;

        // Formato nuevo — cargamos cada campo con fallback al default del constructor
        bag.glowEnabled       = cfg.getBoolean(base + ".glow-enabled",       bag.glowEnabled);
        bag.glowColor         = parseChatColor(cfg.getString(base + ".glow-color"), bag.glowColor);
        bag.particlesEnabled  = cfg.getBoolean(base + ".particles-enabled",  bag.particlesEnabled);
        bag.particleType      = cfg.getString( base + ".particle-type",      bag.particleType);
        bag.physicsEnabled    = cfg.getBoolean(base + ".physics-enabled",    bag.physicsEnabled);
        bag.knockbackStrength = cfg.getDouble( base + ".knockback-strength", bag.knockbackStrength);
        bag.verticalStrength  = cfg.getDouble( base + ".vertical-strength",  bag.verticalStrength);
        bag.material          = BagMaterial.fromString(cfg.getString(base + ".material"));

        return bag;
    }

    private static ChatColor parseChatColor(String s, ChatColor fallback) {
        if (s == null) return fallback;
        try {
            return ChatColor.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
