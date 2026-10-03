package de.walahi.novosmp.vote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Sanitized Votifier payload with a deterministic duplicate-protection id. */
record VotePayload(String id, String serviceName, String playerName, String address,
                   String timestamp, long receivedAt) {

    static VotePayload create(String serviceName, String playerName, String address,
                              String timestamp, long receivedAt) {
        String service = clean(serviceName, "UnknownService", 128);
        String player = clean(playerName, "", 64);
        String sourceAddress = clean(address, "unknown", 128);
        String sourceTimestamp = clean(timestamp, "", 64);
        String fingerprintTimestamp = weakTimestamp(sourceTimestamp)
                ? "received-minute:" + (receivedAt / 60_000L)
                : sourceTimestamp;
        String fingerprint = service.toLowerCase(Locale.ROOT) + '\0'
                + player.toLowerCase(Locale.ROOT) + '\0'
                + sourceAddress + '\0' + fingerprintTimestamp;
        return new VotePayload(sha256(fingerprint), service, player, sourceAddress, sourceTimestamp, receivedAt);
    }

    boolean valid() {
        return !playerName.isBlank();
    }

    private static boolean weakTimestamp(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.equals("0") || normalized.equals("none") || normalized.equals("null");
    }

    private static String clean(String value, String fallback, int maxLength) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) result = fallback;
        return result.length() <= maxLength ? result : result.substring(0, maxLength);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 ist auf dieser JVM nicht verfügbar", impossible);
        }
    }
}
