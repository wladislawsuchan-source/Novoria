package de.walahi.novosmp.sit;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** One non-persistent marker seat per seated player; all cleanup is event-driven. */
public final class SitManager implements Listener {
    public enum Result { SAT, STOOD, COMBAT, INVALID }

    private enum StopReason {
        SHIFT(false, true, true), TOGGLE(true, true, false), DAMAGE(true, true, false),
        SEAT_REMOVED(true, true, false), CANCEL(true, false, false);

        final boolean dismount;
        final boolean restore;
        final boolean notify;

        StopReason(boolean dismount, boolean restore, boolean notify) {
            this.dismount = dismount;
            this.restore = restore;
            this.notify = notify;
        }
    }

    private record Surface(Block block, double y) { }
    private record Session(UUID playerId, ArmorStand seat, Block support, Location stand, double surfaceY) { }

    private final NovoSMPPlugin plugin;
    private final NamespacedKey seatKey;
    private final Map<UUID, Session> byPlayer = new HashMap<>();
    private final Map<UUID, UUID> bySeat = new HashMap<>();
    private final Map<UUID, Session> pendingRestore = new HashMap<>();

    public SitManager(NovoSMPPlugin plugin) {
        this.plugin = plugin;
        this.seatKey = new NamespacedKey(plugin, "sit_seat");
    }

    /** Recognized before CreatureSpawnEvent because the spawn consumer sets the PDC first. */
    public static boolean isTechnicalSeat(SMPCorePlugin plugin, Entity entity) {
        if (!(entity instanceof ArmorStand stand)) return false;
        Byte marker = stand.getPersistentDataContainer().get(
                new NamespacedKey(plugin, "sit_seat"), PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1 && stand.isMarker()
                && !stand.isVisible() && !stand.hasGravity() && !stand.isPersistent()
                && stand.isInvulnerable() && stand.isSilent() && !stand.isCollidable();
    }

    public Result toggle(Player player) {
        UUID playerId = player.getUniqueId();
        if (pendingRestore.containsKey(playerId)) return Result.INVALID;
        if (byPlayer.containsKey(playerId)) {
            stop(playerId, StopReason.TOGGLE);
            return Result.STOOD;
        }
        if (plugin.combatManager() != null && plugin.combatManager().isInCombat(playerId))
            return Result.COMBAT;
        if (!canStart(player)) return Result.INVALID;
        Location feet = player.getLocation();
        Surface surface = surfaceAt(feet);
        if (surface == null || !clearStandingSpace(player, feet, surface.y())) return Result.INVALID;

        Location stand = feet.clone();
        stand.setY(surface.y());
        ArmorStand seat = null;
        try {
            seat = player.getWorld().spawn(stand, ArmorStand.class, armorStand -> {
                armorStand.setVisible(false);
                armorStand.setMarker(true);
                armorStand.setGravity(false);
                armorStand.setInvulnerable(true);
                armorStand.setSilent(true);
                armorStand.setPersistent(false);
                armorStand.setCollidable(false);
                armorStand.setBasePlate(false);
                armorStand.setCanPickupItems(false);
                armorStand.getPersistentDataContainer().set(seatKey, PersistentDataType.BYTE, (byte) 1);
            });
            if (!seat.isValid()) return Result.INVALID;
            if (!seat.addPassenger(player)) {
                seat.remove();
                return Result.INVALID;
            }
            Session session = new Session(playerId, seat, surface.block(), stand, surface.y());
            byPlayer.put(playerId, session);
            bySeat.put(seat.getUniqueId(), playerId);
            return Result.SAT;
        } catch (RuntimeException exception) {
            if (seat != null && seat.isValid()) seat.remove();
            plugin.getLogger().log(Level.WARNING, "Sitz konnte nicht erstellt werden", exception);
            return Result.INVALID;
        }
    }

    private boolean canStart(Player player) {
        return player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                && player.getVehicle() == null && !player.isFlying() && !player.isGliding()
                && !player.isSwimming() && !player.isInWater() && !player.isInLava()
                && player.isOnGround();
    }

    private Surface surfaceAt(Location feet) {
        World world = feet.getWorld();
        if (world == null) return null;
        int x = feet.getBlockX();
        int z = feet.getBlockZ();
        int top = feet.getBlockY();
        Surface best = null;
        for (int y = top; y >= Math.max(world.getMinHeight(), top - 2); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.isLiquid() || isHazard(block.getType())) continue;
            double candidate = SitGeometry.surfaceAt(block.getCollisionShape().getBoundingBoxes(),
                    x, y, z, feet.getX(), feet.getY(), feet.getZ());
            if (Double.isNaN(candidate)) continue;
            if (best == null || candidate > best.y()) best = new Surface(block, candidate);
        }
        return best;
    }

