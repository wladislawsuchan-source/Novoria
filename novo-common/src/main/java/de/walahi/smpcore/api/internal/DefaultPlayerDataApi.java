package de.walahi.smpcore.api.internal;

import de.walahi.smpcore.api.service.PlayerDataApi;
import de.walahi.smpcore.services.PlayerDataService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class DefaultPlayerDataApi implements PlayerDataApi {
    private final PlayerDataService service;
    DefaultPlayerDataApi(PlayerDataService service) { this.service = Objects.requireNonNull(service, "service"); }
    @Override public boolean isTrackedOnline(UUID playerUuid) { return service.isTrackedOnline(playerUuid); }
    @Override public Optional<Instant> sessionStartedAt(UUID playerUuid) { return Optional.ofNullable(service.sessionStartedAt(playerUuid)); }
    @Override public void saveLastSmpLocation(Player player, Location location) { service.saveLastSmpLocation(player, location); }
    @Override public Optional<Location> loadLastSmpLocation(Player player) { return Optional.ofNullable(service.loadLastSmpLocation(player)); }
}
