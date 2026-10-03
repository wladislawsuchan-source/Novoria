package de.walahi.smpcore.api.service;

import org.bukkit.Location;

/** Immutable public view of a saved home. */
public record HomeData(String normalizedName, String displayName, Location location) {
    public HomeData {
        location = location.clone();
    }

    @Override
    public Location location() {
        return location.clone();
    }
}
