package de.walahi.novosmp.auction;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Objects;

/** Typed business rules from the legacy-compatible auction-house section in config.yml. */
final class AuctionSettings {
    private static final String ROOT = "auction-house.";

    private final FileConfiguration configuration;

    AuctionSettings(SMPCorePlugin plugin) {
        this.configuration = Objects.requireNonNull(plugin, "plugin").configs().main();
    }

    long minimumPrice() {
        return Math.max(1L, configuration.getLong(ROOT + "minimum-price", 1L));
    }

    long maximumPrice() {
        return Math.max(minimumPrice(),
                configuration.getLong(ROOT + "maximum-price", 1_000_000_000L));
    }

    long listingDurationHours() {
        return Math.max(1L, configuration.getLong(ROOT + "listing-duration-hours", 48L));
    }

    long expiryCheckSeconds() {
        return Math.max(30L, configuration.getLong(ROOT + "expiry-check-seconds", 60L));
    }

    int expiryBatchSize() {
        return Math.max(1, configuration.getInt(ROOT + "expiry-batch-size", 250));
    }

    int defaultListingLimit() {
        return Math.max(1, configuration.getInt(ROOT + "default-listing-limit", 5));
    }

    List<Integer> listingLimits() {
        return configuration.getIntegerList(ROOT + "listing-limits").stream()
                .filter(limit -> limit != null && limit > 0)
                .distinct()
                .sorted()
                .toList();
    }

    boolean soundsEnabled() {
        return configuration.getBoolean(ROOT + "sounds.enabled", true);
    }

    String soundName(String key) {
        return configuration.getString(ROOT + "sounds." + key + ".name");
    }

    float soundVolume(String key) {
        return (float) configuration.getDouble(ROOT + "sounds." + key + ".volume", 0.8D);
    }

    float soundPitch(String key) {
        return (float) configuration.getDouble(ROOT + "sounds." + key + ".pitch", 1.0D);
    }
}
