package fun.gexel.boxtop.managers;

import com.cryptomorin.xseries.XMaterial;
import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.objects.BagData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Method;

/**
 * BagSpawnManager — gestiona el ciclo de vida de los sacos spawneados con /boxtop spawn.
 *
 * Entidad usada: VILLAGER invisible, con IA activa pero SLOWNESS-255 permanente.
 *
 * ¿Por qué Villager en lugar de Zombie?
 *   - El Villager es naturalmente leashable en TODAS las versiones (1.12+),
 *     lo que permite atar la rienda a vallas con la mecánica vanilla.
 *   - No se quema al sol (el Zombie sí).
 *   - Es un mob pasivo: no genera comportamientos agresivos.
 *
 * ¿Por qué AI=true con SLOWNESS-255 en lugar de AI=false?
 *   AI=false desactiva el motor de físicas → setVelocity() no produce ningún movimiento.
 *   Con AI=true + SLOWNESS-255 el villager no puede moverse por sí mismo pero el
 *   motor de físicas sigue vivo → setVelocity() al golpear funciona perfectamente.
 *
 * CustomModelData — detección de versión:
 *   Se usa el mismo patrón que GlowManager.detectLegacy() para saber si estamos
 *   en 1.12/1.13 (sin soporte CMD) o en 1.14+ (con soporte CMD).
 *   En versiones legacy se aplica el ítem configurado en spawned-bag.legacy-item
 *   con su damage value para sistemas de resource pack pre-1.14.
 */
public class BagSpawnManager {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;

    private static final String STAND_DISPLAY_NAME = "§6Boxing Bag";

    /** Versión minor detectada en startup (ej: 12 para 1.12, 21 para 1.21) */
    private final int minorVersion;

    /** true = 1.12 / 1.13 → sin CustomModelData, usar damage-value si se configura */
    private final boolean isLegacyVersion;

    public BagSpawnManager(BoxTopPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.minorVersion = detectMinorVersion();
        this.isLegacyVersion = (minorVersion < 14);
        plugin.getLogger().info("[BoxTop] Server version 1." + minorVersion
            + " — CustomModelData: " + (isLegacyVersion ? "DISABLED (< 1.14)" : "ENABLED"));
    }

    // -------------------------------------------------------
    // RESTAURACIÓN AL INICIAR
    // -------------------------------------------------------

