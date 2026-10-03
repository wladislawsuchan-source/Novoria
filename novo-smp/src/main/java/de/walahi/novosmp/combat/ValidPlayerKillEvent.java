package de.walahi.novosmp.combat;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Stable hook for bounty and other relationship-aware kill consumers. */
public final class ValidPlayerKillEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID killerId;
    private final String killerName;
    private final UUID victimId;
    private final String victimName;
    private final ValidKillCause cause;
    private final Location location;

    public ValidPlayerKillEvent(UUID killerId, String killerName, UUID victimId, String victimName,
                                ValidKillCause cause, Location location) {
        this.killerId = killerId;
        this.killerName = killerName;
        this.victimId = victimId;
        this.victimName = victimName;
        this.cause = cause;
        this.location = location == null ? null : location.clone();
    }

    public UUID killerId() { return killerId; }
    public String killerName() { return killerName; }
    public UUID victimId() { return victimId; }
    public String victimName() { return victimName; }
    public ValidKillCause cause() { return cause; }
    public Location location() { return location == null ? null : location.clone(); }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
