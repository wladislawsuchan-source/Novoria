package de.walahi.novosmp.stats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Standalone deterministic checks; run with the compiled module classpath. */
public final class OverallRankingCheck {
    private OverallRankingCheck() { }

    public static void main(String[] args) {
        averageAndZero();
        ordering();
        tieBreaks();
        filteredPlayers();
        System.out.println("OVERALL_RANKING=PASS");
    }

    private static void averageAndZero() {
        List<StatsSnapshot> players = players("A", "B", "C", "D", "E");
        List<StatsQueryService.Values> categories = List.of(
                values(players, 0, 1, 2, 3, 4),
                values(players, 2, 0, 1, 3, 4),
                values(players, 4, 0, 1, 2, 3));
        List<OverallRanking.Entry> ranked = OverallRanking.calculate(players, categories);
        OverallRanking.Entry a = OverallRanking.byPlayer(ranked).get(players.getFirst().uuid());
        check(a.rankSum() == 9 && a.categories() == 3, "1/3/5 ergibt 3,0");
        check(new StatsFormatter(null).averageRank(a.rankSum(), a.categories()).equals("3,0"),
                "deutsches Dezimalformat");
        check(categories.get(2).value(players.getFirst().uuid()) == 1L
                && categories.get(2).ranks().get(players.getFirst().uuid()) == 5,
                "letzter Wert wird bewertet");

        StatsQueryService.Values zero = values(players, 0, 1, 2, 3, 4);
        Map<UUID, Long> withZero = new HashMap<>(zero.byPlayer());
        withZero.put(players.getFirst().uuid(), 0L);
        StatsQueryService.Values zeroCategory = new StatsQueryService.Values(players, withZero);
        OverallRanking.Entry zeroRank = OverallRanking.byPlayer(
                OverallRanking.calculate(players, List.of(categories.getFirst(), zeroCategory)))
                .get(players.getFirst().uuid());
        check(zeroRank.categories() == 2 && zeroCategory.ranks().get(players.getFirst().uuid()) == 5,
                "0-Wert bleibt als schlechter Rang enthalten");
    }

    private static void ordering() {
        List<StatsSnapshot> players = players("A", "B", "C", "D", "E", "F");
        List<StatsQueryService.Values> categories = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            int a = index < 4 || index >= 8 ? 2 : 3;
            int c = index < 4 ? 3 : index == 4 ? 4 : index < 8 ? 2 : 4;
            int b = index == 0 ? 4 : index >= 8 ? 6 : 5;
            int[] ranks = new int[6];
            ranks[0] = a; ranks[1] = b; ranks[2] = c;
            int next = 1;
            for (int filler = 3; filler < 6; filler++) {
                while (next == a || next == b || next == c) next++;
                ranks[filler] = next++;
            }
            categories.add(values(players, subtractOne(ranks)));
        }
        List<OverallRanking.Entry> ranked = OverallRanking.calculate(players, categories);
        Map<UUID, OverallRanking.Entry> byPlayer = OverallRanking.byPlayer(ranked);
        check(byPlayer.get(players.get(0).uuid()).rankSum() == 24, "A Ø 2,4");
        check(byPlayer.get(players.get(1).uuid()).rankSum() == 51, "B Ø 5,1");
        check(byPlayer.get(players.get(2).uuid()).rankSum() == 30, "C Ø 3,0");
        check(ranked.indexOf(byPlayer.get(players.get(0).uuid()))
                < ranked.indexOf(byPlayer.get(players.get(2).uuid()))
                && ranked.indexOf(byPlayer.get(players.get(2).uuid()))
                < ranked.indexOf(byPlayer.get(players.get(1).uuid())), "A vor C vor B");
    }

    private static void tieBreaks() {
        List<StatsSnapshot> players = players("A", "B", "C");
        List<StatsQueryService.Values> firstPlace = List.of(
                values(players, 0, 1, 2), values(players, 2, 1, 0));
        List<OverallRanking.Entry> ranked = OverallRanking.calculate(players, firstPlace);
        Map<UUID, OverallRanking.Entry> byPlayer = OverallRanking.byPlayer(ranked);
        check(ranked.indexOf(byPlayer.get(players.get(0).uuid()))
                < ranked.indexOf(byPlayer.get(players.get(1).uuid())),
                "mehr #1 gewinnt bei gleichem Schnitt");

        List<StatsSnapshot> four = players("A", "B", "C", "D");
        List<OverallRanking.Entry> second = OverallRanking.calculate(four, List.of(
                values(four, 0, 2, 1, 3),
                values(four, 1, 0, 2, 3),
                values(four, 3, 2, 0, 1)));
        Map<UUID, OverallRanking.Entry> secondByPlayer = OverallRanking.byPlayer(second);
        check(second.indexOf(secondByPlayer.get(four.get(0).uuid()))
                < second.indexOf(secondByPlayer.get(four.get(1).uuid())),
                "bei gleichen #1 gewinnt mehr #2");

        List<StatsSnapshot> six = players("A", "B", "C", "D", "E", "F");
        List<OverallRanking.Entry> third = OverallRanking.calculate(six, List.of(
                values(six, 0, 3, 1, 2, 4, 5),
                values(six, 1, 0, 2, 3, 4, 5),
                values(six, 2, 1, 0, 3, 4, 5),
                values(six, 5, 4, 0, 1, 2, 3)));
        Map<UUID, OverallRanking.Entry> thirdByPlayer = OverallRanking.byPlayer(third);
        check(third.indexOf(thirdByPlayer.get(six.get(0).uuid()))
                < third.indexOf(thirdByPlayer.get(six.get(1).uuid())),
                "bei gleichen #1/#2 gewinnt mehr #3");

        List<StatsSnapshot> named = players("Zed", "Alpha");
        List<StatsQueryService.Values> exactTie = List.of(
                values(named, 0, 1), values(named, 1, 0));
        check(OverallRanking.calculate(named, exactTie).getFirst().player().name().equals("Alpha"),
                "voller Gleichstand nach Name");
    }

    private static void filteredPlayers() {
        List<StatsSnapshot> visible = players("A", "B");
        List<StatsSnapshot> all = new ArrayList<>(visible);
        all.addAll(players("Hidden", "TooNew"));
        StatsQueryService.Values onlyVisible = values(visible, 0, 1);
        check(OverallRanking.calculate(visible, List.of(onlyVisible)).size() == 2
                && onlyVisible.ranks().get(visible.get(1).uuid()) == 2,
                "bereits gefilterte Spieler beeinflussen keine Ränge");
        check(OverallRanking.calculate(all, List.of(values(all, 2, 3, 0, 1))).size() == 4,
                "Filtereingabe bleibt explizit");
    }

    private static int[] subtractOne(int[] ranks) {
        int[] positions = ranks.clone();
        for (int index = 0; index < positions.length; index++) positions[index]--;
        return positions;
    }

    private static StatsQueryService.Values values(List<StatsSnapshot> players, int... positions) {
        Map<UUID, Long> byPlayer = new HashMap<>();
        for (int index = 0; index < players.size(); index++)
            byPlayer.put(players.get(index).uuid(), (long) players.size() - positions[index]);
        return new StatsQueryService.Values(players, byPlayer);
    }

    private static List<StatsSnapshot> players(String... names) {
        List<StatsSnapshot> result = new ArrayList<>();
        for (String name : names) result.add(new StatsSnapshot(UUID.nameUUIDFromBytes(name.getBytes()), name,
                0, 0, 0, 0, 3600, 0, 0, 0, 0, 0, 0, 0));
        return result;
    }

    private static void check(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
}
