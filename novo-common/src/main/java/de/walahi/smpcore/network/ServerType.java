package de.walahi.smpcore.network;

import java.util.Locale;

public enum ServerType {
    HUB, SMP;

    public static ServerType fromConfig(String value) {
        if (value == null) return HUB;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return HUB;
        }
    }
}
