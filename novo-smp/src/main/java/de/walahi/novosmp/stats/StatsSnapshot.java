package de.walahi.novosmp.stats;

import java.util.UUID;

/** Immutable view of one player's persisted SMP statistics. */
record StatsSnapshot(
        UUID uuid,
        String name,
        long kills,
        long deaths,
        long blocksMined,
        long mobsKilled,
        long playtimeSeconds,
        long blocksPlaced,
        long sellEarnings,
        long advancements,
        long prestige,
        long headsCollected,
        long registeredAt,
        long lastSeen
) {
    public StatsSnapshot {
        if (uuid == null) throw new IllegalArgumentException("uuid darf nicht null sein");
        name = name == null || name.isBlank() ? uuid.toString() : name;
    }

    public long value(String statPath) {
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
}
