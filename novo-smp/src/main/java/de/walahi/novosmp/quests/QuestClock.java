package de.walahi.novosmp.quests;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

final class QuestClock {
    private QuestClock() { }

    static LocalDate day(Instant instant, ZoneId zone, int resetHour, int resetMinute) {
        ZonedDateTime now = instant.atZone(zone);
        return (now.toLocalTime().isBefore(java.time.LocalTime.of(resetHour, resetMinute))
                ? now.minusDays(1) : now).toLocalDate();
    }

    static long untilReset(Instant instant, ZoneId zone, int resetHour, int resetMinute) {
        return Duration.between(instant, day(instant, zone, resetHour, resetMinute)
                .plusDays(1).atTime(resetHour, resetMinute).atZone(zone).toInstant()).toMillis();
    }
}
