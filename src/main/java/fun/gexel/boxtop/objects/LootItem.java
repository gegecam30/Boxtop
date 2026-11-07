package fun.gexel.boxtop.objects;

import com.cryptomorin.xseries.XMaterial;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents a single configurable loot entry for the Rewards system.
 *
 * Used by both the Loot Table (per-hit drops) and Piñata Mode (burst drops).
 * Items are selected via weighted random — higher weight = more common.
 */
public class LootItem {

    private final String material;   // XMaterial name (e.g. GOLD_NUGGET)
    private final int    amount;
    private final int    weight;     // For weighted random selection (minimum 1)
    private final String customName; // Optional color-coded display name
    private final List<String> lore; // Optional color-coded lore lines

    public LootItem(String material, int amount, int weight,
                    String customName, List<String> lore) {
        this.material   = material;
        this.amount     = Math.max(1, amount);
        this.weight     = Math.max(1, weight);
        this.customName = customName;
        this.lore       = lore;
    }

    public String       getMaterial() { return material; }
    public int          getAmount()   { return amount; }
    public int          getWeight()   { return weight; }

    /**
     * Builds the physical ItemStack ready to drop into the world.
     * Returns null if the material name is invalid or unsupported on this version.
     */
    public ItemStack buildItem() {
        XMaterial xmat;
        try {
            xmat = XMaterial.valueOf(material.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }

        ItemStack item = xmat.parseItem();
        if (item == null) return null;
        item.setAmount(amount);

        if (customName != null || (lore != null && !lore.isEmpty())) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (customName != null) {
                    meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', customName));
                }
                if (lore != null && !lore.isEmpty()) {
                    List<String> coloredLore = new ArrayList<>();
                    for (String line : lore) {
                        coloredLore.add(ChatColor.translateAlternateColorCodes('&', line));
                    }
                    meta.setLore(coloredLore);
                }
                item.setItemMeta(meta);
            }
        }

        return item;
    }

    // -------------------------------------------------------
    // PARSING FROM YAML
    // -------------------------------------------------------

    /**
     * Parses a list of LootItems from a ConfigurationSection's list key.
     *
     * Expected YAML structure under `section`:
     *   items:
     *     - material: GOLD_NUGGET
     *       amount: 1
     *       weight: 70
     *       name: "&6Custom Name"   (optional)
     *       lore:                   (optional)
     *         - "&7First line"
     */
    public static List<LootItem> parseList(ConfigurationSection section, String listKey) {
        if (section == null) return Collections.emptyList();

        List<Map<?, ?>> rawList = section.getMapList(listKey);
        if (rawList.isEmpty()) return Collections.emptyList();

        List<LootItem> result = new ArrayList<>();
        for (Map<?, ?> map : rawList) {
            Object matObj = map.get("material");
            String mat    = matObj != null ? matObj.toString() : "STONE";
            int    amt    = parseIntSafe(map.get("amount"),  1);
            int    wgt    = parseIntSafe(map.get("weight"),  10);
            Object nameObj = map.get("name");
            String name   = nameObj != null ? nameObj.toString() : null;

            List<String> lore = new ArrayList<>();
            Object rawLore = map.get("lore");
            if (rawLore instanceof List) {
                for (Object l : (List<?>) rawLore) {
                    if (l != null) lore.add(l.toString());
                }
            }

            result.add(new LootItem(mat, amt, wgt, name, lore.isEmpty() ? null : lore));
        }
        return result;
    }

    private static int parseIntSafe(Object obj, int defaultValue) {
        if (obj == null) return defaultValue;
        try { return Integer.parseInt(String.valueOf(obj)); }
        catch (NumberFormatException e) { return defaultValue; }
    }
}
