package de.walahi.smpcore.api.event;

import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

/** Reserved public event contract for professions in 12.x. */
public final class ProfessionLevelChangeEvent extends SMPCoreEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerUuid;
    private final String professionId;
    private final int oldLevel;
    private final int newLevel;

    public ProfessionLevelChangeEvent(UUID playerUuid, String professionId, int oldLevel, int newLevel) {
        this(playerUuid, professionId, oldLevel, newLevel, ActionContext.system(playerUuid));
    }

    public ProfessionLevelChangeEvent(UUID playerUuid, String professionId, int oldLevel, int newLevel,
                                      ActionContext context) {
        super(context);
        if (oldLevel < 0 || newLevel < 0) throw new IllegalArgumentException("levels must be non-negative");
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.professionId = Objects.requireNonNull(professionId, "professionId");
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public String getProfessionId() { return professionId; }
    public int getOldLevel() { return oldLevel; }
    public int getNewLevel() { return newLevel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
