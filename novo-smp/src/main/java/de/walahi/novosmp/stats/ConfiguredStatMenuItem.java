package de.walahi.novosmp.stats;

import de.walahi.smpcore.gui.MaterialResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * One stats/leaderboard selector entry defined entirely by menus.yml.
 * Adding, removing, moving or restyling visible statistics must not require Java changes.
 */
record ConfiguredStatMenuItem(
        String key,
        String statPath,
        int slot,
        Material material,
        String name,
        List<String> lore,
        ValueFormat format
) {
    enum ValueFormat {
        NUMBER,
        PLAYTIME;

        static ValueFormat parse(String configured) {
            if (configured == null || configured.isBlank()) return NUMBER;
            try {
                return valueOf(configured.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return NUMBER;
            }
        }
    }

    static List<ConfiguredStatMenuItem> load(ConfigurationSection root,
                                              int inventorySize,
                                              Logger logger,
                                              String configPath) {
        if (root == null || inventorySize <= 0) return List.of();

        List<ConfiguredStatMenuItem> result = new ArrayList<>();
        Set<Integer> occupiedSlots = new HashSet<>();

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null || !section.getBoolean("enabled", true)) continue;

            String configuredStat = section.getString("stat");
            String statPath = configuredStat == null ? "" : configuredStat.trim();
            if (statPath.isEmpty()) {
                logger.warning(configPath + "." + key + " wird ignoriert: 'stat' fehlt.");
                continue;
            }

            int slot = section.getInt("slot", -1);
            if (slot < 0 || slot >= inventorySize) {
                logger.warning(configPath + "." + key + " wird ignoriert: ungültiger Slot " + slot + ".");
                continue;
            }
            Material material = MaterialResolver.resolve(section.getString("material"), null);
            if (material == null) {
                logger.warning(configPath + "." + key + " wird ignoriert: ungültiges/fehlendes Material.");
                continue;
            }
            if (!occupiedSlots.add(slot)) {
                logger.warning(configPath + "." + key + " wird ignoriert: Slot " + slot + " ist doppelt belegt.");
                continue;
            }

            String name = section.getString("name", key);
            List<String> lore = List.copyOf(section.getStringList("lore"));
            ValueFormat format = ValueFormat.parse(section.getString("format", "number"));

            result.add(new ConfiguredStatMenuItem(
                    key,
                    statPath,
                    slot,
                    material,
                    name,
                    lore,
                    format
            ));
        }
        return List.copyOf(result);
    }
}