    private boolean clearStandingSpace(Player player, Location feet, double surfaceY) {
        World world = feet.getWorld();
        if (world == null) return false;
        BoundingBox current = player.getBoundingBox();
        double halfX = Math.max(0.3, current.getWidthX() / 2.0);
        double halfZ = Math.max(0.3, current.getWidthZ() / 2.0);
        BoundingBox body = new BoundingBox(feet.getX() - halfX, surfaceY, feet.getZ() - halfZ,
                feet.getX() + halfX, surfaceY + Math.max(1.8, current.getHeight()), feet.getZ() + halfZ);
        for (int x = (int) Math.floor(body.getMinX()); x <= (int) Math.floor(body.getMaxX()); x++) {
            for (int y = (int) Math.floor(body.getMinY()); y <= (int) Math.floor(body.getMaxY()); y++) {
                for (int z = (int) Math.floor(body.getMinZ()); z <= (int) Math.floor(body.getMaxZ()); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isLiquid() || isHazard(block.getType())) return false;
                    if (SitGeometry.collides(body, block.getCollisionShape().getBoundingBoxes(), x, y, z))
                        return false;
                }
            }
        }
        return true;
    }

    private boolean isHazard(Material type) {
        return type == Material.FIRE || type == Material.SOUL_FIRE || type == Material.LAVA;
    }

    private void stop(UUID playerId, StopReason reason) {
        Session session = byPlayer.remove(playerId);
        if (session == null) return;
        bySeat.remove(session.seat().getUniqueId());
        if (reason.dismount) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.getVehicle() == session.seat()) player.leaveVehicle();
        }
        if (session.seat().isValid()) session.seat().remove();
        if (reason.notify) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) plugin.messages().sendConfiguredAuto(player, "sit.messages.stood",
                    "<gray>Du bist wieder aufgestanden.</gray>");
        }
        if (reason.restore) {
            pendingRestore.put(playerId, session);
            Bukkit.getScheduler().runTask(plugin, () -> restore(playerId, session, reason));
        }
    }

    private void restore(UUID playerId, Session session, StopReason reason) {
        if (pendingRestore.remove(playerId) != session) return;
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline() || player.isDead() || player.getVehicle() != null
                || player.getWorld() != session.stand().getWorld()) return;
        // A hit may already have imparted knockback. Never pull the victim back
        // horizontally; only correct the dismount height at the current X/Z.
        Location target = reason == StopReason.DAMAGE ? player.getLocation().clone() : session.stand().clone();
        if (reason == StopReason.DAMAGE) target.setY(session.surfaceY());
        Surface current = surfaceAt(target);
        if (current == null || current.block().getWorld() != session.support().getWorld()
                || current.block().getX() != session.support().getX()
                || current.block().getY() != session.support().getY()
                || current.block().getZ() != session.support().getZ()
                || Math.abs(current.y() - session.surfaceY()) > 0.02
                || !clearStandingSpace(player, target, session.surfaceY())) return;
        Vector velocity = reason == StopReason.DAMAGE ? player.getVelocity() : null;
        if (player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN) && velocity != null)
            player.setVelocity(velocity);
    }

    public void shutdown() {
        pendingRestore.clear();
        for (UUID playerId : List.copyOf(byPlayer.keySet())) stop(playerId, StopReason.CANCEL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID owner = bySeat.get(event.getDismounted().getUniqueId());
        if (owner != null && owner.equals(player.getUniqueId()))
            stop(owner, StopReason.SHIFT);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.DISMOUNT) {
            // Paper may report the dismount position here even if a preceding
            // EntityDismountEvent did not clear our session. stop() is idempotent.
            stop(playerId, StopReason.SHIFT);
            return;
        }
        pendingRestore.remove(playerId);
        stop(playerId, StopReason.CANCEL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        pendingRestore.remove(playerId);
        stop(playerId, StopReason.CANCEL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        pendingRestore.remove(playerId);
        stop(playerId, StopReason.CANCEL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        pendingRestore.remove(playerId);
        stop(playerId, StopReason.CANCEL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        pendingRestore.remove(playerId);
        stop(playerId, StopReason.CANCEL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!byPlayer.isEmpty() && event.getEntity() instanceof Player player && event.getDamage() > 0D)
            stop(player.getUniqueId(), StopReason.DAMAGE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSeatRemoved(EntityRemoveFromWorldEvent event) {
        if (bySeat.isEmpty()) return;
        UUID owner = bySeat.get(event.getEntity().getUniqueId());
        if (owner != null) stop(owner, StopReason.SEAT_REMOVED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSupportBroken(BlockBreakEvent event) {
        if (!byPlayer.isEmpty()) stopOnSupportDestroyed(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) { stopOnSupportDestroyed(event.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) { stopOnSupportDestroyed(event.blockList()); }

    private void stopOnSupportDestroyed(List<Block> destroyed) {
        if (byPlayer.isEmpty() || destroyed.isEmpty()) return;
        List<UUID> affected = new ArrayList<>();
        for (Session session : byPlayer.values())
            if (destroyed.contains(session.support())) affected.add(session.playerId());
        for (UUID playerId : affected) stop(playerId, StopReason.CANCEL);
    }
}
