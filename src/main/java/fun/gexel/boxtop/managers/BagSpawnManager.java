package fun.gexel.boxtop.managers;

import com.cryptomorin.xseries.XMaterial;
import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.objects.BagData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * BagSpawnManager — gestiona ArmorStands creados por /boxtop spawn.
 *
 * COMPATIBILIDAD:
 *  1.12 → ArmorStand, setVisible, setGravity, setArms, setCustomName: OK
 *  1.13 → CustomModelData NO disponible → muestra PAPER normal
 *  1.14+ → CustomModelData disponible → resource pack puede override el modelo
 *
 * PERSISTENCIA:
 *  El UUID del ArmorStand se guarda en data.yml.
 *  Al reiniciar el servidor, restoreOnStartup() verifica que cada ArmorStand
 *  spawneado siga existiendo. Si fue eliminado manualmente, lo re-spawnea
 *  en la última posición conocida (o en el spawn del mundo como fallback).
 */
public class BagSpawnManager {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;

    private static final String STAND_DISPLAY_NAME = "§6Boxing Bag";

    // Detectado una vez al iniciar — no cambia en runtime
    private final boolean supportsCustomModelData;

    public BagSpawnManager(BoxTopPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.supportsCustomModelData = detectCustomModelDataSupport();

        if (supportsCustomModelData) {
            plugin.getLogger().info("[BoxTop] CustomModelData supported (1.14+). Resource pack models enabled.");
        } else {
            plugin.getLogger().info("[BoxTop] CustomModelData not supported (<1.14). Spawned bags will show default item.");
        }
    }

    // -------------------------------------------------------
    // RESTAURACIÓN AL INICIAR
    // -------------------------------------------------------

    /**
     * Llamar desde onEnable() DESPUÉS de que DataManager haya cargado los sacos.
     *
     * Para cada saco con spawned=true verifica:
     *  1. ¿La entidad existe en el mundo? → re-aplica configuración (por si hubo reload)
     *  2. ¿No existe? → la re-spawnea en el spawn del mundo como fallback y actualiza el UUID
     *
     * Se ejecuta con un delay de 20 ticks para asegurar que todos los chunks
     * estén cargados antes de buscar las entidades.
     */
    public void restoreOnStartup() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int restored = 0;
            int missing  = 0;

            for (BagData bag : dataManager.getAllBags()) {
                if (!bag.isSpawned()) continue;

                Entity entity = Bukkit.getEntity(bag.getUuid());

                if (entity instanceof ArmorStand && entity.isValid()) {
                    // Existe — re-aplicar configuración por si el ítem se perdió
                    configureStand((ArmorStand) entity);
                    applyHeadItem((ArmorStand) entity, bag.getCustomModelData());
                    restored++;
                } else {
                    // No existe — re-spawnear
                    plugin.getLogger().warning("[BoxTop] Spawned bag '" + bag.getName()
                        + "' entity not found. Re-spawning...");

                    ArmorStand newStand = respawnStand(bag);
                    if (newStand != null) {
                        // Actualizar UUID en el BagData y guardar
                        // Creamos un nuevo BagData con el mismo nombre pero nuevo UUID
                        dataManager.removeBoxingBag(bag.getName());
                        BagData newBag = dataManager.addBoxingBag(bag.getName(), newStand.getUniqueId());
                        copyConfig(bag, newBag);
                        dataManager.saveBag(newBag);
                        missing++;
                    }
                }
            }

