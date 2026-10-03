package de.walahi.smpcore.services;

import de.walahi.smpcore.api.EventPublisher;
import de.walahi.smpcore.api.event.PunishmentCreateEvent;
import de.walahi.smpcore.api.event.PunishmentRevokeEvent;
import de.walahi.smpcore.punishments.Punishment;
import de.walahi.smpcore.punishments.PunishmentManager;
import de.walahi.smpcore.punishments.PunishmentResult;
import de.walahi.smpcore.punishments.PunishmentType;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Application-facing facade around punishment persistence and business logic. */
public final class PunishmentService implements Service {
    private final PunishmentManager manager;
    private final EventPublisher events;

    public PunishmentService(PunishmentManager manager, EventPublisher events) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.events = Objects.requireNonNull(events, "events");
    }

    public PunishmentResult punish(UUID playerUuid, String playerName, UUID staffUuid, String staffName,
                                   PunishmentType type, String reason, Duration duration) {
        PunishmentResult result = manager.punish(playerUuid, playerName, staffUuid, staffName, type, reason, duration);
        if (result.success() && result.punishment() != null) {
            events.publish(new PunishmentCreateEvent(result.punishment()));
        }
        return result;
    }

    public Optional<Punishment> active(UUID playerUuid, PunishmentType type) {
        return manager.active(playerUuid, type);
    }

    public boolean revoke(UUID playerUuid, PunishmentType type, UUID staffUuid, String staffName) {
        boolean revoked = manager.revoke(playerUuid, type, staffUuid, staffName);
        if (revoked) {
            events.publish(new PunishmentRevokeEvent(playerUuid, type, staffUuid, staffName));
        }
        return revoked;
    }

    public List<Punishment> history(UUID playerUuid) {
        return manager.history(playerUuid);
    }

    public void cleanupExpired() {
        manager.cleanupExpired();
    }
}
