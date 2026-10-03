package de.walahi.smpcore.bootstrap;

import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Collects startup phase timings and prints one compact startup summary.
 */
public final class StartupLogger {

    private final Plugin plugin;
    private final long startupStartedNanos = System.nanoTime();
    private final Map<String, Long> phases = new LinkedHashMap<>();
    private long phaseStartedNanos = startupStartedNanos;

    public StartupLogger(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Marks the previous phase as complete and starts timing the next one. */
    public void phase(String completedPhase) {
        long now = System.nanoTime();
        phases.put(completedPhase, elapsedMillis(phaseStartedNanos, now));
        phaseStartedNanos = now;
    }

    /** Prints all recorded phases and the complete startup duration. */
    public void complete(String mode) {
        long totalMillis = elapsedMillis(startupStartedNanos, System.nanoTime());
        plugin.getLogger().info("========================================");
        plugin.getLogger().info("SMPCore v" + plugin.getPluginMeta().getVersion() + " | " + mode);
        for (Map.Entry<String, Long> entry : phases.entrySet()) {
            plugin.getLogger().info("[OK] " + entry.getKey() + " (" + entry.getValue() + " ms)");
        }
        plugin.getLogger().info("Startup abgeschlossen in " + totalMillis + " ms");
        plugin.getLogger().info("========================================");
    }

    private static long elapsedMillis(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
    }
}