            if (restored > 0 || missing > 0) {
                plugin.getLogger().info("[BoxTop] Spawned bags: " + restored + " restored, " + missing + " re-spawned.");
            }
        }, 20L);
    }

    // -------------------------------------------------------
    // SPAWN
    // -------------------------------------------------------

    /**
     * Spawnea un nuevo ArmorStand y lo registra como saco.
     * @return BagData creado, o null si el nombre ya existe.
     */
    public BagData spawnBag(String name, Location location) {
        if (dataManager.getBagUUID(name) != null) return null;

        ArmorStand stand = location.getWorld().spawn(location, ArmorStand.class);
        configureStand(stand);

        BagData bag = dataManager.addBoxingBag(name, stand.getUniqueId());
        bag.setSpawned(true);

        int cmdValue = plugin.getConfig().getInt("spawned-bag.custom-model-data", 0);
        bag.setCustomModelData(cmdValue);

        applyHeadItem(stand, cmdValue);
        dataManager.saveBag(bag);

        return bag;
    }

    /**
     * Elimina el ArmorStand físicamente si el saco es spawned.
     */
    public void removeBag(BagData bag) {
        if (!bag.isSpawned()) return;
        Entity entity = Bukkit.getEntity(bag.getUuid());
        if (entity instanceof ArmorStand) {
            entity.remove();
        }
    }

    /**
     * Re-aplica el ítem con CustomModelData actualizado.
     * Llamado desde /boxtop reload.
     */
    public void refreshItem(BagData bag) {
        if (!bag.isSpawned()) return;
        Entity entity = Bukkit.getEntity(bag.getUuid());
        if (entity instanceof ArmorStand) {
            applyHeadItem((ArmorStand) entity, bag.getCustomModelData());
        }
    }

    // -------------------------------------------------------
    // CONFIGURACIÓN DEL ARMORSTAND
    // -------------------------------------------------------

    private void configureStand(ArmorStand stand) {
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setCustomName(STAND_DISPLAY_NAME);
        stand.setCustomNameVisible(true);

        // setInvulnerable: 1.9+ — en 1.12 existe, envuelto por seguridad
        try { stand.setInvulnerable(true); } catch (Exception ignored) {}

        // setArms: 1.8+ pero algunas builds lo tienen diferente
        try { stand.setArms(true); } catch (Exception ignored) {}

        // Tamaño normal (no small)
        try { stand.setSmall(false); } catch (Exception ignored) {}

        // Marcar como persistente para que no despawne solo
        // setPersistent es 1.16+ — fallback silencioso en versiones anteriores
        try {
            Method m = stand.getClass().getMethod("setPersistent", boolean.class);
            m.invoke(stand, true);
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------
    // ÍTEM EN LA CABEZA (CORREGIDO CON EL TRUCO DEL PAPEL)
    // -------------------------------------------------------

    private void applyHeadItem(ArmorStand stand, int customModelData) {
        // Usamos PAPER para evitar bugs de armaduras en la cabeza
        ItemStack item = XMaterial.PAPER.parseItem();
        if (item == null) {
            item = new ItemStack(org.bukkit.Material.PAPER);
        }

        if (customModelData > 0) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                // MÉTODO NATIVO 1.21: Evita que la reflexión falle silenciosamente
                meta.setCustomModelData(customModelData);
                meta.setDisplayName(STAND_DISPLAY_NAME);
                item.setItemMeta(meta);
            }
        }

        // Equipar en la cabeza de forma directa
        if (stand.getEquipment() != null) {
            stand.getEquipment().setHelmet(item);
        }
    }

    // -------------------------------------------------------
    // RE-SPAWN DE ENTIDAD PERDIDA
    // -------------------------------------------------------

    private ArmorStand respawnStand(BagData bag) {
        // Intentar spawnear en el mundo principal como fallback
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world == null) return null;

        Location loc = world.getSpawnLocation();
        ArmorStand stand = world.spawn(loc, ArmorStand.class);
        configureStand(stand);
        applyHeadItem(stand, bag.getCustomModelData());
        return stand;
    }

    /**
     * Copia la configuración de un BagData a otro (para re-spawn).
     * No copia nombre ni UUID porque esos son inmutables.
     */
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
    }

    // -------------------------------------------------------
    // HELPERS DE COMPATIBILIDAD
    // -------------------------------------------------------

    /**
     * Aplica CustomModelData via reflexión.
     * En 1.14+: ItemMeta.setCustomModelData(Integer) existe directamente.
     * En <1.14: el método no existe → no hace nada (fail silencioso).
     */
    private void setCustomModelDataSafe(ItemMeta meta, int value) {
        try {
            Method m = meta.getClass().getMethod("setCustomModelData", Integer.class);
            m.invoke(meta, value);
        } catch (NoSuchMethodException ignored) {
            // < 1.14 — CustomModelData no existe, comportamiento esperado
        } catch (Exception e) {
            plugin.getLogger().warning("[BoxTop] Could not set CustomModelData: " + e.getMessage());
        }
    }

    /**
     * Detecta si la versión soporta CustomModelData comprobando si el método
     * existe en ItemMeta. Más confiable que parsear la versión como string.
     */
    private boolean detectCustomModelDataSupport() {
        try {
            ItemMeta.class.getMethod("setCustomModelData", Integer.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}