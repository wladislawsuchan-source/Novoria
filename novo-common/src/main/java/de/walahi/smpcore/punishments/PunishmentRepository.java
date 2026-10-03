package de.walahi.smpcore.punishments;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PunishmentRepository {

    Punishment create(UUID playerUuid,
                      String playerName,
                      UUID staffUuid,
                      String staffName,
                      PunishmentType type,
                      String reason,
                      Instant expiresAt) throws SQLException;

    Optional<Punishment> findActive(UUID playerUuid, PunishmentType type, Instant now) throws SQLException;

    List<Punishment> findHistory(UUID playerUuid) throws SQLException;

    boolean revokeActive(UUID playerUuid,
                         PunishmentType type,
                         UUID staffUuid,
                         String staffName,
                         Instant revokedAt) throws SQLException;

    int deactivateExpired(Instant now) throws SQLException;
}
