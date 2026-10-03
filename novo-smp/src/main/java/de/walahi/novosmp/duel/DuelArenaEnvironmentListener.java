package de.walahi.novosmp.duel;

import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/** Protects duel arenas from external world changes and unauthorized world access. */
final class DuelArenaEnvironmentListener implements Listener {
    private final DuelManager manager;

    DuelArenaEnvironmentListener(DuelManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFluidFlow(BlockFromToEvent event) {
        DuelMatch match = manager.matchAt(event.getBlock().getLocation());
        if (match == null) return;
        if (!match.running() || !match.map.contains(event.getToBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockSpread(BlockSpreadEvent event) {
        DuelMatch match = manager.matchAt(event.getSource().getLocation());
        if (match == null) return;
        if (!match.running() || !match.map.contains(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        DuelMatch match = manager.matchAt(event.getLocation());
        if (match == null) return;
        if (!match.running()) {
            event.setCancelled(true);
            return;
        }
        event.blockList().removeIf(block -> !match.map.contains(block.getLocation()));
        // Explosions dürfen die Arena optisch verändern, aber keine Map-Blöcke als Loot erzeugen.
        // Spieler-platzierte Blöcke werden dabei ebenfalls aus dem Tracking entfernt, weil sie zerstört sind.
        event.blockList().forEach(block -> match.forgetPlacedBlock(block.getLocation()));
        event.setYield(0F);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        DuelMatch match = manager.matchAt(event.getBlock().getLocation());
        if (match == null) return;
        if (!match.running()) {
            event.setCancelled(true);
            return;
        }
        event.blockList().removeIf(block -> !match.map.contains(block.getLocation()));
        event.blockList().forEach(block -> match.forgetPlacedBlock(block.getLocation()));
        event.setYield(0F);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDuelWorldMobSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof Mob && manager.isDuelWorld(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onExplosiveSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed)) return;
        DuelMatch match = manager.matchAt(event.getLocation());
        if (match != null && (!match.running() || !match.request.rules().tntAllowed())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        DuelMatch match = manager.match(player.getUniqueId());
        if (match == null) return;

        if (!match.running() || !match.map.contains(event.getEntity().getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity().getType() == EntityType.TNT_MINECART) {
            if (!match.request.rules().tntAllowed()) {
                event.setCancelled(true);
                return;
            }
            manager.rememberExplosiveOwner(event.getEntity().getUniqueId(), player.getUniqueId());
        }
        if (event.getEntity() instanceof EnderCrystal crystal) {
            if (!match.request.rules().crystalsAllowed()) event.setCancelled(true);
            else manager.rememberExplosiveOwner(crystal.getUniqueId(), player.getUniqueId());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        manager.ejectUnauthorizedDuelWorldPlayer(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.plugin().getServer().getScheduler().runTask(
                manager.plugin(), () -> manager.ejectUnauthorizedDuelWorldPlayer(event.getPlayer())
        );
    }
}
