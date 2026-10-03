package de.walahi.smpcore.commands;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.GameMode;

public final class BuildActivityListener implements Listener {
    private final BuildModeManager manager;
    public BuildActivityListener(BuildModeManager manager) { this.manager = manager; }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (manager.isActive(event.getPlayer()) && manager.isWorldAllowed(event.getPlayer())) {
            event.setCancelled(false);
            manager.ensureBuildState(event.getPlayer());
            manager.markActivity(event.getPlayer());
        }
    }
    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (manager.isActive(event.getPlayer()) && manager.isWorldAllowed(event.getPlayer())) {
            event.setCancelled(false);
            manager.ensureBuildState(event.getPlayer());
            manager.markActivity(event.getPlayer());
        }
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null && (event.getFrom().getBlockX()!=event.getTo().getBlockX() || event.getFrom().getBlockY()!=event.getTo().getBlockY() || event.getFrom().getBlockZ()!=event.getTo().getBlockZ())) manager.markActivity(event.getPlayer());
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent event) { manager.disableOnQuit(event.getPlayer()); }
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (!manager.isActive(event.getPlayer())) return;
        if (!manager.isWorldAllowed(event.getPlayer())) {
            manager.disable(event.getPlayer(), null, true, "world-change");
            return;
        }
        manager.ensureBuildState(event.getPlayer());
    }
}
