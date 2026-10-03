package de.walahi.novosmp.professions;

import java.util.Map;

public record LevelReward(int level, long coins, long lumis, Map<String, Integer> customItems) {
    public LevelReward {
        customItems = Map.copyOf(customItems);
    }
}
