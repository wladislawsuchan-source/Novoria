package de.walahi.novosmp.professions;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A group of entity types that can satisfy a Hunter self-kill requirement. */
public record HuntGroup(String id, String displayName, Material icon,
                        Set<String> entityTypes, List<String> description) {
    public HuntGroup {
        entityTypes = Collections.unmodifiableSet(new LinkedHashSet<>(entityTypes));
        description = List.copyOf(description);
    }
}