    public void restoreOnStartup() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int restored = 0, missing = 0;
            for (BagData bag : new java.util.ArrayList<>(dataManager.getAllBags())) {
                if (!bag.isSpawned()) continue;
                Entity entity = Bukkit.getEntity(bag.getUuid());
                if (entity instanceof LivingEntity && entity.isValid()) {
                    configureMob((LivingEntity) entity);
                    applyHeadItem((LivingEntity) entity, bag.getCustomModelData());
                    applyInvisibility((LivingEntity) entity, bag.isInvisible());
                    restored++;
                } else {
                    plugin.getLogger().warning("[BoxTop] Bag '" + bag.getName() + "' not found, re-spawning...");
                    LivingEntity newMob = respawnMob(bag);
                    if (newMob != null) {
                        dataManager.removeBoxingBag(bag.getName());
                        BagData newBag = dataManager.addBoxingBag(bag.getName(), newMob.getUniqueId());
                        copyConfig(bag, newBag);
                        dataManager.saveBag(newBag);
                        missing++;
                    }
                }
            }
            if (restored > 0 || missing > 0)
                plugin.getLogger().info("[BoxTop] Bags restored: " + restored + ", re-spawned: " + missing);
        }, 20L);
    }

    // -------------------------------------------------------
    // SPAWN
    // -------------------------------------------------------

    public BagData spawnBag(String name, Location location) {
        if (dataManager.getBagUUID(name) != null) return null;

        Villager villager = location.getWorld().spawn(location, Villager.class);
        configureMob(villager);

        BagData bag = dataManager.addBoxingBag(name, villager.getUniqueId());
        bag.setSpawned(true);

        int cmdValue = plugin.getConfig().getInt("spawned-bag.custom-model-data", 0);
        bag.setCustomModelData(cmdValue);

        applyHeadItem(villager, cmdValue);
        applyInvisibility(villager, bag.isInvisible()); // apply per-bag invisibility setting
        dataManager.saveBag(bag);

        // Auto-generate rewards.yml section for this bag
        dataManager.getRewardsFileManager().createDefaultBagSection(name);

        return bag;
    }

    public void removeBag(BagData bag) {
        if (!bag.isSpawned()) return;
        Entity entity = Bukkit.getEntity(bag.getUuid());
        if (entity != null) entity.remove();
    }

    public void refreshItem(BagData bag) {
        if (!bag.isSpawned()) return;
        Entity entity = Bukkit.getEntity(bag.getUuid());
        if (entity instanceof LivingEntity) applyHeadItem((LivingEntity) entity, bag.getCustomModelData());
    }

    // -------------------------------------------------------
    // CONFIGURACIÓN DEL MOB
    // -------------------------------------------------------

    /**
     * Configura el Villager para actuar como saco de boxeo:
     *
     *  AI=true (IMPORTANTE): mantiene el motor de físicas activo para que
     *  setVelocity() funcione cuando se golpea el saco. setAI(false) desactivaría
     *  también las físicas y el saco se quedaría completamente estático.
     *
     *  SLOW amplificador 255 permanente: anula completamente el movimiento
     *  autónomo del villager (sus pathfinder goals no producen desplazamiento porque
     *  la velocidad resultante es 0 o negativa), pero no afecta las físicas externas.
     *  El villager se queda en su lugar y solo se mueve cuando lo golpean.
     *
     *  INVISIBILITY permanente: el jugador solo ve el ítem del helmet (textura del
     *  resource pack). El villager en sí es invisible.
     */
    private void configureMob(LivingEntity mob) {
        mob.setCustomName(STAND_DISPLAY_NAME);
        mob.setCustomNameVisible(true);
        mob.setSilent(true);
        mob.setFireTicks(0);
        mob.setRemoveWhenFarAway(false);

        // SLOW-255 (conocido como SLOWNESS en 1.18+): inmoviliza el movimiento autónomo de la IA.
        // A diferencia de setAI(false), NO desactiva las físicas → setVelocity funciona.
        // Nombre en la API compilada (1.16.5): SLOW. Mismo efecto en todas las versiones.
        mob.addPotionEffect(new PotionEffect(
            PotionEffectType.SLOW,
            Integer.MAX_VALUE,   // prácticamente infinito
            255,                 // amplificador máximo — velocidad de movimiento = 0
            false,               // sin partícula de baliza
            false                // sin partícula de efecto visible
        ));

        // Invisibilidad: ahora controlada por BagData.isInvisible()
        // No se aplica aquí — se aplica explícitamente con applyInvisibility() después

        // Máxima salud para que no muera.
        // En Paper 1.21.11 el límite máximo bajó de 2048 a 1024 — si intentamos
        // asignar un valor mayor el servidor lanza IllegalArgumentException.
        // Usamos 1024.0 como tope seguro universal (1.12 → 1.21.11).
        double targetHealth = 1024.0;
        try {
            org.bukkit.attribute.AttributeInstance maxHpAttr =
                mob.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (maxHpAttr != null) {
                maxHpAttr.setBaseValue(targetHealth);
            }
        } catch (Throwable e) {
            try {
                @SuppressWarnings("deprecation")
                double fallback = 1024.0;
                mob.setMaxHealth(fallback);
            } catch (Exception ignored) {}
        }
        mob.setHealth(targetHealth);

        // setPersistent: 1.14+ — evita que el mob despawnee al alejarse
        try {
            Method m = mob.getClass().getMethod("setPersistent", boolean.class);
            m.invoke(mob, true);
        } catch (Exception ignored) {}

        // Villager específico: profesión NONE para silenciarlo.
        // Villager.Profession.NONE se añadió en 1.14 — en 1.12 no existe.
        // Usamos valueOf() con try/catch para evitar NoSuchFieldError en 1.12.
        if (mob instanceof Villager) {
            Villager v = (Villager) mob;
            try {
                Villager.Profession none = Villager.Profession.valueOf("NONE");
                v.setProfession(none);
            } catch (IllegalArgumentException e) {
                // 1.12: NONE no existe. Intentamos FARMER (sigue invisible, silenciado).
                try { v.setProfession(Villager.Profession.valueOf("FARMER")); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------
    // ÍTEM EN LA CABEZA — compatible 1.12 a 1.21
    // -------------------------------------------------------

    /**
     * Aplica el ítem al helmet del mob según la versión del servidor.
     *
     * 1.14+ (CustomModelData disponible):
     *   Usa PAPER con CustomModelData del config → resource pack muestra el modelo 3D.
     *   Triple-fallback para distintas firmas del método según la build exacta.
     *
     * 1.12 / 1.13 (sin CustomModelData):
     *   El sistema de texturas custom pre-1.14 usaba el DAMAGE VALUE del ítem.
     *   Si el config tiene spawned-bag.legacy-material y spawned-bag.legacy-damage,
     *   los usa. Si no, cae a PAPER normal (el zombie tendrá papel en la cabeza).
     *   Nota: el zombie invisible con papel no se ve — el saco seguirá siendo invisible
     *   en 1.12 a menos que el server tenga un resource pack configurado para damage values.
     */
    private void applyHeadItem(LivingEntity mob, int customModelData) {
        ItemStack item;

        if (isLegacyVersion) {
            // 1.12 / 1.13: intentar usar el ítem legacy con damage value
            item = buildLegacyItem();
        } else {
            // 1.14+: PAPER con CustomModelData
            item = buildModernItem(customModelData);
        }

        if (mob.getEquipment() != null) {
            mob.getEquipment().setHelmet(item);
            mob.getEquipment().setHelmetDropChance(0.0f);
        }
    }

    /**
     * Construye el ítem para versiones 1.12/1.13 usando material + damage value.
     * Configurable en config.yml bajo spawned-bag.legacy-material y legacy-damage.
     *
     * MATEMÁTICA DEL SISTEMA PRE-1.14:
     *   En 1.12, el motor elige el modelo por la fracción de daño: damage / maxDurability.
     *   Para DIAMOND_HOE (1562 usos): damage=1 → 1/1562 = 0.0006406
     *   El predicado del JSON debe ser exactamente: "damage": 0.0006406
     *   Si legacy-damage=0 → el ítem está nuevo → predicado 0.0 → no coincide → sin textura.
     *
     * COMPATIBILIDAD CON CONFIGS ANTIGUAS:
     *   El fallback en el código es DIAMOND_HOE / damage=1, cubriendo usuarios que
     *   no actualicen su config.yml (sin las keys legacy-material / legacy-damage).
     */
    @SuppressWarnings("deprecation")
    private ItemStack buildLegacyItem() {
        // Defaults robustos: DIAMOND_HOE + damage 1 (sistema de texturas pre-1.14).
        // Coincide con el predicado 0.0006406 del JSON de resource pack.
        String matName = plugin.getConfig().getString("spawned-bag.legacy-material", "DIAMOND_HOE");
        int damage     = plugin.getConfig().getInt("spawned-bag.legacy-damage", 1);

        ItemStack item = null;

        // Intentar el material configurado via XMaterial (traduce nombres modernos a legacy)
        try {
            XMaterial xmat = XMaterial.valueOf(matName.toUpperCase());
            item = xmat.parseItem();
        } catch (Exception ignored) {}

        // Fallback a DIAMOND_HOE si el material configurado no existe
        if (item == null) {
            try { item = XMaterial.DIAMOND_HOE.parseItem(); } catch (Exception ignored) {}
        }

        // Último fallback: PAPER (sin textura, pero visible)
        if (item == null) {
            item = XMaterial.PAPER.parseItem();
            if (item == null) item = new ItemStack(org.bukkit.Material.PAPER);
        }

        // setDurability() es @Deprecated desde 1.13, pero esta rama SOLO corre en
        // 1.12/1.13 donde el método existe y es la única forma de asignar damage value.
        // @SuppressWarnings en el método silencia el warning del compilador.
        if (damage > 0) {
            item.setDurability((short) damage);
        }

        return item;
    }

    /**
     * Construye el ítem para versiones 1.14+ con CustomModelData.
     * Triple-fallback para distintas builds/forks del servidor.
     */
    private ItemStack buildModernItem(int customModelData) {
        ItemStack item = XMaterial.PAPER.parseItem();
        if (item == null) item = new ItemStack(org.bukkit.Material.PAPER);

        if (customModelData > 0) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                boolean applied = trySetCustomModelData(meta, customModelData);
                if (applied) {
                    plugin.getLogger().fine("[BoxTop] CustomModelData " + customModelData + " applied.");
                } else {
                    plugin.getLogger().warning("[BoxTop] CustomModelData failed despite 1.14+ version detection. Check server build.");
                }
                meta.setDisplayName(STAND_DISPLAY_NAME);
                item.setItemMeta(meta);
            }
        }

        return item;
    }

    /**
     * Triple-fallback para setCustomModelData.
     * CRÍTICO: usar meta.getClass() (instancia concreta), NO ItemMeta.class (interfaz).
     * En 1.21 Paper el método está en la implementación, no en la interfaz pública.
     */
    private boolean trySetCustomModelData(ItemMeta meta, int value) {
        // Intento 1: llamada directa (compilada contra 1.16.5)
        // En runtime 1.12/1.13 lanza NoSuchMethodError — pero ya filtramos por versión antes.
        try {
            meta.setCustomModelData(value);
            return true;
        } catch (NoSuchMethodError ignored) {
        } catch (Exception ignored) {}

        // Intento 2: reflexión con Integer wrapper — 1.14 a 1.20
        try {
            Method m = meta.getClass().getMethod("setCustomModelData", Integer.class);
            m.invoke(meta, value);
            return true;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            plugin.getLogger().warning("[BoxTop] Reflection CMD (Integer) failed: " + e.getMessage());
        }

        // Intento 3: reflexión con int primitivo — algunos builds 1.21+
        try {
            Method m = meta.getClass().getMethod("setCustomModelData", int.class);
            m.invoke(meta, value);
            return true;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            plugin.getLogger().warning("[BoxTop] Reflection CMD (int) failed: " + e.getMessage());
        }

        return false;
    }

    // -------------------------------------------------------
    // RE-SPAWN
    // -------------------------------------------------------

    private LivingEntity respawnMob(BagData bag) {
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world == null) return null;
        Villager villager = world.spawn(world.getSpawnLocation(), Villager.class);
        configureMob(villager);
        applyHeadItem(villager, bag.getCustomModelData());
        return villager;
    }

    private void copyConfig(BagData from, BagData to) {
        to.setSpawned(true);
        to.setCustomModelData(from.getCustomModelData());
        to.setGlowEnabled(from.isGlowEnabled());
        to.setGlowColor(from.getGlowColor());
        to.setParticlesEnabled(from.isParticlesEnabled());
        to.setParticleType(from.getParticleType());
        to.setPhysicsEnabled(from.isPhysicsEnabled());
        to.setKnockbackStrength(from.getKnockbackStrength());
        to.setVerticalStrength(from.getVerticalStrength());
        to.setMaterial(from.getMaterial());
        to.setInvisible(from.isInvisible());
    }

    // -------------------------------------------------------
    // INVISIBILIDAD (controlada por BagData)
    // -------------------------------------------------------

    /**
     * Applies or removes the permanent INVISIBILITY potion effect on a mob.
     * Called after configureMob() so the setting from data.yml takes effect.
     * Also called by BagConfigGUI toggles for live updates without restart.
     */
    public void applyInvisibility(LivingEntity mob, boolean invisible) {
        mob.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (invisible) {
            mob.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                Integer.MAX_VALUE,
                0,
                false,   // no beacon particle
                false    // no visible effect particle
            ));
        }
    }

    // -------------------------------------------------------
    // DETECCIÓN DE VERSIÓN — igual que GlowManager
    // -------------------------------------------------------

    /**
     * Extrae el número de versión minor del servidor.
     * Ej: "1.21.1-R0.1-SNAPSHOT" → 21
     *      "1.12.2-R0.1-SNAPSHOT" → 12
     */
    private int detectMinorVersion() {
        String raw = Bukkit.getBukkitVersion();
        try {
            String clean = raw.split("-")[0];      // "1.21.1"
            String[] parts = clean.split("\\.");   // ["1","21","1"]
            if (parts.length >= 2) {
                return Integer.parseInt(parts[1]); // 21
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[BoxTop] Could not detect server version from: " + raw);
        }
        return 12; // fallback seguro (asume legacy)
    }
}
