package fun.gexel.boxtop.listeners;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.gui.BagConfigGUI;
import fun.gexel.boxtop.managers.DataManager;
import fun.gexel.boxtop.managers.GlowManager;
import fun.gexel.boxtop.objects.BagData;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Maneja todos los clics dentro del BagConfigGUI.
 *
 * Estrategia para leer la acción de cada ítem:
 * La última línea del lore contiene el código de acción (ej. "ACTION:TOGGLE_GLOW").
 * Esto evita guardar estado externo por slot y hace el GUI robusto ante reordenamientos.
 */
public class BagConfigListener implements Listener {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private final GlowManager glowManager;
    private final BagConfigGUI configGUI;

    public BagConfigListener(BoxTopPlugin plugin, DataManager dataManager,
                             GlowManager glowManager, BagConfigGUI configGUI) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.glowManager = glowManager;
        this.configGUI = configGUI;
    }

    // -------------------------------------------------------
    // CLICK HANDLER
    // -------------------------------------------------------

    @EventHandler
    public void onConfigClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        String title = event.getView().getTitle();
        if (!title.startsWith(BagConfigGUI.GUI_TITLE_PREFIX)) return;

        // Siempre cancelar para que no roben ítems
        event.setCancelled(true);

        if (event.getCurrentItem() == null
                || event.getCurrentItem().getType() == Material.AIR) return;

        Player player = (Player) event.getWhoClicked();

        // Extraer el nombre del saco desde el título
        String bagName = ChatColor.stripColor(title)
                .replace("[Config] ", "")
                .trim();

        BagData bag = dataManager.getBag(bagName);
        if (bag == null) {
            player.sendMessage(ChatColor.RED + "Error: bag not found.");
            player.closeInventory();
            return;
        }

        // Botón cerrar (slot 49)
        if (event.getRawSlot() == 49) {
            player.closeInventory();
            return;
        }

        // Leer acción desde la última línea del lore
        String action = extractAction(event.getCurrentItem());
        if (action == null) return;

        // Delegar al GUI la lógica de cambio
        boolean changed = configGUI.handleClick(action, bag);

        if (changed) {
            // 1. Persistir en data.yml
            dataManager.saveBag(bag);

            // 2. Actualizar glow en tiempo real si cambió algo relacionado
            if (action.contains("GLOW")) {
                glowManager.updateGlow();
            }

            // 3. Refrescar el inventario abierto sin cerrarlo
            configGUI.populate(event.getInventory(), bag);

            // 4. Feedback sonoro leve
            try {
                player.playSound(player.getLocation(),
                    org.bukkit.Sound.valueOf("UI_BUTTON_CLICK"), 0.5f, 1.2f);
            } catch (Exception e) {
                try {
                    player.playSound(player.getLocation(),
                        org.bukkit.Sound.valueOf("CLICK"), 0.5f, 1.2f);
                } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------
    // DRAG — siempre cancelar dentro del GUI
    // -------------------------------------------------------

    @EventHandler
    public void onConfigDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().startsWith(BagConfigGUI.GUI_TITLE_PREFIX)) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------
    // HELPER: extraer código de acción del lore
    // -------------------------------------------------------

    /**
     * La convención es que el código de acción se guarda como última línea del lore,
     * con formato "ACTION:NOMBRE_ACCION" en color DARK_GRAY (§8).
     * ChatColor.stripColor nos da el texto limpio.
     */
    private String extractAction(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return null;

        List<String> lore = meta.getLore();
        if (lore == null || lore.isEmpty()) return null;

        String lastLine = ChatColor.stripColor(lore.get(lore.size() - 1)).trim();
        return lastLine.startsWith("ACTION:") ? lastLine : null;
    }
}
