package de.walahi.smpcore.api.internal;

import de.walahi.smpcore.api.service.*;
import de.walahi.smpcore.punishments.Punishment;
import de.walahi.smpcore.punishments.PunishmentType;
import de.walahi.smpcore.services.PunishmentService;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class DefaultPunishmentApi implements PunishmentApi {
    private final PunishmentService service;
    DefaultPunishmentApi(PunishmentService service) { this.service = Objects.requireNonNull(service, "service"); }

    @Override public PunishmentActionResult punish(UUID playerUuid, String playerName, UUID staffUuid, String staffName,
                                                   ApiPunishmentType type, String reason, Duration duration) {
        var result = service.punish(playerUuid, playerName, staffUuid, staffName, toInternal(type), reason, duration);
        return new PunishmentActionResult(result.success(), result.message(), Optional.ofNullable(result.punishment()).map(this::toData));
    }
    @Override public Optional<PunishmentData> active(UUID playerUuid, ApiPunishmentType type) {
        return service.active(playerUuid, toInternal(type)).map(this::toData);
    }
    @Override public boolean revoke(UUID playerUuid, ApiPunishmentType type, UUID staffUuid, String staffName) {
        return service.revoke(playerUuid, toInternal(type), staffUuid, staffName);
    }
    @Override public List<PunishmentData> history(UUID playerUuid) {
        return service.history(playerUuid).stream().map(this::toData).toList();
    }
    private PunishmentType toInternal(ApiPunishmentType type) { return PunishmentType.valueOf(type.name()); }
    private ApiPunishmentType toPublic(PunishmentType type) { return ApiPunishmentType.valueOf(type.name()); }
    private PunishmentData toData(Punishment p) {
        return new PunishmentData(p.id(), p.playerUuid(), p.playerName(), p.staffUuid(), p.staffName(),
                toPublic(p.type()), p.reason(), p.createdAt(), p.expiresAt(), p.active(), p.revokedAt(),
                p.revokedByUuid(), p.revokedByName());
    }
}
