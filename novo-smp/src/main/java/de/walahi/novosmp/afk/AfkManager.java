package de.walahi.novosmp.afk;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import de.walahi.smpcore.messages.MessageChannel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Verwaltet manuelles und automatisches AFK ohne globale Chat-Broadcasts. */
public final class AfkManager implements Listener, de.walahi.smpcore.afk.AfkAccess {
    private final SMPCorePlugin plugin;
    private final Map<UUID, Long> lastMovement = new HashMap<>();
    private final Set<UUID> afkPlayers = new HashSet<>();
    private BukkitTask checkTask;
    private Consumer<UUID> onBecomeAfk = ignored -> { };
    private Consumer<UUID> onLeaveAfk = ignored -> { };

    public AfkManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void onBecomeAfk(Consumer<UUID> callback) {
        onBecomeAfk = callback == null ? ignored -> { } : callback;
    }

    public void onLeaveAfk(Consumer<UUID> callback) {
        onLeaveAfk = callback == null ? ignored -> { } : callback;
    }

    public void start() {
        stop();
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) lastMovement.put(player.getUniqueId(), now);
        long interval = Math.max(20L, plugin.configs().main().getLong("afk.check-interval-ticks", 20L * 10L));
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkAutomaticAfk, interval, interval);
    }

    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }

    @Override
    public void shutdown() { stop(); }

    @Override
    public boolean isAfk(Player player) {
        return player != null && afkPlayers.contains(player.getUniqueId());
    }

    @Override
    public boolean toggle(Player player) {
        if (isAfk(player)) {
            setAfk(player, false, true);
            return false;
        }
        setAfk(player, true, true);
        return true;
    }

    @Override
    public void markActivity(Player player) {
        if (player == null) return;
        lastMovement.put(player.getUniqueId(), System.currentTimeMillis());
        if (isAfk(player)) setAfk(player, false, false);
    }

    private void checkAutomaticAfk() {
        if (!plugin.configs().main().getBoolean("afk.automatic.enabled", true)) return;
        long timeoutMillis = Math.max(1L, plugin.configs().main().getLong("afk.automatic.minutes", 5L)) * 60_000L;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            long last = lastMovement.getOrDefault(player.getUniqueId(), now);
            if (!isAfk(player) && now - last >= timeoutMillis) setAfk(player, true, false);
        }
    }

    private void setAfk(Player player, boolean afk, boolean manual) {
        UUID uuid = player.getUniqueId();
        if (afk) {
            afkPlayers.add(uuid);
            onBecomeAfk.accept(uuid);
            String path = manual ? "afk.messages.enabled" : "afk.messages.automatic-enabled";
            send(player, path, "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Du bist jetzt AFK.</gray>");
        } else {
            afkPlayers.remove(uuid);
            onLeaveAfk.accept(uuid);
            lastMovement.put(uuid, System.currentTimeMillis());
            send(player, "afk.messages.disabled", "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Du bist nicht mehr AFK.</gray>");
        }
        if (plugin.getRankManager() != null) plugin.getRankManager().applyAll();
    }

    private void send(Player player, String path, String fallback) {
        plugin.messages().sendConfigured(player, path, MessageChannel.SMP, fallback);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastMovement.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        afkPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastMovement.remove(uuid);
        afkPlayers.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        boolean changed = from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()
                || from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch();
        if (!changed) return;

        Player player = event.getPlayer();
        markActivity(player);
    }
}
