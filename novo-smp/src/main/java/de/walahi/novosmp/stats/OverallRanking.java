package de.walahi.novosmp.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request-local average of existing leaderboard ranks; never persisted. */
final class OverallRanking {
    record Entry(StatsSnapshot player, long rankSum, int categories,
                 int firstPlaces, int secondPlaces, int thirdPlaces) { }

    private static final Comparator<Entry> ORDER = Comparator
            .comparingLong(Entry::rankSum)
            .thenComparing(Comparator.comparingInt(Entry::firstPlaces).reversed())
            .thenComparing(Comparator.comparingInt(Entry::secondPlaces).reversed())
            .thenComparing(Comparator.comparingInt(Entry::thirdPlaces).reversed())
            .thenComparing(entry -> entry.player().name(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> entry.player().uuid());

    private OverallRanking() { }

    static List<Entry> calculate(List<StatsSnapshot> visible,
                                 List<StatsQueryService.Values> categories) {
        if (visible.isEmpty() || categories.isEmpty()) return List.of();
        List<Map<UUID, Integer>> ranks = new ArrayList<>(categories.size());
        for (StatsQueryService.Values category : categories) ranks.add(category.ranks());

        List<Entry> result = new ArrayList<>(visible.size());
        for (StatsSnapshot player : visible) {
            long sum = 0L;
            int first = 0, second = 0, third = 0;
            for (Map<UUID, Integer> category : ranks) {
                int rank = category.get(player.uuid());
                sum += rank;
                if (rank == 1) first++;
                else if (rank == 2) second++;
                else if (rank == 3) third++;
            }
            result.add(new Entry(player, sum, ranks.size(), first, second, third));
        }
        result.sort(ORDER);
        return List.copyOf(result);
    }

    static Map<UUID, Entry> byPlayer(List<Entry> entries) {
        Map<UUID, Entry> result = new HashMap<>(entries.size());
        for (Entry entry : entries) result.put(entry.player().uuid(), entry);
        return result;
    }
}
