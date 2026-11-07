package fun.gexel.boxtop.gui;

import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.DataManager;
import fun.gexel.boxtop.managers.RewardManager;
import fun.gexel.boxtop.managers.RewardsFileManager;
import fun.gexel.boxtop.objects.BagData;
import fun.gexel.boxtop.objects.PlayerStat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/**
 * User GUI — the player-facing menu opened by right-clicking any boxing bag.
 *
 * Layout (27 slots, 3 rows):
 *   [G][G][G][G][G][G][G][G][G]   ← decorative border (material-based color)
 *   [G][S][I][G][D][G][R][T][G]   ← functional buttons
 *   [G][G][G][G][X][G][G][G][G]   ← close button
 *
 *   S = My Stats (slot 10) — player head showing damage/hits/rank
 *   I = Bag Info (slot 11) — bag properties
 *   D = Duel     (slot 13) — opens DuelGUI
 *   R = Rewards  (slot 15) — progress bars for all reward layers
 *   T = Top      (slot 16) — top 5 on this bag
 *   X = Close    (slot 22)
 *
 * Features flagged as disabled show as "Coming soon..." buttons to build hype.
 */
public class UserGUI {

    /** Title prefix used by UserGUIListener to identify this inventory. */
    public static final String TITLE_PREFIX = "§8[§6BoxTop§8] §r";

    private final BoxTopPlugin    plugin;
    private final DataManager     dataManager;
    private final RewardManager   rewardManager;
    private final RewardsFileManager rewardsFile;

    // Tracks which bag UUID each player has open → used by listener
    private final Map<UUID, String> playerBag = new HashMap<>();

    public UserGUI(BoxTopPlugin plugin, DataManager dataManager, RewardManager rewardManager) {
        this.plugin        = plugin;
        this.dataManager   = dataManager;
        this.rewardManager = rewardManager;
        this.rewardsFile   = dataManager.getRewardsFileManager();
    }

    // -------------------------------------------------------
    // PUBLIC API
    // -------------------------------------------------------

    public void open(Player player, BagData bag) {
        String title = TITLE_PREFIX + bag.getName();
        Inventory inv = Bukkit.createInventory(null, 27, title);

        // Register player → bag mapping before opening
        playerBag.put(player.getUniqueId(), bag.getName());

        // --- Borders ---
        ItemStack glass = makeBorderGlass(bag);
        for (int slot : new int[]{0,1,2,3,4,5,6,7,8, 9,17, 18,19,20,21,23,24,25,26}) {
            inv.setItem(slot, glass);
        }

        // --- Slot 10: My Stats ---
        inv.setItem(10, buildStatsItem(player, bag));

        // --- Slot 11: Bag Info ---
        inv.setItem(11, buildInfoItem(bag));

        // --- Slot 13: Duel ---
        inv.setItem(13, buildDuelItem());

        // --- Slot 15: Rewards ---
        inv.setItem(15, buildRewardsItem(player, bag));

        // --- Slot 16: Top Ranking ---
        inv.setItem(16, buildTopItem(bag));

        // --- Slot 22: Close ---
        inv.setItem(22, buildCloseItem());

        player.openInventory(inv);
    }

    public String getBagForPlayer(UUID playerUUID) {
        return playerBag.get(playerUUID);
    }

    public void removePlayer(UUID playerUUID) {
        playerBag.remove(playerUUID);
    }

    // -------------------------------------------------------
    // SLOT BUILDERS
    // -------------------------------------------------------

    private ItemStack buildStatsItem(Player player, BagData bag) {
        boolean rankEnabled = plugin.getConfig().getBoolean("features.ranking", false);

        PlayerStat stat = dataManager.getBagPlayerStat(bag.getName(), player.getUniqueId());
        int rank = rankEnabled ? dataManager.getPlayerBagRank(bag.getName(), player.getUniqueId()) : -1;

        ItemStack head = new ItemStack(getMaterialSafe("PLAYER_HEAD", "SKULL_ITEM"), 1, (short) 3);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta) {
            try { ((SkullMeta) meta).setOwningPlayer(player); } catch (Exception ignored) {}
        }

