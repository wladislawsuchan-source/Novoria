package de.walahi.smpcore.api.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public punishment API with stable DTOs instead of internal manager classes. */
public interface PunishmentApi {
    PunishmentActionResult punish(UUID playerUuid, String playerName, UUID staffUuid, String staffName,
                                  ApiPunishmentType type, String reason, Duration duration);
    Optional<PunishmentData> active(UUID playerUuid, ApiPunishmentType type);
    boolean revoke(UUID playerUuid, ApiPunishmentType type, UUID staffUuid, String staffName);
    List<PunishmentData> history(UUID playerUuid);
}
