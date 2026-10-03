package de.walahi.novosmp.angler;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.Random;

/** Cached, gated loot-category roll. Contents of non-fish categories are intentionally not defined here. */
public final class FishingLootPoolSelector {
    public enum Pool { FISH, JUNK, TREASURE, RARE, EPIC, LEGENDARY }
    public record Result(Pool main, Pool extra) { }

    private static final Pool[] POOLS = Pool.values();
    private final double[][] base = new double[7][POOLS.length]; // 0: no Angler, 1..6: P0..P5
    private final double[][] qualityFactors = new double[FishingGame.Quality.values().length][POOLS.length];
    private final double[] extraRollChances = new double[6];

    public FishingLootPoolSelector(FileConfiguration config) {
        for (int tier = 0; tier < base.length; tier++) {
            String prefix = "loot.pools." + (tier == 0 ? "no-angler" : "prestige-" + (tier - 1)) + ".";
            for (Pool pool : POOLS) {
                String key = prefix + pool.name().toLowerCase(Locale.ROOT);
                base[tier][pool.ordinal()] = safe(config.getDouble(key, 0D));
            }
        }
        for (FishingGame.Quality quality : FishingGame.Quality.values()) {
            for (Pool pool : POOLS) {
                String suffix = pool.name().toLowerCase(Locale.ROOT);
                qualityFactors[quality.ordinal()][pool.ordinal()] = safe(config.getDouble(
                        "loot.quality-modifiers." + quality.name().toLowerCase(Locale.ROOT) + "." + suffix, 1D));
            }
        }
        for (int level = 1; level <= 5; level++)
            extraRollChances[level] = Math.min(1D, safe(config.getDouble(
                    "loot.luck-of-the-sea.extra-roll-chance." + level, level * 0.02D)));
    }

    public boolean unlocked(Pool pool, boolean angler, int prestige) {
        return switch (pool) {
            case FISH, JUNK, TREASURE -> true;
            case RARE -> angler && prestige >= 1;
            case EPIC -> angler && prestige >= 3;
            case LEGENDARY -> angler && prestige >= 5;
        };
    }

    public double weight(Pool pool, boolean angler, int prestige, FishingGame.Quality quality) {
        if (quality == FishingGame.Quality.GRAY || !unlocked(pool, angler, prestige)) return 0D;
        int tier = angler ? Math.max(1, Math.min(6, prestige + 1)) : 0;
        double result = base[tier][pool.ordinal()] * qualityFactors[quality.ordinal()][pool.ordinal()];
        return Double.isFinite(result) && result > 0D ? result : 0D;
    }

    /** Weights are normalized by dividing the random draw by their sum. */
    public Pool choose(boolean angler, int prestige, FishingGame.Quality quality, Random random) {
        if (quality == FishingGame.Quality.GRAY) return null;
        double[] weights = new double[POOLS.length];
        double total = 0D;
        for (Pool pool : POOLS) {
            weights[pool.ordinal()] = weight(pool, angler, prestige, quality);
            total += weights[pool.ordinal()];
        }
        if (!(total > 0D) || !Double.isFinite(total)) return null;
        double draw = random.nextDouble() * total;
        Pool lastPositive = null;
        for (Pool pool : POOLS) {
            if (weights[pool.ordinal()] <= 0D) continue;
            lastPositive = pool;
            draw -= weights[pool.ordinal()];
            if (draw < 0D) return pool;
        }
        return lastPositive; // Handles only floating-point rounding at the upper boundary.
    }

    public double extraRollChance(int luckLevel) {
        return extraRollChances[Math.max(0, Math.min(5, luckLevel))];
    }

    /** One main roll and at most one independent extra roll; an extra roll never recurses. */
    public Result roll(boolean angler, int prestige, FishingGame.Quality quality, int luckLevel, Random random) {
        if (quality == FishingGame.Quality.GRAY) return new Result(null, null);
        Pool main = choose(angler, prestige, quality, random);
        double chance = extraRollChance(luckLevel);
        Pool extra = main != null && chance > 0D && random.nextDouble() < chance
                ? choose(angler, prestige, quality, random) : null;
        return new Result(main, extra);
    }

    private static double safe(double value) { return Double.isFinite(value) && value >= 0D ? value : 0D; }
}
