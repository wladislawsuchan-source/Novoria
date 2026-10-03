package de.walahi.novosmp.angler;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Fish-pool weights only; no treasure or other loot rolls. */
public final class FishingPool {
    public record Conditions(String biome, boolean night, boolean rain) { }

    private final Map<String, List<String>> biomeGroups = new LinkedHashMap<>();

    public FishingPool(FileConfiguration config) {
        ConfigurationSection groups = config.getConfigurationSection("fishing.biome-groups");
        ConfigurationSection defaults = config.getDefaults() == null ? null
                : config.getDefaults().getConfigurationSection("fishing.biome-groups");
        Set<String> names = new LinkedHashSet<>();
        if (defaults != null) names.addAll(defaults.getKeys(false));
        if (groups != null) names.addAll(groups.getKeys(false));
        for (String name : names) {
            biomeGroups.put(name.toLowerCase(Locale.ROOT), config.getStringList("fishing.biome-groups." + name)
                    .stream().map(value -> value.toLowerCase(Locale.ROOT)).toList());
        }
    }

    public double weight(FishDefinition fish, Conditions conditions) {
        if (fish.weight() <= 0D) return 0D;
        String biome = conditions.biome().toLowerCase(Locale.ROOT);
        if (fish.exclusive()) {
            return matchesAny(fish.exclusiveBiomes(), biome) ? fish.weight() : 0D;
        }
        double weight = fish.weight();
        if (fish.biomeTags().stream().anyMatch(group -> matchesAny(biomeGroups.getOrDefault(group, List.of()), biome)))
            weight *= fish.biomeMultiplier();
        if (conditions.night() && fish.conditionTags().contains("night")) weight *= fish.nightMultiplier();
        if (conditions.rain() && fish.conditionTags().contains("rain")) weight *= fish.rainMultiplier();
        return Double.isFinite(weight) && weight > 0D ? weight : 0D;
    }

    public FishDefinition choose(Collection<FishDefinition> definitions, Conditions conditions, Random random) {
        List<FishDefinition> available = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0D;
        for (FishDefinition fish : definitions) {
            double weight = weight(fish, conditions);
            if (weight <= 0D) continue;
            available.add(fish);
            weights.add(weight);
            total += weight;
        }
        if (available.isEmpty() || !Double.isFinite(total)) return null;
        double roll = random.nextDouble() * total;
        for (int index = 0; index < available.size(); index++) {
            roll -= weights.get(index);
            if (roll < 0D) return available.get(index);
        }
        return available.getLast();
    }

    private boolean matchesAny(List<String> patterns, String biome) {
        for (String pattern : patterns) {
            if (pattern.equals(biome) || pattern.startsWith("*") && biome.endsWith(pattern.substring(1))) return true;
        }
        return false;
    }
}
