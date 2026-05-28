package fun.gexel.boxtop.gui;

import com.cryptomorin.xseries.XMaterial;
import fun.gexel.boxtop.BoxTopPlugin;
import fun.gexel.boxtop.managers.DataManager;
import fun.gexel.boxtop.managers.GlowManager;
import fun.gexel.boxtop.objects.BagData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GUI de configuración visual por saco.
 * Tamaño: 54 slots (6 filas).
 *
 * Layout:
 * [ INFO ][ --- ][ GLOW ON/OFF ][ GLOW COLOR← ][ GLOW COLOR→ ][ --- ][ PHYSICS ON/OFF ][ --- ][ --- ]
 * [ --- ][ PARTICLE ON/OFF ][ PART TYPE← ][ PART TYPE→ ][ --- ][ MATERIAL← ][ MATERIAL ][ MATERIAL→ ][ --- ]
 * ...bordes decorativos...
 * [ CERRAR ]
 */
public class BagConfigGUI {

    // Título base — usado para identificar el inventario en el listener
    public static final String GUI_TITLE_PREFIX = "§8[§6Config§8] §e";

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private final GlowManager glowManager;

    // Colores de glow disponibles (los que tienen sentido visual)
    private static final ChatColor[] GLOW_COLORS = {
        ChatColor.GOLD, ChatColor.RED, ChatColor.GREEN, ChatColor.AQUA,
        ChatColor.LIGHT_PURPLE, ChatColor.YELLOW, ChatColor.WHITE,
        ChatColor.DARK_RED, ChatColor.DARK_GREEN, ChatColor.DARK_AQUA,
        ChatColor.DARK_PURPLE, ChatColor.BLUE, ChatColor.GRAY
    };

    // Tipos de partículas disponibles (cross-version)
    private static final String[] PARTICLE_TYPES = {
        "VILLAGER_HAPPY", "HEART", "FLAME", "CLOUD",
        "SPELL_WITCH", "TOTEM", "SOUL", "DRIP_LAVA",
        "ENCHANTMENT_TABLE", "REDSTONE"
    };

    public BagConfigGUI(BoxTopPlugin plugin, DataManager dataManager, GlowManager glowManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.glowManager = glowManager;
    }

    // -------------------------------------------------------
    // ABRIR GUI
    // -------------------------------------------------------

    public void open(Player player, String bagName) {
        BagData bag = dataManager.getBag(bagName);
        if (bag == null) {
            player.sendMessage(ChatColor.RED + "Bag not found: " + bagName);
            return;
        }

        String title = GUI_TITLE_PREFIX + bagName;
        Inventory inv = Bukkit.createInventory(null, 54, title);

        populate(inv, bag);
        player.openInventory(inv);
    }

    // -------------------------------------------------------
    // POBLAR INVENTARIO
    // -------------------------------------------------------

