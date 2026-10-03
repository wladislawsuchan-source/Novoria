package de.walahi.novosmp.angler;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Random;

/** Immutable target plus a time-driven bouncing pointer. No Bukkit or database work per frame. */
public final class FishingGame {
    public enum Quality { GRAY, RED, ORANGE, YELLOW, GREEN }

    private final double targetStart;
    private final double green;
    private final double yellow;
    private final double orange;
    private final double red;
    private final long travelNanos;
    private final long timeoutNanos;
    private final long startedNanos;

    public FishingGame(FileConfiguration config, String difficulty, Random random, long startedNanos) {
        this(config, difficulty, random, startedNanos, 0);
    }

    public FishingGame(FileConfiguration config, String difficulty, Random random,
                       long startedNanos, int ruhigeHandLevel) {
        String path = "fishing.minigame." + difficulty + ".";
        double fallbackTravel = switch (difficulty) { case "hard" -> 1.1D; case "medium" -> 1.4D; default -> 1.8D; };
        double fallbackGreen = switch (difficulty) { case "hard" -> 5D; case "medium" -> 8D; default -> 10D; };
        double fallbackYellow = switch (difficulty) { case "hard" -> 3D; case "medium" -> 4D; default -> 6D; };
        double fallbackOrange = switch (difficulty) { case "hard" -> 4.5D; case "medium" -> 6D; default -> 8D; };
        double fallbackRed = switch (difficulty) { case "hard" -> 8D; case "medium" -> 10D; default -> 12D; };
        double g = percent(config.getDouble(path + "green", fallbackGreen));
        double y = percent(config.getDouble(path + "yellow-each", fallbackYellow));
        double o = percent(config.getDouble(path + "orange-each", fallbackOrange));
        double r = percent(config.getDouble(path + "red-each", fallbackRed));
        if (ruhigeHandLevel > 0) {
            String enchantPath = "enchants.ruhige-hand.levels." + ruhigeHandLevel + ".";
            g *= multiplier(config, enchantPath + "green-multiplier");
            y *= multiplier(config, enchantPath + "yellow-multiplier");
        }
        double total = g + 2D * (y + o + r);
        double scale = total > 1D ? 1D / total : 1D;
        this.green = g * scale;
        this.yellow = y * scale;
        this.orange = o * scale;
        this.red = r * scale;
        double width = this.green + 2D * (this.yellow + this.orange + this.red);
        this.targetStart = random.nextDouble() * Math.max(0D, 1D - width);
        this.travelNanos = (long) (seconds(config.getDouble(path + "travel-time-seconds", fallbackTravel), fallbackTravel) * 1_000_000_000D);
        this.timeoutNanos = (long) (seconds(config.getDouble("fishing.minigame.timeout-seconds", 5D), 5D) * 1_000_000_000D);
        this.startedNanos = startedNanos;
    }

    public double pointer(long nowNanos) {
        long elapsed = Math.max(0L, nowNanos - startedNanos);
        double phase = (elapsed % (2L * travelNanos)) / (double) travelNanos;
        return phase <= 1D ? phase : 2D - phase;
    }

    public boolean timedOut(long nowNanos) { return nowNanos - startedNanos >= timeoutNanos; }
    public double targetStart() { return targetStart; }

    public Quality quality(double point) {
        double relative = point - targetStart;
        if (relative < 0D) return Quality.GRAY;
        if (relative < red) return Quality.RED;
        relative -= red;
        if (relative < orange) return Quality.ORANGE;
        relative -= orange;
        if (relative < yellow) return Quality.YELLOW;
        relative -= yellow;
        if (relative < green) return Quality.GREEN;
        relative -= green;
        if (relative < yellow) return Quality.YELLOW;
        relative -= yellow;
        if (relative < orange) return Quality.ORANGE;
        relative -= orange;
        return relative < red ? Quality.RED : Quality.GRAY;
    }

    public String bar(int width, String pointer, String segment, long nowNanos) {
        int safeWidth = Math.max(9, Math.min(81, width));
        int pointerCell = Math.min(safeWidth - 1, (int) (pointer(nowNanos) * safeWidth));
        StringBuilder bar = new StringBuilder(safeWidth * 25);
        for (int index = 0; index < safeWidth; index++) {
            if (index == pointerCell) { bar.append("<white><bold>").append(pointer).append("</bold></white>"); continue; }
            Quality quality = quality((index + 0.5D) / safeWidth);
            String color = switch (quality) {
                case GRAY -> "dark_gray"; case RED -> "red"; case ORANGE -> "gold";
                case YELLOW -> "yellow"; case GREEN -> "green";
            };
            bar.append('<').append(color).append('>').append(segment).append("</").append(color).append('>');
        }
        return bar.toString();
    }

    private static double percent(double value) { return Double.isFinite(value) ? Math.max(0D, Math.min(100D, value)) / 100D : 0D; }
    private static double multiplier(FileConfiguration config, String path) {
        // An existing physical angler.yml may contain `enchants: {}`. Bukkit then
        // does not expose its newly bundled child values through getDouble alone.
        double bundled = config.getDefaults() == null ? 1D : config.getDefaults().getDouble(path, 1D);
        return config.getDouble(path, bundled);
    }
    private static double seconds(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.1D ? Math.min(60D, value) : fallback;
    }
}
