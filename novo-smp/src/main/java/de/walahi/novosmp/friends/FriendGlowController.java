package de.walahi.novosmp.friends;

import de.walahi.novosmp.NovoSMPPlugin;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Computes viewer-specific glow state and sends packets only when that state changes. */
public final class FriendGlowController implements Listener {
    private record GlowKey(UUID viewer, UUID target) { }

    private final NovoSMPPlugin plugin;
    private final FriendConfiguration config;
    private final FriendStateCache cache;
    private final ViewerGlowService packetService;
    private final Predicate<Player> vanished;
    private final BiPredicate<Player, Player> suppressed;
    private final Set<GlowKey> adminGlow = ConcurrentHashMap.newKeySet();
    private final Set<UUID> queuedResyncs = ConcurrentHashMap.newKeySet();
    private final Map<GlowKey, Boolean> applied = new ConcurrentHashMap<>();
    private BukkitTask refreshTask;
    private BiPredicate<Player, Player> clanGlow = (viewer, target) -> false;

    public FriendGlowController(NovoSMPPlugin plugin, FriendConfiguration config, FriendStateCache cache,
                                Predicate<Player> vanished, BiPredicate<Player, Player> suppressed) {
        this.plugin = plugin;
        this.config = config;
        this.cache = cache;
        this.vanished = vanished;
        this.suppressed = suppressed;
        this.packetService = new ViewerGlowService(plugin, this::shouldGlow);
    }

    public void start() {
        long period = Math.max(10L, config.longValue("settings.glow-refresh-ticks", 20L));
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 10L, period);
    }

    public void stop() {
        if (refreshTask != null) refreshTask.cancel();
        refreshTask = null;
        adminGlow.clear();
        queuedResyncs.clear();
        applied.clear();
        packetService.shutdown();
    }

    public boolean hasAdminGlow(UUID viewer, UUID target) {
        return adminGlow.contains(new GlowKey(viewer, target));
    }

    public boolean toggleAdminGlow(UUID viewer, UUID target) {
        GlowKey key = new GlowKey(viewer, target);
        boolean enabled;
        if (adminGlow.remove(key)) enabled = false;
        else {
            adminGlow.add(key);
            enabled = true;
        }
        refreshAll();
        return enabled;
    }

    public void refreshLater(long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshAll, Math.max(0L, delay));
    }

    public void setClanGlow(BiPredicate<Player, Player> clanGlow) {
        this.clanGlow = clanGlow == null ? (viewer, target) -> false : clanGlow;
        refreshAll();
    }

    private void resyncPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        invalidateAppliedState(playerId);
        if (!queuedResyncs.add(playerId)) return;

        // A teleport can rebuild the tracked player entity for nearby viewers. Refresh
        // the moved player in both viewer directions after one tick and once more after
        // entity tracking has settled, without recalculating every unrelated player pair.
        refreshPlayerLater(playerId, 1L, false);
        refreshPlayerLater(playerId, 10L, true);
    }

    private void refreshPlayerLater(UUID playerId, long delay, boolean finishResync) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                if (finishResync) invalidateAppliedState(playerId);
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) refreshPlayer(player);
            } finally {
                if (finishResync) queuedResyncs.remove(playerId);
            }
        }, Math.max(0L, delay));
    }

    private void invalidateAppliedState(UUID playerId) {
        applied.keySet().removeIf(key -> key.viewer().equals(playerId) || key.target().equals(playerId));
    }

    private void refreshPlayer(Player player) {
        java.util.List<Player> online = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
        if (!cache.preloadOnline(online.stream().map(Player::getUniqueId).toList())) return;

        for (Player other : online) {
            if (other.getUniqueId().equals(player.getUniqueId())) continue;
            try {
                apply(player, other, shouldGlow(player, other));
                apply(other, player, shouldGlow(other, player));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Freundes-Glow konnte nach einem Teleport nicht synchronisiert werden: "
                        + exception.getMessage());
            }
        }
    }

    public void refreshAll() {
        java.util.List<Player> online = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
        if (!cache.preloadOnline(online.stream().map(Player::getUniqueId).toList())) return;
        for (Player viewer : online) {
            for (Player target : online) {
                if (viewer.getUniqueId().equals(target.getUniqueId())) continue;
                try {
                    apply(viewer, target, shouldGlow(viewer, target));
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Freundes-Glow konnte nicht berechnet werden: " + exception.getMessage());
                }
            }
        }
        applied.keySet().removeIf(key -> Bukkit.getPlayer(key.viewer()) == null || Bukkit.getPlayer(key.target()) == null);
    }

    private boolean shouldGlow(Player viewer, Player target) {
        if (viewer.getWorld() != target.getWorld() || vanished.test(target)) return false;
        boolean adminEnabled = viewer.hasPermission(FriendPermissions.ADMIN_HOME)
                && adminGlow.contains(new GlowKey(viewer.getUniqueId(), target.getUniqueId()));
        boolean friends = cache.areFriends(viewer.getUniqueId(), target.getUniqueId());
        boolean friendEnabled = !suppressed.test(viewer, target) && friends
                && cache.allowsBeingGlowed(target.getUniqueId())
                && cache.settings(viewer.getUniqueId(), target.getUniqueId()).glow();
        // A friendship always owns this relationship. Clan glow may not override its setting.
        boolean clanEnabled = !friends && !suppressed.test(viewer, target)
                && cache.allowsBeingGlowed(target.getUniqueId()) && clanGlow.test(viewer, target);
        return adminEnabled || friendEnabled || clanEnabled;
    }

    private void apply(Player viewer, Player target, boolean glowing) {
        GlowKey key = new GlowKey(viewer.getUniqueId(), target.getUniqueId());
        Boolean previous = applied.get(key);
        if (previous != null && previous == glowing) return;
        applied.put(key, glowing);
        packetService.setGlowing(viewer, target, glowing);
    }

    @EventHandler
    public void move(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getWorld() != event.getTo().getWorld()) return;
        double distance = Math.max(1.0D, config.decimal("settings.movement-refresh-distance", 4.0D));
        if (event.getFrom().distanceSquared(event.getTo()) > distance * distance) refreshAll();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        resyncPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void changedWorld(PlayerChangedWorldEvent event) {
        resyncPlayer(event.getPlayer());
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void track(PlayerTrackEntityEvent event) {
        if (!(event.getEntity() instanceof Player target)) return;
        Player viewer = event.getPlayer();
        GlowKey key = new GlowKey(viewer.getUniqueId(), target.getUniqueId());
        applied.remove(key);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline() || !target.isOnline()) return;
            java.util.List<Player> online = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
            if (!cache.preloadOnline(online.stream().map(Player::getUniqueId).toList())) return;
            try {
                apply(viewer, target, shouldGlow(viewer, target));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Freundes-Glow konnte beim Entity-Tracking nicht gesendet werden: "
                        + exception.getMessage());
            }
        });
    }

    @EventHandler
    public void untrack(PlayerUntrackEntityEvent event) {
        if (!(event.getEntity() instanceof Player target)) return;
        applied.remove(new GlowKey(event.getPlayer().getUniqueId(), target.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event) {
        resyncPlayer(event.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        adminGlow.removeIf(key -> key.viewer().equals(player) || key.target().equals(player));
        queuedResyncs.remove(player);
        applied.keySet().removeIf(key -> key.viewer().equals(player) || key.target().equals(player));
    }
}
