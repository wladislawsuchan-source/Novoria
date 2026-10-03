package de.walahi.novosmp.friends;

import de.walahi.smpcore.friends.FriendRepository;
import de.walahi.smpcore.friends.FriendSettings;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read cache for hot friend checks (glow, chat, combat and presence).
 * Online player pairs are batch-loaded; mutating paths invalidate immediately.
 */
public final class FriendStateCache {
    private record RelationKey(UUID owner, UUID friend) { }
    private record TimedRelation(boolean friends, FriendSettings settings, long loadedAt) { }
    private record TimedBoolean(boolean value, long loadedAt) { }

    private final FriendRepository repository;
    private final FriendStateSnapshotRepository snapshots;
    private final long ttlMillis;
    private final Map<RelationKey, TimedRelation> relations = new ConcurrentHashMap<>();
    private final Map<UUID, TimedBoolean> glowPermissions = new ConcurrentHashMap<>();
    private volatile Set<UUID> lastOnlinePlayers = Set.of();
    private volatile long lastSnapshotAt;

    public FriendStateCache(FriendRepository repository, FriendStateSnapshotRepository snapshots, long ttlMillis) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.ttlMillis = Math.max(1_000L, ttlMillis);
    }

    /** Ensures every directional pair in the supplied online set is cached, including non-friend pairs. */
    public synchronized boolean preloadOnline(Collection<UUID> players) {
        Set<UUID> online = players == null ? Set.of() : Set.copyOf(new HashSet<>(players));
        long now = System.currentTimeMillis();
        if (online.equals(lastOnlinePlayers) && now - lastSnapshotAt < ttlMillis) return true;

        FriendStateSnapshotRepository.Snapshot snapshot = snapshots.load(online);
        if (!snapshot.successful()) return false;
        relations.keySet().removeIf(key -> online.contains(key.owner()) && online.contains(key.friend()));
        for (UUID owner : online) {
            for (UUID friend : online) {
                if (!owner.equals(friend)) {
                    relations.put(new RelationKey(owner, friend),
                            new TimedRelation(false, FriendSettings.defaults(), now));
                }
            }
        }
        for (FriendStateSnapshotRepository.Relation relation : snapshot.relations()) {
            relations.put(new RelationKey(relation.owner(), relation.friend()),
                    new TimedRelation(true, relation.settings(), now));
        }
        snapshot.glowPermissions().forEach((player, allowed) ->
                glowPermissions.put(player, new TimedBoolean(allowed, now)));
        lastOnlinePlayers = online;
        lastSnapshotAt = now;
        return true;
    }

    public boolean areFriends(UUID owner, UUID friend) {
        return relation(owner, friend).friends();
    }

    public FriendSettings settings(UUID owner, UUID friend) {
        return relation(owner, friend).settings();
    }

    public boolean allowsBeingGlowed(UUID player) {
        long now = System.currentTimeMillis();
        TimedBoolean cached = glowPermissions.get(player);
        if (cached != null && now - cached.loadedAt() < ttlMillis) return cached.value();
        boolean value = repository.allowsBeingGlowed(player);
        glowPermissions.put(player, new TimedBoolean(value, now));
        return value;
    }

    public void invalidatePair(UUID first, UUID second) {
        relations.remove(new RelationKey(first, second));
        relations.remove(new RelationKey(second, first));
    }

    public void invalidateSettings(UUID owner, UUID friend) {
        relations.remove(new RelationKey(owner, friend));
    }

    public void invalidatePlayer(UUID player) {
        relations.keySet().removeIf(key -> key.owner().equals(player) || key.friend().equals(player));
        glowPermissions.remove(player);
        lastOnlinePlayers = Set.of();
    }

    public void invalidateGlowPermission(UUID player) {
        glowPermissions.remove(player);
    }

    public void clear() {
        relations.clear();
        glowPermissions.clear();
        lastOnlinePlayers = Set.of();
        lastSnapshotAt = 0L;
    }

    private TimedRelation relation(UUID owner, UUID friend) {
        if (owner == null || friend == null || owner.equals(friend)) {
            return new TimedRelation(false, FriendSettings.defaults(), System.currentTimeMillis());
        }
        RelationKey key = new RelationKey(owner, friend);
        long now = System.currentTimeMillis();
        TimedRelation cached = relations.get(key);
        if (cached != null && now - cached.loadedAt() < ttlMillis) return cached;

        boolean friends = repository.areFriends(owner, friend);
        FriendSettings settings = friends ? repository.getSettings(owner, friend) : FriendSettings.defaults();
        TimedRelation loaded = new TimedRelation(friends, settings, now);
        relations.put(key, loaded);
        return loaded;
    }
}
