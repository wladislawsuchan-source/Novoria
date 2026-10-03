package de.walahi.smpcore.gui;

import java.text.NumberFormat;
import java.time.Duration;
import java.util.Locale;

/** Shared display formatting for menus. */
public final class MenuFormat {
    private static final ThreadLocal<NumberFormat> GERMAN_INTEGER = ThreadLocal.withInitial(() -> {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.GERMANY);
        format.setGroupingUsed(true);
        format.setMaximumFractionDigits(0);
        return format;
    });

    private MenuFormat() {
    }

    public static String integer(long value) {
        return GERMAN_INTEGER.get().format(value);
    }

    public static String duration(Duration duration) {
        long seconds = Math.max(0L, duration == null ? 0L : duration.getSeconds());
        return durationSeconds(seconds);
    }

    public static String durationSeconds(long seconds) {
        long safe = Math.max(0L, seconds);
        long days = safe / 86_400L;
        long hours = (safe % 86_400L) / 3_600L;
        long minutes = (safe % 3_600L) / 60L;
        long remainingSeconds = safe % 60L;
        if (days > 0L) return days + " T. " + hours + " Std.";
        if (hours > 0L) return hours + " Std. " + minutes + " Min.";
        if (minutes > 0L) return minutes + " Min. " + remainingSeconds + " Sek.";
        return remainingSeconds + " Sek.";
    }
}
