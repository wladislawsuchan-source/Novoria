package de.walahi.novosmp.professions;

import org.bukkit.Material;

import java.util.List;
import java.util.Set;

public record MaterialGroup(String id, String displayName, Material icon,
                            Set<Material> materials, List<String> description) {
    public MaterialGroup {
        materials = Set.copyOf(materials);
        description = List.copyOf(description);
    }
}
