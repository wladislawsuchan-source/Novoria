package de.walahi.smpcore.api.service;

import java.time.Instant;
import java.util.UUID;

/** Immutable public view of a punishment entry. */
public record PunishmentData(long id, UUID playerUuid, String playerName, UUID staffUuid, String staffName,
                             ApiPunishmentType type, String reason, Instant createdAt, Instant expiresAt,
                             boolean active, Instant revokedAt, UUID revokedByUuid, String revokedByName) {
    public boolean permanent() { return expiresAt == null; }
    public boolean expired(Instant now) { return expiresAt != null && !expiresAt.isAfter(now); }
    public boolean currentlyActive(Instant now) { return active && !expired(now); }
}
