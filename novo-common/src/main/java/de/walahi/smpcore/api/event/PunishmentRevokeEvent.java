package de.walahi.smpcore.api.event;

import de.walahi.smpcore.punishments.PunishmentType;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

/** Fired after an active punishment was revoked successfully. */
public final class PunishmentRevokeEvent extends SMPCoreEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerUuid;
    private final PunishmentType type;
    private final UUID staffUuid;
    private final String staffName;

    public PunishmentRevokeEvent(UUID playerUuid, PunishmentType type, UUID staffUuid, String staffName) {
        this(playerUuid, type, staffUuid, staffName,
                ActionContext.actorTarget(ActionSource.COMMAND, staffUuid, playerUuid));
    }

    public PunishmentRevokeEvent(UUID playerUuid, PunishmentType type, UUID staffUuid,
                                 String staffName, ActionContext context) {
        super(context);
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.type = Objects.requireNonNull(type, "type");
        this.staffUuid = staffUuid;
        this.staffName = Objects.requireNonNullElse(staffName, "CONSOLE");
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public PunishmentType getType() { return type; }
    public UUID getStaffUuid() { return staffUuid; }
    public String getStaffName() { return staffName; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
