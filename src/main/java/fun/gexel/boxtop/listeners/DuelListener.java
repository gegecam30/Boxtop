package fun.gexel.boxtop.listeners;

import com.cryptomorin.xseries.XMaterial;
import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.gui.BagConfigGUI;
import fun.gexel.boxtop.gui.DuelGUI;
import fun.gexel.boxtop.managers.DataManager;
import fun.gexel.boxtop.managers.DuelManager;
import fun.gexel.boxtop.objects.BagData;
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
    private final BagConfigGUI configGUI;

    // Sesión: jugador → saco seleccionado (para el menú de duelos)
    private final java.util.Map<UUID, UUID> playerSelectedBag = new java.util.HashMap<>();

    public DuelListener(BoxTopPlugin plugin, DataManager dataManager,
                        DuelManager duelManager, DuelGUI duelGUI, BagConfigGUI configGUI) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.duelManager = duelManager;
        this.duelGUI = duelGUI;
        this.configGUI = configGUI;
    }

    // -------------------------------------------------------
    // CLICK DERECHO EN UN SACO
    // -------------------------------------------------------

    @EventHandler
    public void onRightClickBag(PlayerInteractEntityEvent event) {
        // Evitar doble ejecución (mano off-hand)
        if (event.getHand() != EquipmentSlot.HAND) return;

        UUID entityUUID = event.getRightClicked().getUniqueId();
        if (!dataManager.isBoxingBag(entityUUID)) return;

        Player player = event.getPlayer();
        ItemStack itemHand = player.getInventory().getItemInMainHand();

        // Permitir riendas (lead) sin interceptar
        Material leadMat = XMaterial.LEAD.parseMaterial();
        if (itemHand != null && itemHand.getType() == leadMat) return;

        event.setCancelled(true);

        // --- AGACHADO + CLICK DERECHO → GUI de configuración (solo admins) ---
        if (player.isSneaking()) {
            if (!player.hasPermission("boxtop.admin")) {
                player.sendMessage(dataManager.getMessage("no-permission"));
                return;
            }
            BagData bag = dataManager.getBagByUUID(entityUUID);
            if (bag != null) {
                configGUI.open(player, bag.getName());
            }
            return;
        }

        // --- CLICK DERECHO SIMPLE → Menú de duelos ---
        if (!plugin.getConfig().getBoolean("duels.enabled")) return;

        if (duelManager.isBagInDuel(entityUUID)) {
            player.sendMessage(dataManager.getMessage("duels.messages.bag-busy"));
            return;
        }

        playerSelectedBag.put(player.getUniqueId(), entityUUID);
        duelGUI.openDuelMenu(player);
    }

    // -------------------------------------------------------
    // CLICK DENTRO DEL MENÚ DE DUELOS
    // -------------------------------------------------------

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        String configTitleRaw = dataManager.getRawMessage("duels.messages.menu-title");
        String configTitle = ChatColor.stripColor(configTitleRaw);
        String viewTitle = ChatColor.stripColor(event.getView().getTitle());

        if (!viewTitle.contains(configTitle)) return;

        event.setCancelled(true);

        if (event.getCurrentItem() == null
                || event.getCurrentItem().getType() == Material.AIR) return;

        ItemStack item = event.getCurrentItem();
        ItemMeta meta = item.getItemMeta();

        boolean isHead = false;
        try {
            isHead = item.getType() == XMaterial.PLAYER_HEAD.parseMaterial()
                  || item.getType().name().contains("SKULL")
                  || item.getType().name().contains("HEAD");
        } catch (Exception ignored) {}

        if (!isHead || meta == null || !meta.hasDisplayName()) return;

        String targetName = ChatColor.stripColor(meta.getDisplayName());
        Player target   = plugin.getServer().getPlayerExact(targetName);
        Player inviter  = (Player) event.getWhoClicked();

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

    // -------------------------------------------------------
    // DRAG — anti-robo en menú de duelos
    // -------------------------------------------------------

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
