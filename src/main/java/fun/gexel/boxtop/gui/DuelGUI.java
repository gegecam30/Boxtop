package fun.gexel.boxtop.gui;

import com.cryptomorin.xseries.XMaterial; // Importamos XSeries
import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.DataManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public class DuelGUI {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;

    public DuelGUI(BoxTopPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
    }

    public void openDuelMenu(Player player) {
        int radius = plugin.getConfig().getInt("duels.search-radius", 15);
        List<Player> nearbyPlayers = new ArrayList<>();

        for (Player p : player.getWorld().getPlayers()) {
            if (p.getLocation().distance(player.getLocation()) <= radius && !p.equals(player)) {
                nearbyPlayers.add(p);
            }
        }

        if (nearbyPlayers.isEmpty()) {
            player.sendMessage(dataManager.getMessage("duels.messages.no-players-nearby"));
            return;
        }

        String title = dataManager.getRawMessage("duels.messages.menu-title");
        int size = (int) (Math.ceil(nearbyPlayers.size() / 9.0) * 9);
        if (size > 54) size = 54;
        
        Inventory inv = Bukkit.createInventory(null, size, title);

        for (Player p : nearbyPlayers) {
            // --- FIX CRÍTICO 1.12: USAR XMATERIAL ---
            ItemStack head = XMaterial.PLAYER_HEAD.parseItem(); 
            if (head != null) {
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                if (meta != null) {
                    // setOwningPlayer es 1.12+, pero por seguridad en versiones raras usamos fallback
                    try {
                         meta.setOwningPlayer(p);
                    } catch (Exception e) {
                         meta.setOwner(p.getName()); // Método Legacy
                    }
                    
                    meta.setDisplayName(org.bukkit.ChatColor.YELLOW + p.getName());
                    List<String> lore = new ArrayList<>();
                    lore.add(dataManager.getRawMessage("duels.messages.click-to-challenge").replace("{player}", p.getName()));
                    meta.setLore(lore);
                    head.setItemMeta(meta);
                    inv.addItem(head);
                }
            }
        }

        player.openInventory(inv);
    }
}