package fun.gexel.boxtop.managers;

import com.cryptomorin.xseries.XSound;
import fun.gexel.boxtop.BoxTopPlugin;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DuelManager {

    private final BoxTopPlugin plugin;
    private final DataManager dataManager;
    private final GlowManager glowManager;
    private final SoundManager soundManager;

    private final Map<UUID, UUID> pendingInvites = new HashMap<>();
    private final Map<UUID, UUID> inviteBag = new HashMap<>();
    private final Map<UUID, DuelSession> activeDuels = new HashMap<>();

    private Particle totemParticle = null;

    public DuelManager(BoxTopPlugin plugin, DataManager dataManager, GlowManager glowManager, SoundManager soundManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.glowManager = glowManager;
        this.soundManager = soundManager;
        setupParticles();
    }

    private void setupParticles() {
        try {
            totemParticle = Particle.valueOf("TOTEM");
        } catch (IllegalArgumentException e) {
            try {
                totemParticle = Particle.valueOf("TOTEM_OF_UNDYING");
            } catch (IllegalArgumentException ignored) {
                totemParticle = null;
            }
        }
    }

    public void sendInvite(Player sender, Player target, UUID bagUUID) {
        if (pendingInvites.containsKey(target.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "That player already has a pending invitation.");
            return;
        }

        if (activeDuels.containsKey(bagUUID)) {
            sender.sendMessage(dataManager.getMessage("duels.messages.bag-busy"));
            return;
        }

        pendingInvites.put(target.getUniqueId(), sender.getUniqueId());
        inviteBag.put(target.getUniqueId(), bagUUID);

        sender.sendMessage(ChatColor.GREEN + "Duel invitation sent to " + target.getName() + "!");
        
        // Enviar formato de invitación línea por línea
        for (String line : dataManager.getStringList("duels.invite-format")) {
            String msg = line.replace("{player}", sender.getName());
            
            if (msg.contains("[CLICK")) {
                // INTENTO DE MENSAJE CLICKEABLE (Funciona en 1.12+)
                try {
                    TextComponent component = new TextComponent(ChatColor.translateAlternateColorCodes('&', msg));
                    component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/boxtop accept " + sender.getName()));
                    component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(ChatColor.GREEN + "Click to accept duel!").create()));
                    target.spigot().sendMessage(component);
                } catch (Exception e) {
                    // Si falla el componente avanzado, enviamos texto plano
                    target.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                }

                // MENSAJE DE AYUDA (EXTRAIDO DE CONFIG)
                String hint = dataManager.getRawMessage("duels.messages.invite-hint").replace("{player}", sender.getName());
                target.sendMessage(ChatColor.translateAlternateColorCodes('&', hint));
                
            } else {
                target.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            }
        }

        XSound.ENTITY_EXPERIENCE_ORB_PICKUP.play(target);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (pendingInvites.containsKey(target.getUniqueId()) && 
                    pendingInvites.get(target.getUniqueId()).equals(sender.getUniqueId())) {
                    
                    pendingInvites.remove(target.getUniqueId());
                    inviteBag.remove(target.getUniqueId());
                    if (sender.isOnline()) sender.sendMessage(ChatColor.YELLOW + "Invitation expired.");
                    if (target.isOnline()) target.sendMessage(ChatColor.YELLOW + "Invitation from " + sender.getName() + " expired.");
                }
            }
        }.runTaskLater(plugin, 600L);
    }

    public boolean hasInvite(Player target, Player sender) {
        return pendingInvites.containsKey(target.getUniqueId()) && 
               pendingInvites.get(target.getUniqueId()).equals(sender.getUniqueId());
    }

    public void acceptInvite(Player acceptor, Player inviter) {
        if (!pendingInvites.containsKey(acceptor.getUniqueId()) || 
            !pendingInvites.get(acceptor.getUniqueId()).equals(inviter.getUniqueId())) {
            acceptor.sendMessage(ChatColor.RED + "You don't have an invitation from that player.");
            return;
        }

        UUID bagUUID = inviteBag.get(acceptor.getUniqueId());
        pendingInvites.remove(acceptor.getUniqueId());
        inviteBag.remove(acceptor.getUniqueId());

        if (activeDuels.containsKey(bagUUID)) {
            acceptor.sendMessage(dataManager.getMessage("duels.messages.bag-busy"));
            return;
        }

        startDuel(inviter, acceptor, bagUUID);
    }

    private void startDuel(Player p1, Player p2, UUID bagUUID) {
        int duration = plugin.getConfig().getInt("duels.duration", 30);
        
        String title = dataManager.getRawMessage("duels.messages.bossbar-format")
                .replace("{p1}", p1.getName()).replace("{d1}", "0")
                .replace("{p2}", p2.getName()).replace("{d2}", "0")
                .replace("{time}", String.valueOf(duration));

        BossBar bossBar = Bukkit.createBossBar(title, BarColor.BLUE, BarStyle.SOLID);
        bossBar.addPlayer(p1);
        bossBar.addPlayer(p2);

        DuelSession session = new DuelSession(p1.getUniqueId(), p2.getUniqueId(), bossBar, duration);
        activeDuels.put(bagUUID, session);

        String startMsg = dataManager.getMessage("duels.messages.duel-started");
        p1.sendMessage(startMsg);
        p2.sendMessage(startMsg);

        XSound.ENTITY_WITHER_SPAWN.play(p1);
        XSound.ENTITY_WITHER_SPAWN.play(p2);

        session.taskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (!activeDuels.containsKey(bagUUID)) {
                    this.cancel();
                    return;
                }

                if (session.currentTime <= 0) {
                    endDuel(bagUUID, false);
                    this.cancel();
                    return;
                }

                updateBossBar(session);

                if (session.currentTime <= 5) {
                    XSound.BLOCK_NOTE_BLOCK_PLING.play(p1, 2.0f, 2.0f);
                    XSound.BLOCK_NOTE_BLOCK_PLING.play(p2, 2.0f, 2.0f);
                }

                session.currentTime--;
            }
        }.runTaskTimer(plugin, 0L, 20L).getTaskId();
    }

    public void addDuelDamage(UUID bagUUID, Player player, double damage) {
        DuelSession session = activeDuels.get(bagUUID);
        if (session == null) return;

        if (player.getUniqueId().equals(session.player1)) {
            session.damage1 += damage;
        } else if (player.getUniqueId().equals(session.player2)) {
            session.damage2 += damage;
        }
        
        updateBossBar(session);
    }

    private void updateBossBar(DuelSession session) {
        if (session.bossBar == null) return;
        
        Player p1 = Bukkit.getPlayer(session.player1);
        Player p2 = Bukkit.getPlayer(session.player2);
        String p1Name = (p1 != null) ? p1.getName() : "Unknown";
        String p2Name = (p2 != null) ? p2.getName() : "Unknown";

        String format = dataManager.getRawMessage("duels.messages.bossbar-format");
        
        String title = format
                .replace("{p1}", p1Name)
                .replace("{d1}", String.format("%.0f", session.damage1))
                .replace("{p2}", p2Name)
                .replace("{d2}", String.format("%.0f", session.damage2))
                .replace("{time}", String.valueOf(session.currentTime));
        
        double progress = (double) session.currentTime / session.maxDuration;
        if (progress < 0.0) progress = 0.0;
        if (progress > 1.0) progress = 1.0;
        
        session.bossBar.setProgress(progress);
        session.bossBar.setTitle(ChatColor.translateAlternateColorCodes('&', title));
    }

    public void endDuel(UUID bagUUID, boolean forceCancel) {
        DuelSession session = activeDuels.remove(bagUUID);
        if (session == null) return;

        Bukkit.getScheduler().cancelTask(session.taskId);
        session.bossBar.removeAll();

        if (forceCancel) return;

        Player p1 = Bukkit.getPlayer(session.player1);
        Player p2 = Bukkit.getPlayer(session.player2);

        if (p1 == null || p2 == null) return;

        announceWinner(p1, p2, session);
    }

    private void announceWinner(Player p1, Player p2, DuelSession session) {
        String winnerMsg = dataManager.getMessage("duels.messages.duel-ended-winner");
        String tieMsg = dataManager.getMessage("duels.messages.duel-ended-tie");

        if (session.damage1 > session.damage2) {
            Bukkit.broadcastMessage(winnerMsg.replace("{winner}", p1.getName()).replace("{damage}", String.format("%.0f", session.damage1)));
            spawnTotemEffect(p1);
            soundManager.playLevelUpSound(p1);
        } else if (session.damage2 > session.damage1) {
            Bukkit.broadcastMessage(winnerMsg.replace("{winner}", p2.getName()).replace("{damage}", String.format("%.0f", session.damage2)));
            spawnTotemEffect(p2);
            soundManager.playLevelUpSound(p2);
        } else {
            Bukkit.broadcastMessage(tieMsg.replace("{damage}", String.format("%.0f", session.damage1)));
            XSound.ENTITY_VILLAGER_NO.play(p1);
            XSound.ENTITY_VILLAGER_NO.play(p2);
        }
    }
    
    private void spawnTotemEffect(Player p) {
        if (totemParticle != null) {
            try {
                p.getWorld().spawnParticle(totemParticle, p.getLocation().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.1);
            } catch (Exception ignored) {}
        }
    }

    public boolean isBagInDuel(UUID bagUUID) {
        return activeDuels.containsKey(bagUUID);
    }

    public boolean isPlayerInDuel(UUID bagUUID, Player player) {
        DuelSession session = activeDuels.get(bagUUID);
        if (session == null) return false;
        return session.player1.equals(player.getUniqueId()) || session.player2.equals(player.getUniqueId());
    }

    private static class DuelSession {
        UUID player1;
        UUID player2;
        double damage1 = 0;
        double damage2 = 0;
        BossBar bossBar;
        int taskId;
        int maxDuration;
        int currentTime;

        public DuelSession(UUID p1, UUID p2, BossBar bb, int duration) {
            this.player1 = p1;
            this.player2 = p2;
            this.bossBar = bb;
            this.maxDuration = duration;
            this.currentTime = duration;
        }
    }
}