        meta.setDisplayName(ChatColor.GOLD + "✦ " + player.getName() + "'s Stats");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        lore.add(ChatColor.GRAY + " Total Damage:  " + ChatColor.YELLOW + formatNum(stat.getDamage()));
        lore.add(ChatColor.GRAY + " Hits:          " + ChatColor.YELLOW + formatNum(stat.getHits()));
        lore.add(ChatColor.GRAY + " Best Hit:      " + ChatColor.YELLOW + String.format("%.1f", stat.getBestHit()));
        if (rankEnabled) {
            lore.add(ChatColor.GRAY + " My Rank:       " + (rank > 0
                ? ChatColor.AQUA + "#" + rank
                : ChatColor.DARK_GRAY + "Unranked"));
        }
        lore.add(ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");

        meta.setLore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack buildInfoItem(BagData bag) {
        ItemStack item = new ItemStack(getMaterialSafe("ARMOR_STAND", "STONE"), 1);
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "✦ Bag Info");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        lore.add(ChatColor.GRAY + " Name:      " + ChatColor.WHITE + bag.getName());
        lore.add(ChatColor.GRAY + " Material:  " + ChatColor.WHITE + bag.getMaterial().name());
        lore.add(ChatColor.GRAY + " Physics:   " + boolStr(bag.isPhysicsEnabled()));
        lore.add(ChatColor.GRAY + " Particles: " + boolStr(bag.isParticlesEnabled()));
        lore.add(ChatColor.GRAY + " Glow:      " + boolStr(bag.isGlowEnabled()));
        lore.add(ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildDuelItem() {
        ItemStack item = new ItemStack(getMaterialSafe("GOLDEN_SWORD", "GOLD_SWORD"), 1);
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "⚔ Challenge to Duel");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        lore.add(ChatColor.GRAY + " Challenge another player");
        lore.add(ChatColor.GRAY + " to a timed duel on this bag.");
        lore.add("");
        lore.add(ChatColor.YELLOW + " ▶ Click to select opponent");
        lore.add(ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        lore.add(ChatColor.DARK_GRAY.toString() + ChatColor.ITALIC + "ACTION:DUEL");

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildRewardsItem(Player player, BagData bag) {
        String bagName = bag.getName();
        boolean milestonesGlobal = plugin.getConfig().getBoolean("features.milestones", false);
        boolean lootTableGlobal  = plugin.getConfig().getBoolean("features.loot-table", false);
        boolean pinataGlobal     = plugin.getConfig().getBoolean("features.pinata", true);

        ItemStack item = new ItemStack(getMaterialSafe("CHEST", "CHEST"), 1);
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(ChatColor.GREEN + "✦ Rewards");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");

        // Milestones section
        if (milestonesGlobal && rewardsFile.isMilestonesEnabled(bagName)) {
            PlayerStat stat = dataManager.getBagPlayerStat(bagName, player.getUniqueId());
            double interval = rewardsFile.getMilestoneInterval(bagName);
            double progress = interval > 0 ? (stat.getDamage() % interval) : 0;
            lore.add(ChatColor.GOLD + " — Milestones —");
            lore.add(ChatColor.GRAY + " " + progressBar(progress, interval, 10)
                    + " " + ChatColor.YELLOW + formatNum(progress) + "/" + formatNum(interval));
            lore.add(ChatColor.GRAY + " Next reward in: " + ChatColor.WHITE
                    + formatNum(interval - progress) + " dmg");
        } else if (!milestonesGlobal) {
            lore.add(ChatColor.DARK_GRAY + " — Milestones —");
            lore.add(ChatColor.DARK_GRAY + "   " + ChatColor.ITALIC + "Coming soon...");
        }

        lore.add("");

        // Loot Table section
        if (lootTableGlobal && rewardsFile.isLootTableEnabled(bagName)) {
            double chance = rewardsFile.getLootTableChance(bagName);
            int itemCount = rewardsFile.getLootTableItems(bagName).size();
            lore.add(ChatColor.GOLD + " — Loot Table —");
            lore.add(ChatColor.GRAY + " Drop chance: " + ChatColor.WHITE + chance + "% per hit");
            lore.add(ChatColor.GRAY + " Possible drops: " + ChatColor.WHITE + itemCount + " item(s)");
        } else if (!lootTableGlobal) {
            lore.add(ChatColor.DARK_GRAY + " — Loot Table —");
            lore.add(ChatColor.DARK_GRAY + "   " + ChatColor.ITALIC + "Coming soon...");
        }

        lore.add("");

        // Piñata section
        if (pinataGlobal && rewardsFile.isPinataEnabled(bagName)) {
            int current  = rewardManager.getPinataCounter(bagName);
            int required = rewardsFile.getPinataHitsRequired(bagName);
            lore.add(ChatColor.GOLD + " — Piñata —");
            lore.add(ChatColor.GRAY + " " + progressBar(current, required, 10)
                    + " " + ChatColor.YELLOW + current + "/" + required + " hits");
        } else if (!pinataGlobal) {
            lore.add(ChatColor.DARK_GRAY + " — Piñata —");
            lore.add(ChatColor.DARK_GRAY + "   " + ChatColor.ITALIC + "Coming soon...");
        }

        lore.add(ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildTopItem(BagData bag) {
        boolean rankEnabled = plugin.getConfig().getBoolean("features.ranking", false);

        ItemStack item = new ItemStack(getMaterialSafe("NETHER_STAR", "NETHER_STAR"), 1);
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "✦ Top Players");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");

        if (!rankEnabled) {
            lore.add(ChatColor.DARK_GRAY + " " + ChatColor.ITALIC + "Coming soon...");
            lore.add(ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }

        List<PlayerStat> top = dataManager.getBagTop(bag.getName(), 5);
        if (top.isEmpty()) {
            lore.add(ChatColor.GRAY + " No data yet — be the first!");
        } else {
            String[] medals = {"§6#1 ", "§7#2 ", "§c#3 ", "§7#4 ", "§7#5 "};
            for (int i = 0; i < top.size(); i++) {
                PlayerStat ps  = top.get(i);
                String prefix = i < medals.length ? medals[i] : "§7#" + (i + 1) + " ";
                lore.add(" " + prefix + ChatColor.WHITE + ps.getPlayerName()
                        + ChatColor.GRAY + " — " + ChatColor.YELLOW + formatNum(ps.getDamage()) + " dmg");
            }
        }

        lore.add(ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildCloseItem() {
        ItemStack item = new ItemStack(getMaterialSafe("BARRIER", "REDSTONE"), 1);
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "✖ Close");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY.toString() + ChatColor.ITALIC + "ACTION:CLOSE");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------
    // BORDER GLASS — color based on bag material
    // -------------------------------------------------------

    private ItemStack makeBorderGlass(BagData bag) {
        String glassType;
        switch (bag.getMaterial()) {
            case STONE: glassType = "GRAY_STAINED_GLASS_PANE";   break;
            case WOOL:  glassType = "WHITE_STAINED_GLASS_PANE";  break;
            case SAND:
            default:    glassType = "ORANGE_STAINED_GLASS_PANE"; break;
        }
        ItemStack glass = new ItemStack(getMaterialSafe(glassType, "STAINED_GLASS_PANE"), 1);
        ItemMeta meta = glass.getItemMeta();
        meta.setDisplayName(ChatColor.DARK_GRAY + " ");
        glass.setItemMeta(meta);
        return glass;
    }

    // -------------------------------------------------------
    // UTILITIES
    // -------------------------------------------------------

    private String progressBar(double current, double max, int bars) {
        int filled = max > 0 ? (int) Math.min(Math.round((current / max) * bars), bars) : 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < filled; i++)  sb.append("§a■");
        for (int i = filled; i < bars; i++) sb.append("§7■");
        return sb.toString();
    }

    private String formatNum(double value) {
        if (value >= 1_000_000) return String.format("%.1fM", value / 1_000_000);
        if (value >= 1_000)     return String.format("%.1fK", value / 1_000);
        return String.format("%.0f", value);
    }

    private String formatNum(int value) {
        return formatNum((double) value);
    }

    private String boolStr(boolean value) {
        return value ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled";
    }

    /** Cross-version material lookup — tries modern name, falls back to legacy. */
    private Material getMaterialSafe(String modern, String legacy) {
        try {
            Material m = Material.valueOf(modern);
            if (m != null) return m;
        } catch (IllegalArgumentException ignored) {}
        try {
            return Material.valueOf(legacy);
        } catch (IllegalArgumentException ignored) {}
        return Material.STONE;
    }
}
