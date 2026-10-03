package de.walahi.novosmp.angler;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Transient combo; persistent milestone records are handled by ProfessionManager. */
public final class FishingComboTracker {
    public record Result(int combo, double multiplier) { }
    private record State(int combo, long lastAttemptMillis) { }

    private final Map<UUID, State> states = new HashMap<>();
    private long timeoutMillis;
    private final TreeMap<Integer, Double> tiers = new TreeMap<>();
    private final Map<Integer, List<Integer>> concentrationThresholds = new HashMap<>();

    public FishingComboTracker(FileConfiguration config) { reload(config); }

    public void reload(FileConfiguration config) {
        timeoutMillis = Math.max(1L, config.getLong("fishing.combo.timeout-seconds", 60L)) * 1_000L;
        tiers.clear();
        ConfigurationSection local = config.getConfigurationSection("fishing.combo.tiers");
        ConfigurationSection defaults = config.getDefaults() == null ? null
                : config.getDefaults().getConfigurationSection("fishing.combo.tiers");
        if (defaults != null) readTiers(config, defaults);
        if (local != null) readTiers(config, local);
        concentrationThresholds.clear();
        String path = "enchants.konzentration.levels";
        ConfigurationSection localLevels = config.getConfigurationSection(path);
        ConfigurationSection defaultLevels = config.getDefaults() == null ? null
                : config.getDefaults().getConfigurationSection(path);
        Set<String> keys = new HashSet<>();
        if (defaultLevels != null) keys.addAll(defaultLevels.getKeys(false));
        if (localLevels != null) keys.addAll(localLevels.getKeys(false));
        for (String key : keys) {
            try {
                int level = Integer.parseInt(key);
                if (level <= 0) continue;
                String levelPath = path + "." + key;
                List<Integer> thresholds = config.getIntegerList(levelPath);
                if (thresholds.isEmpty() && config.getDefaults() != null)
                    thresholds = config.getDefaults().getIntegerList(levelPath);
                if (thresholds.size() != tiers.size()) continue;
                int previous = 0;
                boolean valid = true;
                for (int threshold : thresholds) {
                    if (threshold <= previous) { valid = false; break; }
                    previous = threshold;
                }
                if (valid) concentrationThresholds.put(level, List.copyOf(thresholds));
            } catch (NumberFormatException ignored) { }
        }
    }

    private void readTiers(FileConfiguration config, ConfigurationSection section) {
        for (String key : section.getKeys(false)) {
            try {
                int threshold = Integer.parseInt(key);
                String path = "fishing.combo.tiers." + key;
                double bundled = config.getDefaults() == null ? 1D
                        : config.getDefaults().getDouble(path, 1D);
                double multiplier = config.getDouble(path, bundled);
                if (threshold > 0 && Double.isFinite(multiplier) && multiplier >= 1D)
                    tiers.put(threshold, multiplier);
            } catch (NumberFormatException ignored) { }
        }
    }

    public void attempt(UUID playerId, long nowMillis) {
        State previous = states.get(playerId);
        int combo = previous == null || nowMillis - previous.lastAttemptMillis() >= timeoutMillis
                ? 0 : previous.combo();
        states.put(playerId, new State(combo, nowMillis));
    }

    /** Combo rises before this catch's XP tier is chosen. */
    public Result finish(UUID playerId, FishingGame.Quality quality) {
        return finish(playerId, quality, 0);
    }

    public Result finish(UUID playerId, FishingGame.Quality quality, int concentrationLevel) {
        return finish(playerId, quality, concentrationLevel, false);
    }

    public Result finish(UUID playerId, FishingGame.Quality quality, int concentrationLevel,
                         boolean doubleGreenCombo) {
        State state = states.get(playerId);
        int combo = state == null ? 0 : state.combo();
        if (quality == FishingGame.Quality.GRAY) combo = 0;
        else if (quality != FishingGame.Quality.RED)
            combo += quality == FishingGame.Quality.GREEN && doubleGreenCombo ? 2 : 1;
        states.put(playerId, new State(combo, state == null ? System.currentTimeMillis() : state.lastAttemptMillis()));
        List<Integer> thresholds = concentrationThresholds.get(concentrationLevel);
        if (thresholds == null) {
            Map.Entry<Integer, Double> tier = tiers.floorEntry(combo);
            return new Result(combo, tier == null ? 1D : tier.getValue());
        }
        double multiplier = 1D;
        int index = 0;
        for (double tierMultiplier : tiers.values()) {
            if (combo < thresholds.get(index++)) break;
            multiplier = tierMultiplier;
        }
        return new Result(combo, multiplier);
    }

    public void reset(UUID playerId) { states.remove(playerId); }
    public int combo(UUID playerId, long nowMillis) {
        State state = states.get(playerId);
        if (state == null) return 0;
        if (nowMillis - state.lastAttemptMillis() >= timeoutMillis) { reset(playerId); return 0; }
        return state.combo();
    }
}
