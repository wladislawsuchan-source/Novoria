package de.walahi.smpcore.punishments;

import java.time.Instant;
import java.util.UUID;

public record Punishment(
        long id,
        UUID playerUuid,
        String playerName,
        UUID staffUuid,
        String staffName,
        PunishmentType type,
        String reason,
        Instant createdAt,
        Instant expiresAt,
        boolean active,
        Instant revokedAt,
        UUID revokedByUuid,
        String revokedByName
) {
    public boolean permanent() {
        return expiresAt == null;
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean currentlyActive(Instant now) {
        return active && !expired(now);
    }
}
