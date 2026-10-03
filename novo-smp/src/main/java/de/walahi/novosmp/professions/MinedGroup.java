package de.walahi.novosmp.professions;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A group of natural blocks that can satisfy a miner self-mining requirement. */
public record MinedGroup(String id, String displayName, Material icon,
                         Set<Material> materials, List<String> description) {
    public MinedGroup {
        materials = Collections.unmodifiableSet(new LinkedHashSet<>(materials));
        description = List.copyOf(description);
    }
}
