package de.walahi.smpcore.bridge;

import org.bukkit.Location;

import java.util.List;
import java.util.Optional;

/** Neutral bridge used by shared hologram code without depending on NovoSMP crate classes. */
public interface CrateLocator {
    Optional<String> crateAt(Location location);

    /** Snapshot of all configured physical crates. */
    default List<CratePlacement> placements() {
        return List.of();
    }

    record CratePlacement(Location location, String crateId, String displayName) { }
}
