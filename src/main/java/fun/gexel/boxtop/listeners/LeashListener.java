package fun.gexel.boxtop.listeners;

import com.cryptomorin.xseries.XMaterial;
import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.DataManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * LeashListener — riendas para admins con soporte completo de vallas (1.12 - 1.21).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * PROBLEMA RAÍZ: Vallas en 1.21 Paper
 * ─────────────────────────────────────────────────────────────────────────────
 * En 1.21 Paper, cuando el jugador intenta atar una entidad a una valla después
 * de amarrarla, la mecánica vanilla falla silenciosamente. El servidor manda el
 * sonido del nudo pero no cambia el estado.
 *
 * Causa probable: el tracking interno del servidor de 1.21 para "qué entidades
 * están amarradas a este jugador" usa una estructura diferente o tiene validaciones
 * extra que la entidad configurada (Villager invisible con SLOWNESS-255) no pasa.
 *
 * SOLUCIÓN: Escuchar PlayerInteractEvent en bloques de valla y, si el jugador
 * tiene un saco amarrado, crear manualmente un LeashHitch (el nudo de valla) y
 * asignarlo como LeashHolder de la entidad.
 *
 * LeashHitch es exactamente la entidad que Minecraft crea internamente cuando
 * ata un mob a una valla — usar la misma API garantiza que todo funcione:
 * la cuerda visual, el detach al romper la valla, etc.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FLUJO FINAL
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Admin right-click entidad + rienda → PlayerInteractEntityEvent
 *    - Admin: NO cancelar → vanilla procesa el leash completo.
 *    - 1 tick después: si vanilla no lo amarró (mob no-leashable), aplicar manual.
 *
 * 2. Admin right-click valla (con saco amarrado) → PlayerInteractEvent
 *    - Solo en 1.14+ (en 1.12 vanilla funciona solo).
 *    - Si hay saco amarrado al jugador → tieToFence() → LeashHitch manual.
 *    - Cancelar evento para evitar doble-procesamiento.
 *
 * 3. Sin permiso → cancelar siempre antes de vanilla.
 *
 * Compatibilidad: 1.12 - 1.21
 */
public class LeashListener implements Listener {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private final Material leadMaterial;
    private final boolean isModernVersion; // true si versión >= 1.14

    public LeashListener(BoxTopPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        Material mat = XMaterial.LEAD.parseMaterial();
        this.leadMaterial = (mat != null) ? mat : Material.AIR;
        this.isModernVersion = detectModernVersion();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1) AMARRAR AL JUGADOR (clic en entidad con rienda)
    // ═══════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.NORMAL)
    public void onLeashEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        if (item == null || leadMaterial == Material.AIR || item.getType() != leadMaterial) return;

        Entity target = event.getRightClicked();
        if (!(target instanceof LivingEntity)) return;

        // Solo intervenir si la entidad clickeada es un saco de BoxTop
        if (!dataManager.isBoxingBag(target.getUniqueId())) return;

        Player player = event.getPlayer();

