package de.walahi.smpcore.punishments;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {

    private static final Pattern PART = Pattern.compile("(\\d+)(mo|[smhdwy])", Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    /** Supports combinations such as 30m, 2h, 5d, 3w, 6mo, 1y and 1d12h. */
    public static Optional<Duration> parse(String input) {
        if (input == null || input.isBlank()) return Optional.empty();

        String normalized = input.toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher matcher = PART.matcher(normalized);
        long seconds = 0L;
        int consumed = 0;

        try {
            while (matcher.find()) {
                if (matcher.start() != consumed) return Optional.empty();
                long amount = Long.parseLong(matcher.group(1));
                if (amount <= 0) return Optional.empty();
                seconds = Math.addExact(seconds, Math.multiplyExact(amount, secondsFor(matcher.group(2))));
                consumed = matcher.end();
            }
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }

        if (consumed != normalized.length() || seconds <= 0) return Optional.empty();
        return Optional.of(Duration.ofSeconds(seconds));
    }

    private static long secondsFor(String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "s" -> 1L;
            case "m" -> 60L;
            case "h" -> 3_600L;
            case "d" -> 86_400L;
            case "w" -> 604_800L;
            case "mo" -> 2_592_000L; // 30 Tage
            case "y" -> 31_536_000L; // 365 Tage
            default -> throw new IllegalArgumentException("Unbekannte Zeiteinheit: " + unit);
        };
    }
}
