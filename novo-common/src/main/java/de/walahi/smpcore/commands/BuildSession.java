package de.walahi.smpcore.commands;

import org.bukkit.GameMode;
import org.bukkit.permissions.PermissionAttachment;

public record BuildSession(
        GameMode previousGameMode,
        boolean previousAllowFlight,
        boolean previousFlying,
        float previousFlySpeed,
        PermissionAttachment permissionAttachment,
        long startedAt,
        long lastActivityAt
) {
    public BuildSession withActivity(long timestamp) {
        return new BuildSession(previousGameMode, previousAllowFlight, previousFlying,
                previousFlySpeed, permissionAttachment, startedAt, timestamp);
    }
}
