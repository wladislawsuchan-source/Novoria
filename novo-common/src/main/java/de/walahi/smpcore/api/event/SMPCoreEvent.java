package de.walahi.smpcore.api.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Base class for every public SMPCore event.
 *
 * Every event carries a unique id and an immutable creation timestamp so
 * integrations can correlate, log and deduplicate event deliveries.
 */
public abstract class SMPCoreEvent extends Event {
    private final UUID eventId;
    private final Instant occurredAt;
    private final ActionContext context;

    protected SMPCoreEvent(ActionContext context) {
        super(!Bukkit.isPrimaryThread());
        this.eventId = UUID.randomUUID();
        this.occurredAt = Instant.now();
        this.context = Objects.requireNonNull(context, "context");
    }

    public final UUID getEventId() {
        return eventId;
    }

    public final Instant getOccurredAt() {
        return occurredAt;
    }

    public final ActionContext getContext() {
        return context;
    }

    public final ActionSource getSource() {
        return context.source();
    }

    public final UUID getActorUuid() {
        return context.actorUuid();
    }

    public final UUID getTargetUuid() {
        return context.targetUuid();
    }
}
