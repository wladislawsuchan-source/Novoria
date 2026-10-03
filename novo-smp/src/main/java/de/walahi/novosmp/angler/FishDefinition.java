package de.walahi.novosmp.angler;

import org.bukkit.Material;

import java.util.List;

public record FishDefinition(String id, String displayName, Material material, String rarity,
                             String rarityDisplay, String preferredBiomes, List<String> biomeTags, String bestConditions,
                             List<String> conditionTags,
                             boolean exclusive, long sellPrice, String color, List<String> lore,
                             double weight, String difficulty, double biomeMultiplier,
                             double nightMultiplier, double rainMultiplier, List<String> exclusiveBiomes) {
    public FishDefinition {
        lore = List.copyOf(lore);
        biomeTags = List.copyOf(biomeTags);
        conditionTags = List.copyOf(conditionTags);
        exclusiveBiomes = List.copyOf(exclusiveBiomes);
    }
}
