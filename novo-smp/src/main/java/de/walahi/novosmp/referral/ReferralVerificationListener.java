package de.walahi.novosmp.referral;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Prüft vorgemerkte Referrals ohne DB-Abfrage bei jedem normalen Gameplay-Event. */
public final class ReferralVerificationListener implements Listener {
    private final Plugin plugin;
    private final ReferralManager manager;
    private final Set<UUID> scheduled = ConcurrentHashMap.newKeySet();

    public ReferralVerificationListener(Plugin plugin, ReferralManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> manager.trackPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        scheduled.remove(uuid);
        manager.untrackPlayer(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        scheduleCheck(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobKill(EntityDeathEvent event) {
        if (event.getEntity().hasMetadata("novosmp-combat-dummy")
                || event.getEntity().hasMetadata("novosmp-finalized-combat-death")) return;
        Player killer = event.getEntity().getKiller();
        if (killer != null) scheduleCheck(killer);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        scheduleCheck(event.getPlayer());
    }

    private void scheduleCheck(Player player) {
        UUID uuid = player.getUniqueId();
        if (!manager.isPending(uuid) || !scheduled.add(uuid)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            scheduled.remove(uuid);
            if (player.isOnline()) manager.tryVerify(player);
        });
    }
}
