package de.walahi.smpcore.api;

import org.bukkit.Bukkit;

import java.util.Optional;

/** Safe access helper for plugins integrating with SMPCore. */
public final class SMPCoreApiProvider {
    private SMPCoreApiProvider() {
    }

    public static Optional<SMPCoreApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(SMPCoreApi.class));
    }

    public static SMPCoreApi require() {
        return get().orElseThrow(() -> new IllegalStateException(
                "SMPCore API is not available. Ensure SMPCore is enabled and declared as a dependency."
        ));
    }
}
