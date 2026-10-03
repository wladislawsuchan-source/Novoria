package de.walahi.novosmp.stats;

import java.util.Objects;
import java.util.UUID;

/** Mutable cache entity. Persistence and GUI code only consume immutable snapshots. */
final class PlayerStats {
    private final UUID uuid;
    private String name;
    private long kills;
    private long deaths;
    private long blocksMined;
    private long mobsKilled;
    private long playtimeSeconds;
    private long blocksPlaced;
    private long sellEarnings;
    private long advancements;
    private long prestige;
    private long headsCollected;
    private final long registeredAt;
    private long lastSeen;

    PlayerStats(StatsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.uuid = snapshot.uuid();
        this.name = snapshot.name();
        this.kills = nonNegative(snapshot.kills());
        this.deaths = nonNegative(snapshot.deaths());
        this.blocksMined = nonNegative(snapshot.blocksMined());
        this.mobsKilled = nonNegative(snapshot.mobsKilled());
        this.playtimeSeconds = nonNegative(snapshot.playtimeSeconds());
        this.blocksPlaced = nonNegative(snapshot.blocksPlaced());
        this.sellEarnings = nonNegative(snapshot.sellEarnings());
        this.advancements = nonNegative(snapshot.advancements());
        this.prestige = nonNegative(snapshot.prestige());
        this.headsCollected = nonNegative(snapshot.headsCollected());
        this.registeredAt = snapshot.registeredAt() > 0L ? snapshot.registeredAt() : System.currentTimeMillis();
        this.lastSeen = Math.max(0L, snapshot.lastSeen());
    }

    static PlayerStats create(UUID uuid, String name, long now) {
        return new PlayerStats(new StatsSnapshot(
                uuid, name, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, now, now));
    }

    UUID uuid() {
        return uuid;
    }

    synchronized boolean updateIdentity(String newName, long seenAt, long minimumTouchIntervalMillis) {
        boolean changed = false;
        if (isReadablePlayerName(newName) && !newName.equals(name)) {
            name = newName;
            changed = true;
        }
        long safeSeenAt = Math.max(0L, seenAt);
        if (safeSeenAt > lastSeen && (minimumTouchIntervalMillis <= 0L
                || safeSeenAt - lastSeen >= minimumTouchIntervalMillis)) {
            lastSeen = safeSeenAt;
            changed = true;
        }
        return changed;
    }

    synchronized boolean set(String statPath, long value) {
        long safe = nonNegative(value);
        long previous = value(statPath);
        if (previous == safe) return false;
        switch (statPath) {
            case "kills" -> kills = safe;
            case "deaths" -> deaths = safe;
            case "blocks-mined" -> blocksMined = safe;
            case "mobs-killed" -> mobsKilled = safe;
            case "playtime-seconds" -> playtimeSeconds = safe;
            case "blocks-placed" -> blocksPlaced = safe;
            case "sell-earnings" -> sellEarnings = safe;
            case "advancements" -> advancements = safe;
            case "prestige" -> prestige = safe;
            case "heads-collected" -> headsCollected = safe;
            case "last-seen" -> lastSeen = safe;
            default -> {
                return false;
            }
        }
        return true;
    }

    synchronized boolean add(String statPath, long amount) {
        if (amount == 0L) return false;
        long current = value(statPath);
        long next;
        try {
            next = Math.addExact(current, amount);
        } catch (ArithmeticException ignored) {
            next = amount > 0L ? Long.MAX_VALUE : 0L;
        }
        return set(statPath, next);
    }

    synchronized long value(String statPath) {
        return switch (statPath) {
            case "kills" -> kills;
            case "deaths" -> deaths;
            case "blocks-mined" -> blocksMined;
            case "mobs-killed" -> mobsKilled;
            case "playtime-seconds" -> playtimeSeconds;
            case "blocks-placed" -> blocksPlaced;
            case "sell-earnings" -> sellEarnings;
            case "advancements" -> advancements;
            case "prestige" -> prestige;
            case "heads-collected" -> headsCollected;
            case "registered-at" -> registeredAt;
            case "last-seen" -> lastSeen;
            default -> 0L;
        };
    }

    synchronized StatsSnapshot snapshot() {
        return new StatsSnapshot(uuid, name, kills, deaths, blocksMined, mobsKilled,
                playtimeSeconds, blocksPlaced, sellEarnings, advancements, prestige, headsCollected, registeredAt, lastSeen);
    }

    /**
     * UUID text is only an internal fallback for stat updates where the player is offline.
     * It must never replace a real name that is already known for this UUID.
     */
    private boolean isReadablePlayerName(String candidate) {
        return candidate != null
                && !candidate.isBlank()
                && !candidate.equalsIgnoreCase(uuid.toString());
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }
}
