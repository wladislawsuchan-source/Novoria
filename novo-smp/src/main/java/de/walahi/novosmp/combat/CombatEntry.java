package de.walahi.novosmp.combat;

import java.util.UUID;

/** Minimal timestamp-only runtime state. All access happens on the server thread. */
record CombatEntry(long combatUntil, UUID lastOpponent) {
    boolean active(long now) {
        return combatUntil > now;
    }
}
