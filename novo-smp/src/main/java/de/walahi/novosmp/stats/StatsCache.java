package de.walahi.novosmp.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory player-stat cache with per-player dirty tracking. */
final class StatsCache {
    private final Map<UUID, PlayerStats> players = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();
    private final long lastSeenTouchIntervalMillis;

    StatsCache(Collection<StatsSnapshot> initialEntries, long lastSeenTouchIntervalMillis) {
        this.lastSeenTouchIntervalMillis = Math.max(0L, lastSeenTouchIntervalMillis);
        for (StatsSnapshot snapshot : initialEntries) {
            players.put(snapshot.uuid(), new PlayerStats(snapshot));
        }
    }

    PlayerStats ensure(UUID uuid, String name) {
        return ensure(uuid, name, System.currentTimeMillis());
    }

    PlayerStats ensure(UUID uuid, String name, long now) {
        PlayerStats created = PlayerStats.create(uuid, name, now);
        PlayerStats stats = players.putIfAbsent(uuid, created);
        if (stats == null) {
            dirtyPlayers.add(uuid);
            return created;
        }
        if (stats.updateIdentity(name, now, lastSeenTouchIntervalMillis)) dirtyPlayers.add(uuid);
        return stats;
    }

    void touchNow(UUID uuid, String name, long now) {
        PlayerStats stats = players.get(uuid);
        if (stats == null) {
            ensure(uuid, name, now);
            return;
        }
        if (stats.updateIdentity(name, now, 0L)) dirtyPlayers.add(uuid);
    }

    long get(UUID uuid, String statPath) {
        PlayerStats stats = players.get(uuid);
        return stats == null ? 0L : stats.value(statPath);
    }

    void add(UUID uuid, String statPath, long amount) {
        if (uuid == null || amount == 0L) return;
        PlayerStats stats = players.get(uuid);
        if (stats == null) {
            stats = ensure(uuid, uuid.toString());
        }
        if (stats.add(statPath, amount)) dirtyPlayers.add(uuid);
    }

    void set(UUID uuid, String statPath, long value) {
        if (uuid == null) return;
        PlayerStats stats = players.get(uuid);
        if (stats == null) stats = ensure(uuid, uuid.toString());
        if (stats.set(statPath, value)) dirtyPlayers.add(uuid);
    }

    StatsSnapshot snapshot(UUID uuid, String fallbackName) {
        PlayerStats stats = players.get(uuid);
        if (stats != null) return stats.snapshot();
        long now = System.currentTimeMillis();
        return new StatsSnapshot(uuid, fallbackName, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, now, 0L);
    }

    List<StatsSnapshot> snapshots() {
        List<StatsSnapshot> result = new ArrayList<>(players.size());
        for (PlayerStats stats : players.values()) result.add(stats.snapshot());
        return result;
    }

    List<StatsSnapshot> dirtySnapshots() {
        List<StatsSnapshot> result = new ArrayList<>(dirtyPlayers.size());
        for (UUID uuid : List.copyOf(dirtyPlayers)) {
            PlayerStats stats = players.get(uuid);
            if (stats != null) result.add(stats.snapshot());
        }
        return result;
    }

    void markPersisted(Collection<StatsSnapshot> persisted) {
        for (StatsSnapshot snapshot : persisted) {
            PlayerStats current = players.get(snapshot.uuid());
            if (current != null && current.snapshot().equals(snapshot)) {
                dirtyPlayers.remove(snapshot.uuid());
            }
        }
    }

    int size() {
        return players.size();
    }

    boolean isDirty() {
        return !dirtyPlayers.isEmpty();
    }
}
