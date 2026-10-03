package de.walahi.smpcore.api.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Read/write access to SMPCore player session and last-location data. */
public interface PlayerDataApi {
    boolean isTrackedOnline(UUID playerUuid);
    Optional<Instant> sessionStartedAt(UUID playerUuid);
    void saveLastSmpLocation(Player player, Location location);
    Optional<Location> loadLastSmpLocation(Player player);
}
