package de.walahi.smpcore.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/** Fired after a home was successfully deleted. */
public final class HomeDeleteEvent extends SMPCoreEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String normalizedName;
    private final String displayName;

    public HomeDeleteEvent(Player player, String normalizedName, String displayName) {
        this(player, normalizedName, displayName,
                ActionContext.player(ActionSource.COMMAND, player.getUniqueId()));
    }

    public HomeDeleteEvent(Player player, String normalizedName, String displayName, ActionContext context) {
        super(context);
        this.player = Objects.requireNonNull(player, "player");
        this.normalizedName = Objects.requireNonNull(normalizedName, "normalizedName");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
    }

    public Player getPlayer() { return player; }
    public String getNormalizedName() { return normalizedName; }
    public String getDisplayName() { return displayName; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
