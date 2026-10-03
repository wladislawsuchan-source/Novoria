package de.walahi.novosmp.duel;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared marker for teleports initiated by the duel subsystem itself. */
final class DuelTeleportRegistry {
    private static final Set<UUID> ALLOWED = ConcurrentHashMap.newKeySet();

    private DuelTeleportRegistry() {}

    static void allow(UUID playerId) {
        if (playerId != null) ALLOWED.add(playerId);
    }

    static void disallow(UUID playerId) {
        if (playerId != null) ALLOWED.remove(playerId);
    }

    static boolean isAllowed(UUID playerId) {
        return playerId != null && ALLOWED.contains(playerId);
    }

    static void clear() {
        ALLOWED.clear();
    }
}
