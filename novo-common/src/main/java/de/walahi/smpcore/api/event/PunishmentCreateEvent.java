package de.walahi.smpcore.api.event;

import de.walahi.smpcore.punishments.Punishment;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/** Fired after a punishment was persisted successfully. */
public final class PunishmentCreateEvent extends SMPCoreEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Punishment punishment;

    public PunishmentCreateEvent(Punishment punishment) {
        this(punishment, ActionContext.actorTarget(
                ActionSource.COMMAND,
                punishment.staffUuid(),
                punishment.playerUuid()
        ));
    }

    public PunishmentCreateEvent(Punishment punishment, ActionContext context) {
        super(context);
        this.punishment = Objects.requireNonNull(punishment, "punishment");
    }

    public Punishment getPunishment() { return punishment; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
