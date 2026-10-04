package de.walahi.novosmp.quests;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class QuestState {
    static final class Daily {
        final UUID id;
        final UUID player;
        final LocalDate day;
        final int slot;
        final String definitionId;
        double progress;
        boolean completed;
        boolean keyDelivered;
        boolean dirty;
        long revision;
        long savedRevision;

        Daily(UUID id, UUID player, LocalDate day, int slot, String definitionId,
              double progress, boolean completed, boolean keyDelivered) {
            this.id = id;
            this.player = player;
            this.day = day;
            this.slot = slot;
            this.definitionId = definitionId;
            this.progress = progress;
            this.completed = completed;
            this.keyDelivered = keyDelivered;
        }
    }

    static final class Participant {
        final UUID player;
        String name;
        double progress;
        long reachedAt;
        boolean dirty;
        long revision;
        long savedRevision;

        Participant(UUID player, String name, double progress, long reachedAt) {
            this.player = player;
            this.name = name;
            this.progress = progress;
            this.reachedAt = reachedAt;
        }
    }

    static final class Global {
        final int slot;
        UUID id;
        String definitionId;
        long cooldownLeft;
        long activeSince;
        UUID winner;
        final Map<UUID, Participant> participants = new HashMap<>();
        boolean dirty;

        Global(int slot, UUID id, String definitionId, long cooldownLeft, long activeSince, UUID winner) {
            this.slot = slot;
            this.id = id;
            this.definitionId = definitionId;
            this.cooldownLeft = cooldownLeft;
            this.activeSince = activeSince;
            this.winner = winner;
        }

        boolean cooling() { return definitionId == null; }
        List<Participant> topThree() {
            return participants.values().stream().filter(p -> p.progress > 0)
                    .sorted(Comparator.comparingDouble((Participant p) -> p.progress).reversed()
                            .thenComparingLong(p -> p.reachedAt).thenComparing(p -> p.player))
                    .limit(3).toList();
        }
    }

    static final class DailySet {
        final LocalDate day;
        final List<Daily> quests = new ArrayList<>();
        DailySet(LocalDate day) { this.day = day; }
    }
}
