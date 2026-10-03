package de.walahi.smpcore.services;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;

/**
 * Periodically persists volatile player state so an abrupt process shutdown
 * loses at most a small amount of position progress.
 */
public final class SaveManager {
    private static final long AUTOSAVE_INTERVAL_TICKS = 20L * 60L;

    private final JavaPlugin plugin;
    private final PlayerDataService playerDataService;
    private BukkitTask autosaveTask;

    public SaveManager(JavaPlugin plugin, PlayerDataService playerDataService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerDataService = Objects.requireNonNull(playerDataService, "playerDataService");
    }

    public void start() {
        stop();
        autosaveTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::saveAllOnlinePlayers,
                AUTOSAVE_INTERVAL_TICKS,
                AUTOSAVE_INTERVAL_TICKS
        );
    }

    public void savePlayer(Player player) {
        if (player == null || !player.isOnline()) return;
        playerDataService.saveLastSmpLocation(player, player.getLocation());
    }

    public void saveAllOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            savePlayer(player);
        }
    }

    public void stop() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
    }
}
