package de.walahi.novosmp.daily;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One independently configurable part of a Daily reward. */
public record DailyRewardTier(long coins, long lumis, Map<String, Integer> keys, List<String> commands) {
    public static final DailyRewardTier EMPTY = new DailyRewardTier(0L, 0L, Map.of(), List.of());

    public DailyRewardTier {
        coins = Math.max(0L, coins);
        lumis = Math.max(0L, lumis);
        Map<String, Integer> safeKeys = new LinkedHashMap<>();
        if (keys != null) {
            keys.forEach((id, amount) -> {
                if (id != null && !id.isBlank() && amount != null && amount > 0) {
                    safeKeys.merge(id.trim().toLowerCase(java.util.Locale.ROOT), amount, Integer::sum);
                }
            });
        }
        keys = java.util.Collections.unmodifiableMap(safeKeys);
        commands = commands == null ? List.of() : commands.stream()
                .filter(command -> command != null && !command.isBlank())
                .map(String::trim)
                .toList();
    }

    public DailyRewardTier plus(DailyRewardTier other) {
        if (other == null) return this;
        Map<String, Integer> mergedKeys = new LinkedHashMap<>(keys);
        other.keys.forEach((id, amount) -> mergedKeys.merge(id, amount, Integer::sum));
        java.util.ArrayList<String> mergedCommands = new java.util.ArrayList<>(commands);
        mergedCommands.addAll(other.commands);
        return new DailyRewardTier(safeAdd(coins, other.coins), safeAdd(lumis, other.lumis),
                mergedKeys, mergedCommands);
    }

    public boolean empty() {
        return coins <= 0L && lumis <= 0L && keys.isEmpty() && commands.isEmpty();
    }

    private static long safeAdd(long first, long second) {
        if (Long.MAX_VALUE - first < second) return Long.MAX_VALUE;
        return first + second;
    }
}
