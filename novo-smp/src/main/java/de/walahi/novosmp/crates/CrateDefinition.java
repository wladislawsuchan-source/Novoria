package de.walahi.novosmp.crates;

import org.bukkit.Material;

import java.util.List;

public record CrateDefinition(String id, String displayName, String keyDisplayName, Material keyMaterial,
                              boolean enabled, List<CrateReward> rewards) {
    public CrateDefinition {
        keyDisplayName = keyDisplayName == null ? "" : keyDisplayName.trim();
        keyMaterial = keyMaterial == null ? Material.TRIPWIRE_HOOK : keyMaterial;
    }

    public double totalWeight() { return rewards.stream().mapToDouble(CrateReward::weight).sum(); }
}
