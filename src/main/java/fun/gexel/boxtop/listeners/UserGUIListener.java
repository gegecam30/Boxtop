package fun.gexel.boxtop.listeners;

import fun.gexel.boxtop.gui.UserGUI;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Handles click events inside the User GUI.
 *
 * Action routing is done via the last lore line convention:
 *   "ACTION:DUEL"  → close UserGUI, hand off to DuelGUI
 *   "ACTION:CLOSE" → close inventory
 *
 * Any slot without an ACTION lore simply cancels the click (no item stealing).
 */
public class UserGUIListener implements Listener {

    private final UserGUI     userGUI;
    private final DuelListener duelListener; // needed to register selected bag before opening DuelGUI

    public UserGUIListener(UserGUI userGUI, DuelListener duelListener) {
        this.userGUI      = userGUI;
        this.duelListener = duelListener;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        String title = event.getView().getTitle();
        if (!title.startsWith(UserGUI.TITLE_PREFIX)) return;

        event.setCancelled(true); // always cancel — no item manipulation

        if (event.getCurrentItem() == null
                || event.getCurrentItem().getType().name().equals("AIR")) return;

        Player player = (Player) event.getWhoClicked();
        String action = extractAction(event.getCurrentItem());
        if (action == null) return;

        switch (action.toUpperCase()) {
            case "DUEL":
                handleDuelButton(player);
                break;
            case "CLOSE":
                player.closeInventory();
                break;
            default:
                break;
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        if (!event.getView().getTitle().startsWith(UserGUI.TITLE_PREFIX)) return;
        userGUI.removePlayer(event.getPlayer().getUniqueId());
    }

    // -------------------------------------------------------
    // ACTION HANDLERS
    // -------------------------------------------------------

    private void handleDuelButton(Player player) {
        String bagName = userGUI.getBagForPlayer(player.getUniqueId());
        if (bagName == null) {
            player.sendMessage(ChatColor.RED + "[BoxTop] Could not find the bag. Try again.");
            player.closeInventory();
            return;
        }

        // Let DuelListener know which bag this player selected
        duelListener.openDuelGUIForPlayer(player, bagName);
    }

    // -------------------------------------------------------
    // HELPER — Extract ACTION from last lore line
    // -------------------------------------------------------

    private String extractAction(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return null;
        List<String> lore = meta.getLore();
        if (lore.isEmpty()) return null;
        String last = ChatColor.stripColor(lore.get(lore.size() - 1)).trim();
        if (last.startsWith("ACTION:")) {
            return last.substring(7);
        }
        return null;
    }
}
