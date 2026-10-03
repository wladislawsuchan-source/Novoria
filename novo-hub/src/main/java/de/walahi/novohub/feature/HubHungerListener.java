package de.walahi.novohub.feature;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Hält den Hub-Zustand für jeden Spieler zuverlässig auf den Config-Werten. */
public final class HubHungerListener implements Listener {
    private final SMPCorePlugin plugin;
    private final HubPlayerStateManager stateManager;

    public HubHungerListener(SMPCorePlugin plugin, HubPlayerStateManager stateManager) {
        this.plugin = plugin;
        this.stateManager = stateManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!stateManager.isHub(player)) return;
        if (!plugin.configs().menus().getBoolean("hub.disable-hunger", true)) return;
        event.setCancelled(true);
        stateManager.apply(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> stateManager.apply(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> stateManager.apply(event.getPlayer()), 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> stateManager.apply(event.getPlayer()));
    }
}
