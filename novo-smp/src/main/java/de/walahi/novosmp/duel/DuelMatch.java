package de.walahi.novosmp.duel;

import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DuelMatch {
    enum State { PREPARING, COUNTDOWN, RUNNING, ENDING, FINISHED }

    final UUID id = UUID.randomUUID();
    final DuelRequest request;
    final DuelMap map;
    final Map<UUID, DuelPlayerSnapshot> snapshots = new HashMap<>();
    final Map<UUID, Double> damage = new HashMap<>();
    final Map<UUID, LastDamage> lastDamage = new HashMap<>();
    final Map<UUID, Location> countdownAnchors = new HashMap<>();
    final Set<UUID> restoredPlayers = new HashSet<>();
    final Set<String> playerPlacedBlocks = new HashSet<>();
    State state = State.PREPARING;
    long startedAtMillis;
    BukkitTask timerTask;
    BukkitTask countdownFreezeTask;
    boolean escrowed;
    UUID winner;
    boolean resolved;
    boolean lifecycleCompleted;

    DuelMatch(DuelRequest request, DuelMap map) {
        this.request = request;
        this.map = map;
        damage.put(request.challenger(), 0D);
        damage.put(request.target(), 0D);
    }

    UUID opponent(UUID player) {
        return request.challenger().equals(player) ? request.target() : request.challenger();
    }

    boolean contains(UUID player) {
        return request.challenger().equals(player) || request.target().equals(player);
    }

    boolean running() { return state == State.RUNNING; }

    void rememberPlacedBlock(Location location) {
        String key = blockKey(location);
        if (key != null) playerPlacedBlocks.add(key);
    }

    boolean consumePlacedBlock(Location location) {
        String key = blockKey(location);
        return key != null && playerPlacedBlocks.remove(key);
    }

    void forgetPlacedBlock(Location location) {
        String key = blockKey(location);
        if (key != null) playerPlacedBlocks.remove(key);
    }

    private static String blockKey(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":"
                + location.getBlockY() + ":" + location.getBlockZ();
    }

    record LastDamage(UUID attacker, long atMillis) {}
}
