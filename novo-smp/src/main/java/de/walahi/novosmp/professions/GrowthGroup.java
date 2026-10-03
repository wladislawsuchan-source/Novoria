package de.walahi.novosmp.professions;

import org.bukkit.Material;

import java.util.List;
import java.util.Set;

public record GrowthGroup(String id, String displayName, Material icon,
                          Set<Material> saplings, List<String> description) {
    public GrowthGroup {
        saplings = Set.copyOf(saplings);
        description = List.copyOf(description);
    }
}
