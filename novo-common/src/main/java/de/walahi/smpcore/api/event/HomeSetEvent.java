package de.walahi.smpcore.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/** Fired after a home was successfully created or updated. */
public final class HomeSetEvent extends SMPCoreEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public enum Action { CREATED, UPDATED }

    private final Player player;
    private final String normalizedName;
    private final String displayName;
    private final Location location;
    private final Action action;

    public HomeSetEvent(Player player, String normalizedName, String displayName, Location location, Action action) {
        this(player, normalizedName, displayName, location, action,
                ActionContext.player(ActionSource.COMMAND, player.getUniqueId()));
    }

    public HomeSetEvent(Player player, String normalizedName, String displayName, Location location,
                        Action action, ActionContext context) {
        super(context);
        this.player = Objects.requireNonNull(player, "player");
        this.normalizedName = Objects.requireNonNull(normalizedName, "normalizedName");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.location = Objects.requireNonNull(location, "location").clone();
        this.action = Objects.requireNonNull(action, "action");
    }

    public Player getPlayer() { return player; }
    public String getNormalizedName() { return normalizedName; }
    public String getDisplayName() { return displayName; }
    public Location getLocation() { return location.clone(); }
    public Action getAction() { return action; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
