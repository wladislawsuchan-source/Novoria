package de.walahi.smpcore.lifecycle;

import de.walahi.smpcore.HologramManager;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.function.Supplier;

/** Handles delayed hologram spawning while worlds and Citizens finish loading. */
public final class HologramLifecycle implements Listener {
    private final JavaPlugin plugin;
    private final Supplier<HologramManager> managerSupplier;
    private BukkitTask startupTask;

    public HologramLifecycle(JavaPlugin plugin, Supplier<HologramManager> managerSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.managerSupplier = Objects.requireNonNull(managerSupplier, "managerSupplier");
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        startWatcher();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        HologramManager manager = managerSupplier.get();
        if (manager != null) manager.respawnHologramsInChunk(event.getChunk());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HologramManager manager = managerSupplier.get();
            if (manager == null) return;
            int loaded = manager.spawnAvailable();
            plugin.getLogger().info("Hologramme nach WorldLoad geprüft: " + loaded + " aktiv.");
        }, 10L);
    }

    public void shutdown() {
        if (startupTask != null) {
            startupTask.cancel();
            startupTask = null;
        }
    }

    private void startWatcher() {
        shutdown();
        HologramManager manager = managerSupplier.get();
        if (manager == null) return;

        manager.reloadAll();
        final int[] attempts = {0};
        startupTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            HologramManager current = managerSupplier.get();
            if (current == null) {
                shutdown();
                return;
            }
            attempts[0]++;
            int active = current.spawnAvailable();
            int configured = current.configuredEnabledCount();
            int resolvable = current.resolvableEnabledCount();
            if (resolvable < configured && attempts[0] < 60) return;

            if (resolvable < configured) {
                plugin.getLogger().warning("Nur " + resolvable + "/" + configured
                        + " Hologramme sind auflösbar. Prüfe Weltnamen, NPC-IDs, Crates und Textzeilen in holograms.yml.");
            } else {
                plugin.getLogger().info("Hologramme vollständig auflösbar (" + resolvable + "/" + configured
                        + "); aktuell " + active + " als TextDisplay geladen (Chunks werden dynamisch geladen).");
            }
            shutdown();
        }, 20L, 20L);
    }
}