    /**
     * Construye el inventario completo a partir del estado actual del BagData.
     * Se llama también al hacer clic para refrescar el GUI sin cerrarlo.
     */
    public void populate(Inventory inv, BagData bag) {
        inv.clear();

        // --- BORDES DECORATIVOS ---
        ItemStack border = makeBorder();
        for (int i = 45; i < 54; i++) inv.setItem(i, border); // fila 6 (borde inferior)
        inv.setItem(0, border);
        inv.setItem(8, border);
        inv.setItem(9, border);
        inv.setItem(17, border);
        inv.setItem(18, border);
        inv.setItem(26, border);
        inv.setItem(27, border);
        inv.setItem(35, border);
        inv.setItem(36, border);
        inv.setItem(44, border);

        // --- SLOT 1: INFO DEL SACO ---
        inv.setItem(1, makeInfo(bag));

        // ===== FILA 1: GLOW =====
        // Slot 10: Toggle Glow
        inv.setItem(10, makeToggle(
            "✦ Glow Effect",
            bag.isGlowEnabled(),
            "Enable/disable the glow outline",
            "ACTION:TOGGLE_GLOW"
        ));

        // Slot 11: Color anterior
        inv.setItem(11, makeArrow(false, "Glow Color", "ACTION:GLOW_COLOR_PREV"));

        // Slot 12: Color actual
        inv.setItem(12, makeColorDisplay(bag.getGlowColor()));

        // Slot 13: Color siguiente
        inv.setItem(13, makeArrow(true, "Glow Color", "ACTION:GLOW_COLOR_NEXT"));

        // ===== FILA 2: FÍSICAS =====
        // Slot 19: Toggle Physics
        inv.setItem(19, makeToggle(
            "⚡ Physics",
            bag.isPhysicsEnabled(),
            "Enable/disable bag knockback",
            "ACTION:TOGGLE_PHYSICS"
        ));

        // Slot 20-21: Knockback strength
        inv.setItem(20, makeArrow(false, "Knockback", "ACTION:KB_DOWN"));
        inv.setItem(21, makeValueDisplay(
            "Knockback Strength",
            String.format("%.2f", bag.getKnockbackStrength()),
            XMaterial.FEATHER
        ));
        inv.setItem(22, makeArrow(true, "Knockback", "ACTION:KB_UP"));

        // Slot 23-24: Vertical strength
        inv.setItem(23, makeArrow(false, "Vertical", "ACTION:VT_DOWN"));
        inv.setItem(24, makeValueDisplay(
            "Vertical Strength",
            String.format("%.2f", bag.getVerticalStrength()),
            XMaterial.ARROW
        ));
        inv.setItem(25, makeArrow(true, "Vertical", "ACTION:VT_UP"));

        // ===== FILA 3: PARTÍCULAS =====
        // Slot 28: Toggle Particles
        inv.setItem(28, makeToggle(
            "✶ Particles",
            bag.isParticlesEnabled(),
            "Enable/disable particle effects",
            "ACTION:TOGGLE_PARTICLES"
        ));

        // Slot 29-31: Particle type
        inv.setItem(29, makeArrow(false, "Particle Type", "ACTION:PARTICLE_PREV"));
        inv.setItem(30, makeValueDisplay(
            "Particle Type",
            formatParticleName(bag.getParticleType()),
            XMaterial.BLAZE_POWDER
        ));
        inv.setItem(31, makeArrow(true, "Particle Type", "ACTION:PARTICLE_NEXT"));

        // ===== FILA 4: MATERIAL =====
        // Slot 37-39: Material del saco
        inv.setItem(37, makeArrow(false, "Bag Material", "ACTION:MATERIAL_PREV"));
        inv.setItem(38, makeMaterialDisplay(bag.getMaterial()));
        inv.setItem(39, makeArrow(true, "Bag Material", "ACTION:MATERIAL_NEXT"));

        // --- BOTÓN CERRAR ---
        inv.setItem(49, makeClose());
    }

    // -------------------------------------------------------
    // LÓGICA DE ACCIÓN (llamada desde el Listener)
    // -------------------------------------------------------

