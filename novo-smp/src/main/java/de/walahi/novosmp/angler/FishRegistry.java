package de.walahi.novosmp.angler;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Fish IDs, not item names or lore, are the authority for all Angler systems. */
public final class FishRegistry {
    private final SMPCorePlugin plugin;
    private final NamespacedKey fishIdKey;
    private final Map<String, FishDefinition> definitions = new LinkedHashMap<>();

    public FishRegistry(SMPCorePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.fishIdKey = new NamespacedKey(plugin, "fish_id");
        reload();
    }

    public void reload() {
        definitions.clear();
        ConfigurationSection root = plugin.configs().angler().getConfigurationSection("fish");
        definitions.putAll(parse(root, plugin.getLogger()::warning,
                material -> material.isItem() && !material.isAir()));
    }

    static Map<String, FishDefinition> parse(ConfigurationSection root, Consumer<String> warning) {
        return parse(root, warning, material -> material.isItem() && !material.isAir());
    }

    static Map<String, FishDefinition> parse(ConfigurationSection root, Consumer<String> warning,
                                              Predicate<Material> validMaterial) {
        Map<String, FishDefinition> parsed = new LinkedHashMap<>();
        if (root == null) return parsed;
        ConfigurationSection defaultFish = root.getRoot() == null || root.getRoot().getDefaults() == null
                ? null : root.getRoot().getDefaults().getConfigurationSection("fish");
        Set<String> fishIds = new LinkedHashSet<>();
        if (defaultFish != null) fishIds.addAll(defaultFish.getKeys(false));
        fishIds.addAll(root.getKeys(false));
        for (String rawId : fishIds) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_]+")) {
                warning.accept("Ungültige Fish-ID in angler.yml: " + rawId);
                continue;
            }
            ConfigurationSection section = root.getConfigurationSection(rawId);
            ConfigurationSection defaults = defaultFish == null ? null : defaultFish.getConfigurationSection(rawId);
            if (section == null) section = defaults;
            if (section == null) continue;
            Material material = Material.matchMaterial(section.getString("material", "COD"));
            if (material == null || !validMaterial.test(material)) {
                warning.accept("Ungültiges Fisch-Material in angler.yml: " + rawId);
                continue;
            }
            long price = section.getLong("sell-price", 0L);
            if (price < 0L) {
                warning.accept("Negativer Fisch-Verkaufspreis in angler.yml: " + rawId);
                continue;
            }
            double weight = section.isSet("weight") ? section.getDouble("weight")
                    : defaults == null ? 0D : defaults.getDouble("weight", 0D);
            String difficulty = (section.isSet("difficulty") ? section.getString("difficulty", "easy")
                    : defaults == null ? "easy" : defaults.getString("difficulty", "easy")).toLowerCase(Locale.ROOT);
            if (!Double.isFinite(weight) || weight < 0D
                    || !Set.of("easy", "medium", "hard").contains(difficulty)) {
                warning.accept("Ungültiges Fanggewicht oder Minispiel-Schwierigkeit in angler.yml: " + rawId);
                continue;
            }
            parsed.put(id, new FishDefinition(id, section.getString("display-name", id), material,
                    section.getString("rarity", "common").toLowerCase(Locale.ROOT),
                    section.getString("rarity-display", "Häufig"),
                    section.getString("preferred-biomes", "Unbekannt"),
                    section.getStringList("biome-tags").stream().map(value -> value.toLowerCase(Locale.ROOT)).toList(),
                    section.getString("best-conditions", "Keine"),
                    section.getStringList("condition-tags").stream().map(value -> value.toLowerCase(Locale.ROOT)).toList(),
                    section.getBoolean("exclusive", false), price,
                    section.getString("color", "<white>"), section.getStringList("lore"),
                    weight, difficulty,
                    positiveMultiplier(newDouble(section, defaults, "biome-multiplier", 1D)),
                    positiveMultiplier(newDouble(section, defaults, "night-multiplier", 1D)),
                    positiveMultiplier(newDouble(section, defaults, "rain-multiplier", 1D)),
                    (section.isSet("exclusive-biomes") ? section.getStringList("exclusive-biomes")
                            : defaults == null ? java.util.List.<String>of() : defaults.getStringList("exclusive-biomes")).stream()
                            .map(value -> value.toLowerCase(Locale.ROOT)).toList()));
        }
        return parsed;
    }

    private static double positiveMultiplier(double value) {
        return Double.isFinite(value) && value > 0D ? value : 1D;
    }

    private static double newDouble(ConfigurationSection section, ConfigurationSection defaults,
                                    String key, double fallback) {
        return section.isSet(key) ? section.getDouble(key)
                : defaults == null ? fallback : defaults.getDouble(key, fallback);
    }

    public Collection<FishDefinition> definitions() { return java.util.List.copyOf(definitions.values()); }
    public FishDefinition find(String id) {
        return id == null ? null : definitions.get(id.toLowerCase(Locale.ROOT));
    }
    public NamespacedKey fishIdKey() { return fishIdKey; }

    public FishDefinition identify(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        // A normal Angler fish has only this one custom identity key. Do not whitelist
        // another custom/PDC item just because a fish ID was copied onto it.
        String id = meta.getPersistentDataContainer().get(fishIdKey, PersistentDataType.STRING);
        return resolve(definitions, id, item.getType(), meta.getPersistentDataContainer().getKeys(), fishIdKey);
    }

    static FishDefinition resolve(Map<String, FishDefinition> definitions, String id, Material material,
                                  Set<NamespacedKey> keys, NamespacedKey fishIdKey) {
        if (id == null || !keys.equals(Set.of(fishIdKey))) return null;
        FishDefinition definition = definitions.get(id.toLowerCase(Locale.ROOT));
        return definition != null && material == definition.material() ? definition : null;
    }
}
