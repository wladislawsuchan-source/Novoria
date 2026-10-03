package de.walahi.smpcore.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable metadata describing who initiated an action, who it affected and
 * through which entry point it was triggered.
 */
public record ActionContext(ActionSource source, UUID actorUuid, UUID targetUuid) {
    public ActionContext {
        source = Objects.requireNonNull(source, "source");
    }

    public static ActionContext player(ActionSource source, UUID playerUuid) {
        UUID uuid = Objects.requireNonNull(playerUuid, "playerUuid");
        return new ActionContext(source, uuid, uuid);
    }

    public static ActionContext actorTarget(ActionSource source, UUID actorUuid, UUID targetUuid) {
        return new ActionContext(source, actorUuid, Objects.requireNonNull(targetUuid, "targetUuid"));
    }

    public static ActionContext system(UUID targetUuid) {
        return new ActionContext(ActionSource.SYSTEM, null, targetUuid);
    }

    public Optional<UUID> actor() {
        return Optional.ofNullable(actorUuid);
    }

    public Optional<UUID> target() {
        return Optional.ofNullable(targetUuid);
    }
}
