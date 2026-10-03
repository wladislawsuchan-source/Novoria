package de.walahi.novosmp.duel;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.Locale;

/** Enforces movement, inventory, command and item restrictions during active duels. */
final class DuelRestrictionListener implements Listener {
    private final DuelManager manager;

    DuelRestrictionListener(DuelManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        DuelMatch match = manager.match(player.getUniqueId());
        if (match == null || event.getTo() == null) return;

        boolean changedPosition = event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ();
        if (!changedPosition) return;

        if (match.state == DuelMatch.State.COUNTDOWN) {
            Location anchor = match.countdownAnchors.get(player.getUniqueId());
            Location locked = anchor == null ? event.getFrom().clone() : anchor.clone();
            locked.setYaw(event.getTo().getYaw());
            locked.setPitch(event.getTo().getPitch());
            event.setTo(locked);
            player.setVelocity(new Vector(0D, 0D, 0D));
            player.setFallDistance(0F);
            manager.warnArenaLeave(player);
            return;
        }

        if (match.state == DuelMatch.State.RUNNING && !match.map.contains(event.getTo())) {
            event.setTo(event.getFrom());
            manager.warnArenaLeave(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        DuelMatch match = manager.match(player.getUniqueId());
        if (match == null || DuelTeleportRegistry.isAllowed(player.getUniqueId())) return;

        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        boolean duelItemTeleport = cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT;
        if (duelItemTeleport && match.running() && event.getTo() != null && match.map.contains(event.getTo())) return;

        event.setCancelled(true);
        if (match.state != DuelMatch.State.ENDING && match.state != DuelMatch.State.FINISHED) {
            manager.warnArenaLeave(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        DuelMatch match = manager.match(event.getPlayer().getUniqueId());
        if (match != null && !match.running()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        DuelMatch match = manager.match(event.getPlayer().getUniqueId());
        if (match != null && !match.running()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        ProjectileSource shooter = event.getEntity().getShooter();
        if (!(shooter instanceof Player player)) return;
        DuelMatch match = manager.match(player.getUniqueId());
        if (match != null && !match.running()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        DuelMatch match = manager.match(player.getUniqueId());
        if (match == null) return;

        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.PLAYER) return;
        event.setCancelled(true);
        manager.sendMessage(player, manager.config().message(
                "restrictions.inventory", "<red>Externe Inventare sind während eines Duells gesperrt.</red>"
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        DuelMatch match = manager.match(player.getUniqueId());
        if (match != null && match.state == DuelMatch.State.ENDING) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        DuelMatch match = manager.match(player.getUniqueId());
        if (match != null && match.state == DuelMatch.State.ENDING) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!manager.inDuel(event.getPlayer().getUniqueId())) return;

        String command = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (manager.config().allowedCommands().stream().anyMatch(allowed ->
                command.equals(allowed) || command.startsWith(allowed + " "))) return;

        event.setCancelled(true);
        manager.sendMessage(event.getPlayer(), manager.config().message(
                "restrictions.command", "<red>Während eines Duells sind nur die konfigurierten Duellbefehle erlaubt.</red>"
        ));
        manager.sounds().play(event.getPlayer(), "blocked-action");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (manager.inDuel(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        DuelMatch match = manager.match(player.getUniqueId());
        if (match != null && !match.running()) event.setCancelled(true);
    }

    /**
     * XP-Flaschen im Duell sind für Mending gedacht und dürfen keine permanent nutzbare
     * Spieler-XP erzeugen. Paper feuert PlayerItemMendEvent direkt vor PlayerExpChangeEvent;
     * dadurch bleibt die Mending-Reparatur erhalten und nur die verbleibende XP-Gutschrift
     * an den Spieler wird auf 0 gesetzt. Andere natürliche XP-Quellen bleiben unangetastet.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBottleExperience(PlayerExpChangeEvent event) {
        DuelMatch match = manager.match(event.getPlayer().getUniqueId());
        if (match == null) return;
        if (!(event.getSource() instanceof ExperienceOrb orb)) return;
        if (orb.getSpawnReason() != ExperienceOrb.SpawnReason.EXP_BOTTLE) return;
        event.setAmount(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        DuelMatch match = manager.match(event.getPlayer().getUniqueId());
        if (match == null) return;
        if (!match.running() || !match.map.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (event.isCancelled()) return;
        // Arena-Bestand darf zerstört werden, aber niemals Items erzeugen. Nur Blöcke,
        // die in genau diesem Match von einem Spieler gesetzt wurden, dürfen droppen.
        event.setDropItems(match.consumePlacedBlock(event.getBlock().getLocation()));
        event.setExpToDrop(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        DuelMatch match = manager.match(event.getPlayer().getUniqueId());
        if (match == null) return;
        if (!match.running() || !match.map.contains(event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
            return;
        }
        Material type = event.getBlockPlaced().getType();
        if (type == Material.TNT && !match.request.rules().tntAllowed()) {
            event.setCancelled(true);
            return;
        }
        if (event.isCancelled()) return;

        // Multi-Block-Platzierungen (z. B. Türen/Betten) vollständig erfassen.
        if (event instanceof BlockMultiPlaceEvent multi) {
            multi.getReplacedBlockStates().forEach(state -> match.rememberPlacedBlock(state.getLocation()));
        } else {
            match.rememberPlacedBlock(event.getBlockPlaced().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        DuelMatch match = manager.match(event.getPlayer().getUniqueId());
        if (match == null) return;
        if (!match.running() || !match.map.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (!event.isCancelled()) match.rememberPlacedBlock(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        DuelMatch match = manager.match(event.getPlayer().getUniqueId());
        if (match == null) return;
        if (!match.running() || !match.map.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (event.isCancelled()) return;

        // Wasser/Lava aus der Arena ist ebenfalls Map-Ressource. Nur Flüssigkeit, die in
        // diesem Match selbst gesetzt wurde, darf wieder in einen Eimer aufgenommen werden.
        if (!match.consumePlacedBlock(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        DuelMatch match = manager.match(player.getUniqueId());
        if (match == null) return;
        if (!match.running() || !match.map.contains(event.getBlock().getLocation())) event.setCancelled(true);
    }
}
