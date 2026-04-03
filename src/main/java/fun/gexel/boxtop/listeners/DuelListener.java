package fun.gexel.boxtop.listeners;

import com.cryptomorin.xseries.XMaterial;
import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.gui.DuelGUI;
import fun.gexel.boxtop.managers.DataManager;
import fun.gexel.boxtop.managers.DuelManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;

public class DuelListener implements Listener {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private final DuelManager duelManager;
    private final DuelGUI duelGUI;

    private final java.util.Map<UUID, UUID> playerSelectedBag = new java.util.HashMap<>();

    public DuelListener(BoxTopPlugin plugin, DataManager dataManager, DuelManager duelManager, DuelGUI duelGUI) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.duelManager = duelManager;
        this.duelGUI = duelGUI;
    }

    @EventHandler
    public void onRightClickBag(PlayerInteractEntityEvent event) {
        // 1. Evitar doble ejecución
        if (event.getHand() != EquipmentSlot.HAND) return;
        
        if (!plugin.getConfig().getBoolean("duels.enabled")) return;
        
        // Verificar si es un saco
        if (dataManager.isBoxingBag(event.getRightClicked().getUniqueId())) {
            
            Player player = event.getPlayer();
            ItemStack itemHand = player.getInventory().getItemInMainHand();

            // --- FIX: PERMITIR RIENDAS (LEADS) ---
            // Si el jugador tiene una rienda en la mano, NO abrimos el menú.
            // Dejamos que Minecraft ejecute la lógica vanilla (atar al mob).
            Material leadMat = XMaterial.LEAD.parseMaterial();
            if (itemHand != null && itemHand.getType() == leadMat) {
                return; // Salimos y NO cancelamos el evento -> La rienda funciona.
            }
            // -------------------------------------
            
            // Si el saco está ocupado en un duelo
            if (duelManager.isBagInDuel(event.getRightClicked().getUniqueId())) {
                player.sendMessage(dataManager.getMessage("duels.messages.bag-busy"));
                return;
            }

            // Abrir menú normal
            playerSelectedBag.put(player.getUniqueId(), event.getRightClicked().getUniqueId());
            duelGUI.openDuelMenu(player);
            event.setCancelled(true); 
        }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        String configTitleRaw = dataManager.getRawMessage("duels.messages.menu-title");
        String configTitle = ChatColor.stripColor(configTitleRaw);
        String viewTitle = ChatColor.stripColor(event.getView().getTitle());

        if (viewTitle.contains(configTitle)) {
            // Anti-Robo (Click)
            event.setCancelled(true); 
            
            if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

            ItemStack item = event.getCurrentItem();
            ItemMeta meta = item.getItemMeta();
            
            boolean isHead = false;
            try {
                isHead = item.getType() == XMaterial.PLAYER_HEAD.parseMaterial() || 
                         item.getType().name().contains("SKULL") || 
                         item.getType().name().contains("HEAD");
            } catch (Exception ignored) {}

            if (isHead && meta != null && meta.hasDisplayName()) {
                
                String targetName = ChatColor.stripColor(meta.getDisplayName());
                Player target = plugin.getServer().getPlayerExact(targetName);
                Player inviter = (Player) event.getWhoClicked();

                if (target == null || !target.isOnline()) {
                    inviter.sendMessage(ChatColor.RED + "Error: Player is offline.");
                    inviter.closeInventory();
                    return;
                }

                if (target.equals(inviter)) return;

                UUID bagUUID = playerSelectedBag.get(inviter.getUniqueId());
                
                if (bagUUID != null) {
                    duelManager.sendInvite(inviter, target, bagUUID);
                    inviter.closeInventory();
                } else {
                    inviter.closeInventory();
                    inviter.sendMessage(ChatColor.RED + "Error: Session expired.");
                }
            }
        }
    }

    // Anti-Robo (Arrastrar)
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String configTitleRaw = dataManager.getRawMessage("duels.messages.menu-title");
        String configTitle = ChatColor.stripColor(configTitleRaw);
        String viewTitle = ChatColor.stripColor(event.getView().getTitle());

        if (viewTitle.contains(configTitle)) {
            event.setCancelled(true);
        }
    }
}