        // Sin permiso → bloquear antes de que vanilla procese
        if (!player.hasPermission("boxtop.admin")) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You don't have permission to leash BoxTop bags.");
            return;
        }

        // Admin: NO cancelar el evento.
        // Vanilla procesará el leash completamente (incluyendo su tracking interno).
        final LivingEntity living = (LivingEntity) target;
        final boolean wasLeashed = living.isLeashed();

        // 1 tick después: si vanilla no aplicó el leash (entidad no leashable en vanilla),
        // lo aplicamos manualmente (para /boxtop setentity con mobs hostiles, etc.)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            final boolean isLeashedNow;
            try {
                isLeashedNow = living.isLeashed();
            } catch (Exception e) {
                return; // Entidad removida
            }

            // Vanilla NO amarró la entidad → aplicar manualmente
            if (!wasLeashed && !isLeashedNow) {
                ItemStack currentItem = player.getInventory().getItemInMainHand();
                if (currentItem == null || currentItem.getType() != leadMaterial) return;

                try {
                    boolean success = living.setLeashHolder(player);
                    if (success) {
                        consumeLead(player, currentItem);
                        player.sendMessage(ChatColor.GREEN + formatName(living) + " leashed!");
                        player.sendMessage(ChatColor.GRAY + "(Right-click a fence post to tie it)");
                    } else {
                        player.sendMessage(ChatColor.RED + formatName(living) + " cannot be leashed.");
                    }
                } catch (Exception e) {
                    player.sendMessage(ChatColor.RED + "Could not leash " + formatName(living) + ".");
                }
            }
            // wasLeashed=false → isLeashedNow=true: vanilla amarró la entidad ✓
            // wasLeashed=true  → isLeashedNow=false: vanilla la desamarró ✓
        }, 1L);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2) ATAR A LA VALLA — FIX para 1.21 Paper
    //    Se activa solo en 1.14+ (en 1.12 vanilla funciona perfectamente).
    // ═══════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.NORMAL)
    public void onFenceTie(PlayerInteractEvent event) {
        // Solo versiones modernas — en 1.12 vanilla funciona solo, no interferir
        if (!isModernVersion) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null || !isFencePost(block.getType())) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("boxtop.admin")) return;

        // Buscar saco (bag) que esté amarrado a este jugador
        for (UUID bagUUID : dataManager.getAllBagUUIDs()) {
            Entity entity = Bukkit.getEntity(bagUUID);
            if (!(entity instanceof LivingEntity)) continue;
            LivingEntity living = (LivingEntity) entity;

            try {
                if (!living.isLeashed()) continue;
                Entity holder = living.getLeashHolder();
                if (holder == null || !holder.equals(player)) continue;
            } catch (Exception e) {
                continue;
            }

            // Hay un saco amarrado al jugador → atarlo a la valla manualmente
            event.setCancelled(true); // Prevenir doble-procesamiento de vanilla
            tieToFence(living, block, player);
            return;
        }
    }

    /**
     * Ata la entidad a la valla creando (o reutilizando) un LeashHitch.
     *
     * LeashHitch es la entidad-nudo que Minecraft usa internamente cuando
     * se ata un mob a una valla. Al usarla directamente:
     * - La cuerda visual se renderiza correctamente.
     * - El mob se suelta si se rompe la valla (comportamiento vanilla).
     * - El jugador puede soltar el nudo con clic derecho.
     */
    private void tieToFence(LivingEntity entity, Block fenceBlock, Player player) {
        // El nudo se coloca en el centro superior del bloque de valla
        Location hitchLoc = fenceBlock.getLocation().add(0.5, 0.5, 0.5);
        World world = fenceBlock.getWorld();

        // Reutilizar LeashHitch existente en esa valla (evitar duplicados)
        for (Entity nearby : world.getNearbyEntities(hitchLoc, 0.6, 0.6, 0.6)) {
            if (nearby.getType().name().equals("LEASH_HITCH")) {
                entity.setLeashHolder(nearby);
                playKnotSound(world, hitchLoc);
                return;
            }
        }

        // Crear nuevo LeashHitch
        try {
            EntityType hitchType = EntityType.valueOf("LEASH_HITCH");
            Entity hitch = world.spawnEntity(hitchLoc, hitchType);
            boolean tied = entity.setLeashHolder(hitch);
            if (tied) {
                playKnotSound(world, hitchLoc);
            } else {
                // Si setLeashHolder falla por algún motivo, remover el hitch huérfano
                hitch.remove();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[BoxTop] Could not create LeashHitch: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private void playKnotSound(World world, Location loc) {
        try {
            Sound sound = Sound.valueOf("ENTITY_LEASH_KNOT_PLACE");
            world.playSound(loc, sound, 1.0f, 1.0f);
        } catch (Exception ignored) {} // No romper si el sound no existe en esta versión
    }

    /** True si el bloque es una valla (no puerta de valla). Funciona en todas las versiones. */
    private boolean isFencePost(Material material) {
        String name = material.name();
        return name.contains("FENCE") && !name.contains("FENCE_GATE");
    }

    private void consumeLead(Player player, ItemStack leadItem) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (leadItem.getAmount() > 1) {
            leadItem.setAmount(leadItem.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }

    private String formatName(Entity entity) {
        String raw = entity.getType().name().replace("_", " ").toLowerCase();
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private boolean detectModernVersion() {
        String versionRaw = Bukkit.getBukkitVersion();
        try {
            String clean = versionRaw.split("-")[0];
            String[] parts = clean.split("\\.");
            if (parts.length >= 2) {
                return Integer.parseInt(parts[1]) >= 14;
            }
        } catch (Exception ignored) {}
        return true; // Fallback seguro: asumir versión moderna
    }
}
