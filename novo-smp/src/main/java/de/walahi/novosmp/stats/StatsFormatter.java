package de.walahi.novosmp.stats;

import de.walahi.novosmp.NovoSMPPlugin;

import java.util.Locale;

/** Shared formatting for personal stats, leaderboards, scoreboard and tablist. */
final class StatsFormatter {
    private final NovoSMPPlugin plugin;

    StatsFormatter(NovoSMPPlugin plugin) {
        this.plugin = plugin;
    }

    String number(long value) {
        long safeValue = Math.max(0L, value);
        boolean compact = plugin.configs().menus().getBoolean("stats.menu.number-format.compact", true);
        if (!compact || safeValue < 1_000L) {
            return String.format(Locale.GERMANY, "%,d", safeValue);
        }

        String thousand = plugin.configs().menus().getString("stats.menu.number-format.suffixes.thousand", "K");
        String million = plugin.configs().menus().getString("stats.menu.number-format.suffixes.million", "M");
        String billion = plugin.configs().menus().getString("stats.menu.number-format.suffixes.billion", "B");
        String trillion = plugin.configs().menus().getString("stats.menu.number-format.suffixes.trillion", "T");
        String decimalSeparator = plugin.configs().menus().getString("stats.menu.number-format.decimal-separator", ".");
        int decimals = Math.max(0, Math.min(3,
                plugin.configs().menus().getInt("stats.menu.number-format.decimals", 1)));

        double divisor;
        String suffix;
        if (safeValue >= 1_000_000_000_000L) {
            divisor = 1_000_000_000_000D;
            suffix = trillion;
        } else if (safeValue >= 1_000_000_000L) {
            divisor = 1_000_000_000D;
            suffix = billion;
        } else if (safeValue >= 1_000_000L) {
            divisor = 1_000_000D;
            suffix = million;
        } else {
            divisor = 1_000D;
            suffix = thousand;
        }

        String formatted = String.format(Locale.US, "%." + decimals + "f", safeValue / divisor);
        if (decimals > 0) formatted = formatted.replaceAll("\\.?0+$", "");
        if (!".".equals(decimalSeparator)) formatted = formatted.replace(".", decimalSeparator);
        return formatted + suffix;
    }

    String playtime(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        long hours = safeSeconds / 3_600L;
        long minutes = (safeSeconds % 3_600L) / 60L;
        if (hours > 0L) return hours + "h " + minutes + "m";
        if (minutes > 0L) return minutes + "m";
        return safeSeconds + "s";
    }

    String averageRank(long rankSum, int categories) {
        return categories <= 0 ? "-" : String.format(Locale.GERMANY, "%.1f", rankSum / (double) categories);
    }

    String escape(String text) {
        return text == null ? "" : text.replace("<", "\\<");
    }
}