    /**
     * Procesa un clic en el GUI y actualiza el BagData.
     * Devuelve true si hubo un cambio que requiere guardar y refrescar.
     */
    public boolean handleClick(String action, BagData bag) {
        switch (action) {

            case "ACTION:TOGGLE_GLOW":
                bag.setGlowEnabled(!bag.isGlowEnabled());
                return true;

            case "ACTION:GLOW_COLOR_PREV":
                bag.setGlowColor(cyclePrev(GLOW_COLORS, bag.getGlowColor()));
                return true;

            case "ACTION:GLOW_COLOR_NEXT":
                bag.setGlowColor(cycleNext(GLOW_COLORS, bag.getGlowColor()));
                return true;

            case "ACTION:TOGGLE_PHYSICS":
                bag.setPhysicsEnabled(!bag.isPhysicsEnabled());
                return true;

            case "ACTION:KB_DOWN":
                bag.setKnockbackStrength(Math.max(0.0, round2(bag.getKnockbackStrength() - 0.05)));
                return true;

            case "ACTION:KB_UP":
                bag.setKnockbackStrength(Math.min(3.0, round2(bag.getKnockbackStrength() + 0.05)));
                return true;

            case "ACTION:VT_DOWN":
                bag.setVerticalStrength(Math.max(0.0, round2(bag.getVerticalStrength() - 0.05)));
                return true;

            case "ACTION:VT_UP":
                bag.setVerticalStrength(Math.min(2.0, round2(bag.getVerticalStrength() + 0.05)));
                return true;

            case "ACTION:TOGGLE_PARTICLES":
                bag.setParticlesEnabled(!bag.isParticlesEnabled());
                return true;

            case "ACTION:PARTICLE_PREV":
                bag.setParticleType(cyclePrevStr(PARTICLE_TYPES, bag.getParticleType()));
                return true;

            case "ACTION:PARTICLE_NEXT":
                bag.setParticleType(cycleNextStr(PARTICLE_TYPES, bag.getParticleType()));
                return true;

            case "ACTION:MATERIAL_PREV":
                bag.setMaterial(cyclePrevMaterial(bag.getMaterial()));
                return true;

            case "ACTION:MATERIAL_NEXT":
                bag.setMaterial(cycleNextMaterial(bag.getMaterial()));
                return true;

            default:
                return false;
        }
    }

    // -------------------------------------------------------
    // ITEM BUILDERS
    // -------------------------------------------------------

