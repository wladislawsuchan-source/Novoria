package de.walahi.novosmp.duel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

public record DuelMap(
        String id,
        String displayName,
        Material icon,
        String worldName,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        Location spawnOne,
        Location spawnTwo,
        String schematicFile
) {
    public DuelMap {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("map id");
        if (displayName == null || displayName.isBlank()) displayName = id;
        if (icon == null || !icon.isItem()) icon = Material.GRASS_BLOCK;
    }

    public World world() {
        return worldName == null ? null : Bukkit.getWorld(worldName);
    }

    public Location spawnOne() { return resolve(spawnOne); }

    public Location spawnTwo() { return resolve(spawnTwo); }

    private Location resolve(Location stored) {
        if (stored == null) return null;
        World world = world();
        if (world == null) return stored.clone();
        Location resolved = stored.clone();
        resolved.setWorld(world);
        return resolved;
    }

    public boolean ready() {
        return world() != null && spawnOne != null && spawnTwo != null
                && schematicFile != null && !schematicFile.isBlank();
    }

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().getName().equalsIgnoreCase(worldName)) return false;
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(DuelMap other) {
        if (other == null || worldName == null || other.worldName() == null
                || !worldName.equalsIgnoreCase(other.worldName())) return false;
        return minX <= other.maxX() && maxX >= other.minX()
                && minY <= other.maxY() && maxY >= other.minY()
                && minZ <= other.maxZ() && maxZ >= other.minZ();
    }
}
