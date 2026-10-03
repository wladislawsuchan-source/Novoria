package de.walahi.novosmp.duel;

import java.util.UUID;

public record DuelRequest(
        UUID id,
        UUID challenger,
        UUID target,
        String mapId,
        int durationSeconds,
        long wager,
        String kitId,
        DuelRules rules,
        long expiresAtMillis
) {
    public boolean expired() { return System.currentTimeMillis() >= expiresAtMillis; }
}