    private ItemStack makeBorder() {
        ItemStack item = XMaterial.GRAY_STAINED_GLASS_PANE.parseItem();
        if (item == null) item = new ItemStack(Material.GLASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeInfo(BagData bag) {
        ItemStack item = XMaterial.ARMOR_STAND.parseItem();
        if (item == null) item = new ItemStack(Material.STONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "✦ " + bag.getName().toUpperCase());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "UUID: " + ChatColor.DARK_GRAY + bag.getUuid().toString().substring(0, 8) + "...");
            lore.add(ChatColor.GRAY + "Material: " + ChatColor.YELLOW + bag.getMaterial().getDisplayName());
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Changes save automatically.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeToggle(String label, boolean enabled, String description, String action) {
        XMaterial mat = enabled ? XMaterial.LIME_DYE : XMaterial.GRAY_DYE;
        ItemStack item = mat.parseItem();
        if (item == null) item = new ItemStack(Material.STONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String status = enabled
                ? ChatColor.GREEN + "✔ ENABLED"
                : ChatColor.RED + "✘ DISABLED";
            meta.setDisplayName(ChatColor.WHITE + label + " " + status);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + description);
            lore.add("");
            lore.add(ChatColor.YELLOW + "► Click to toggle");
            lore.add(ChatColor.DARK_GRAY + action); // acción oculta en el lore — leída por el listener
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeArrow(boolean right, String label, String action) {
        XMaterial mat = right ? XMaterial.ARROW : XMaterial.SPECTRAL_ARROW;
        if (mat.parseItem() == null) mat = XMaterial.ARROW;
        ItemStack item = mat.parseItem();
        if (item == null) item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((right ? ChatColor.GREEN : ChatColor.RED) + (right ? "▶" : "◀") + " " + label);
            meta.setLore(Arrays.asList(
                ChatColor.YELLOW + "► Click to change",
                ChatColor.DARK_GRAY + action
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeColorDisplay(ChatColor color) {
        // Mapeamos colores ChatColor a bloques de lana para visualización
        XMaterial woolMat = chatColorToWool(color);
        ItemStack item = woolMat.parseItem();
        if (item == null) item = new ItemStack(Material.WHITE_WOOL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color + "● " + color.name());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Current glow color");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeValueDisplay(String label, String value, XMaterial mat) {
        ItemStack item = mat.parseItem();
        if (item == null) item = new ItemStack(Material.STONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.WHITE + label + ": " + ChatColor.YELLOW + value);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeMaterialDisplay(BagData.BagMaterial material) {
        XMaterial xmat;
        String description;
        switch (material) {
            case STONE:
                xmat = XMaterial.STONE;
                description = ChatColor.GRAY + "Heavy: barely moves, less damage";
                break;
            case WOOL:
                xmat = XMaterial.WHITE_WOOL;
                description = ChatColor.GRAY + "Soft: flies far, return damage";
                break;
            default: // SAND
                xmat = XMaterial.SAND;
                description = ChatColor.GRAY + "Default: balanced physics";
                break;
        }
        ItemStack item = xmat.parseItem();
        if (item == null) item = new ItemStack(Material.STONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "⬡ Material: " + ChatColor.WHITE + material.getDisplayName());
            meta.setLore(Arrays.asList(description, "", ChatColor.YELLOW + "► Use arrows to change"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeClose() {
        ItemStack item = XMaterial.BARRIER.parseItem();
        if (item == null) item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "✘ Close");
            meta.setLore(Arrays.asList(ChatColor.GRAY + "Close this menu"));
            item.setItemMeta(meta);
        }
        return item;
    }

    // -------------------------------------------------------
    // HELPERS DE CICLO
    // -------------------------------------------------------

    private ChatColor cycleNext(ChatColor[] arr, ChatColor current) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == current) return arr[(i + 1) % arr.length];
        }
        return arr[0];
    }

    private ChatColor cyclePrev(ChatColor[] arr, ChatColor current) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == current) return arr[(i - 1 + arr.length) % arr.length];
        }
        return arr[0];
    }

    private String cycleNextStr(String[] arr, String current) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(current)) return arr[(i + 1) % arr.length];
        }
        return arr[0];
    }

    private String cyclePrevStr(String[] arr, String current) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(current)) return arr[(i - 1 + arr.length) % arr.length];
        }
        return arr[0];
    }

    private BagData.BagMaterial cycleNextMaterial(BagData.BagMaterial current) {
        BagData.BagMaterial[] vals = BagData.BagMaterial.values();
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] == current) return vals[(i + 1) % vals.length];
        }
        return BagData.BagMaterial.SAND;
    }

    private BagData.BagMaterial cyclePrevMaterial(BagData.BagMaterial current) {
        BagData.BagMaterial[] vals = BagData.BagMaterial.values();
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] == current) return vals[(i - 1 + vals.length) % vals.length];
        }
        return BagData.BagMaterial.SAND;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String formatParticleName(String raw) {
        return raw.replace("_", " ").toLowerCase()
                  .substring(0, 1).toUpperCase()
               + raw.replace("_", " ").toLowerCase().substring(1);
    }

    // -------------------------------------------------------
    // MAPA ChatColor → Lana (para visualización del color de glow)
    // -------------------------------------------------------

    private XMaterial chatColorToWool(ChatColor color) {
        switch (color) {
            case RED:          return XMaterial.RED_WOOL;
            case DARK_RED:     return XMaterial.RED_WOOL;
            case GREEN:        return XMaterial.LIME_WOOL;
            case DARK_GREEN:   return XMaterial.GREEN_WOOL;
            case AQUA:         return XMaterial.LIGHT_BLUE_WOOL;
            case DARK_AQUA:    return XMaterial.CYAN_WOOL;
            case BLUE:         return XMaterial.BLUE_WOOL;
            case DARK_BLUE:    return XMaterial.BLUE_WOOL;
            case LIGHT_PURPLE: return XMaterial.MAGENTA_WOOL;
            case DARK_PURPLE:  return XMaterial.PURPLE_WOOL;
            case YELLOW:       return XMaterial.YELLOW_WOOL;
            case GOLD:         return XMaterial.ORANGE_WOOL;
            case WHITE:        return XMaterial.WHITE_WOOL;
            case GRAY:         return XMaterial.LIGHT_GRAY_WOOL;
            case DARK_GRAY:    return XMaterial.GRAY_WOOL;
            default:           return XMaterial.WHITE_WOOL;
        }
    }
}
