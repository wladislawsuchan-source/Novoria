package de.walahi.smpcore.punishments;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class PunishmentFormatter {

    private PunishmentFormatter() {
    }

    public static String remaining(Punishment punishment, Instant now) {
        if (punishment.permanent()) return "Permanent";
        Duration duration = Duration.between(now, punishment.expiresAt());
        if (duration.isNegative() || duration.isZero()) return "Abgelaufen";
        return duration(duration);
    }

    public static String duration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        seconds %= 3_600;
        long minutes = seconds / 60;
        seconds %= 60;

        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + " Tag" + (days == 1 ? "" : "e"));
        if (hours > 0) parts.add(hours + " Stunde" + (hours == 1 ? "" : "n"));
        if (minutes > 0) parts.add(minutes + " Minute" + (minutes == 1 ? "" : "n"));
        if (parts.isEmpty()) parts.add(seconds + " Sekunde" + (seconds == 1 ? "" : "n"));
        return String.join(", ", parts);
    }
}
