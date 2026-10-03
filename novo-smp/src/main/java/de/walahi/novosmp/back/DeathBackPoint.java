package de.walahi.novosmp.back;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record DeathBackPoint(
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        long createdAt,
        long expiresAt
) {
    public Location toLocation() {
        World resolvedWorld = Bukkit.getWorld(world);
        return resolvedWorld == null ? null : new Location(resolvedWorld, x, y, z, yaw, pitch);
    }

    public boolean expired(long now) {
        return expiresAt <= now;
    }
}
