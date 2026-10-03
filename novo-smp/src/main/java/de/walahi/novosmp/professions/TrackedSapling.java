package de.walahi.novosmp.professions;

import org.bukkit.Material;

import java.util.UUID;

public record TrackedSapling(UUID worldId, int x, int y, int z, UUID ownerId,
                             int prestige, int milestone, String growthGroupId,
                             Material material, long plantedAt) {
}
