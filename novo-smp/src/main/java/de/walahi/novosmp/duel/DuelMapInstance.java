package de.walahi.novosmp.duel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Lightweight offset definition for an additional physical copy of a logical duel map.
 * The copied arena reuses the original schematic and derives its region/spawns from it.
 */
public record DuelMapInstance(
        String id,
        String worldName,
        int offsetX,
        int offsetY,
        int offsetZ
) {
    public DuelMapInstance {
        id = DuelConfig.normalize(id);
        if (id.isBlank()) throw new IllegalArgumentException("instance id");
        if (worldName == null || worldName.isBlank()) throw new IllegalArgumentException("world name");
    }

    public DuelMap createArena(String logicalMapId, DuelMap original) {
        String normalizedMapId = DuelConfig.normalize(logicalMapId);
        World world = Bukkit.getWorld(worldName);
        return new DuelMap(
                physicalId(normalizedMapId),
                original.displayName(),
                original.icon(),
                worldName,
                original.minX() + offsetX,
                original.minY() + offsetY,
                original.minZ() + offsetZ,
                original.maxX() + offsetX,
                original.maxY() + offsetY,
                original.maxZ() + offsetZ,
                shift(original.spawnOne(), world),
                shift(original.spawnTwo(), world),
                original.schematicFile()
        );
    }

    public String physicalId(String logicalMapId) {
        return DuelConfig.normalize(logicalMapId) + "__instance__" + id;
    }

    private Location shift(Location source, World world) {
        if (source == null) return null;
        Location shifted = source.clone().add(offsetX, offsetY, offsetZ);
        shifted.setWorld(world);
        return shifted;
    }
}
