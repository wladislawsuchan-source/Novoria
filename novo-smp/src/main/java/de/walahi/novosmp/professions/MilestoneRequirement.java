package de.walahi.novosmp.professions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record MilestoneRequirement(int level, long coinCost,
                                   Map<String, Long> materials,
                                   Map<String, Long> growths,
                                   Map<String, Long> mined,
                                   Map<String, Long> hunts,
                                   Map<String, Long> fish,
                                   Map<String, Long> skills) {
    public MilestoneRequirement {
        materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        growths = Collections.unmodifiableMap(new LinkedHashMap<>(growths));
        mined = Collections.unmodifiableMap(new LinkedHashMap<>(mined));
        hunts = Collections.unmodifiableMap(new LinkedHashMap<>(hunts));
        fish = Collections.unmodifiableMap(new LinkedHashMap<>(fish));
        skills = Collections.unmodifiableMap(new LinkedHashMap<>(skills));
    }

    public MilestoneRequirement(int level, long coinCost, Map<String, Long> materials,
                                Map<String, Long> growths, Map<String, Long> mined,
                                Map<String, Long> hunts, Map<String, Long> fish) {
        this(level, coinCost, materials, growths, mined, hunts, fish, Map.of());
    }

    public MilestoneRequirement(int level, long coinCost, Map<String, Long> materials,
                                Map<String, Long> growths, Map<String, Long> mined, Map<String, Long> hunts) {
        this(level, coinCost, materials, growths, mined, hunts, Map.of());
    }

    public MilestoneRequirement(int level, long coinCost,
                                Map<String, Long> materials,
                                Map<String, Long> growths,
                                Map<String, Long> mined) {
        this(level, coinCost, materials, growths, mined, Map.of());
    }

    public MilestoneRequirement(int level, long coinCost,
                                Map<String, Long> materials,
                                Map<String, Long> growths) {
        this(level, coinCost, materials, growths, Map.of(), Map.of());
    }

    public boolean empty() {
        return coinCost <= 0L && materials.isEmpty() && growths.isEmpty() && mined.isEmpty()
                && hunts.isEmpty() && fish.isEmpty() && skills.isEmpty();
    }
}
