package de.walahi.smpcore;

import de.walahi.smpcore.hub.HubCompassController;
import de.walahi.smpcore.location.LocationStore;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Owns player join routing, public join/quit announcements and last SMP
 * location persistence. Runtime cleanup such as pending teleports remains in
 * the core plugin and calls {@link #handleQuit(PlayerQuitEvent)} explicitly.
 */
public final class PlayerSessionLifecycle implements Listener {
    private final SMPCorePlugin plugin;
    private final LocationStore locations;
    private final HubCompassController hubCompass;
    private final Set<UUID> automaticHubTeleports = new HashSet<>();

    public PlayerSessionLifecycle(SMPCorePlugin plugin, LocationStore locations,
                                  HubCompassController hubCompass) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.locations = Objects.requireNonNull(locations, "locations");
        this.hubCompass = Objects.requireNonNull(hubCompass, "hubCompass");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        if (!plugin.isSmpServer() || plugin.services() == null) return;

        var profile = event.getConnection().getProfile();
        UUID playerUuid = profile.getUniqueId();
        String playerName = profile.getName();
        Location target = plugin.services().playerData().loadLastSmpLocation(playerUuid, playerName);

        if (target == null && plugin.configs().main().getBoolean("settings.use-smp-spawn-as-fallback", true)) {
            target = locations.get("locations.smp-spawn");
            if (target == null) {
                target = locations.worldSpawn(plugin.configs().server().getString("spawn-world", "smp_spawn"));
            }
        }

        if (target != null) {
            event.setSpawnLocation(target);
            plugin.getLogger().info("Join-Ziel für " + playerName + ": " + formatLocation(target));
        } else {
            plugin.getLogger().warning("Kein gültiges SMP-Join-Ziel für " + playerName
                    + " gefunden. Prüfe locations.smp-spawn und die Welt smp_spawn.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.services() != null) {
            plugin.services().playerData().markOnline(player.getUniqueId());
        }
        applyJoinMessage(event, player);
        plugin.scheduleServerJoinReminder(player);

        if (!plugin.isHubServer()
                || !plugin.configs().main().getBoolean("settings.send-to-hub-on-join", true)) {
            finishTabJoin(player);
            return;
        }

        automaticHubTeleports.add(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> teleportToHub(player), 2L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        if (!plugin.configs().messages().getBoolean("announcements.advancements.public-chat", false)) {
            event.message(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (plugin.services() == null
                || !plugin.configs().main().getBoolean("settings.save-last-smp-location", true)
                || event.getTo() == null
                || automaticHubTeleports.contains(event.getPlayer().getUniqueId())) {
            return;
        }

        boolean fromSmp = plugin.isSmpGameplayWorld(event.getFrom().getWorld());
        boolean toSmp = plugin.isSmpGameplayWorld(event.getTo().getWorld());
        if (fromSmp && !toSmp) {
            plugin.services().playerData().saveLastSmpLocation(event.getPlayer(), event.getFrom());
        }
    }

    public void handleQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        applyQuitMessage(event, player);
        automaticHubTeleports.remove(player.getUniqueId());

        if (plugin.services() == null) return;
        plugin.services().playerData().markOffline(player.getUniqueId());
        if (plugin.configs().main().getBoolean("settings.save-last-smp-location", true)
                && plugin.isSmpGameplayWorld(player.getWorld())) {
            plugin.services().playerData().saveLastSmpLocation(player, player.getLocation());
        }
    }

    public void clear() {
        automaticHubTeleports.clear();
    }

    private void teleportToHub(Player player) {
        if (!player.isOnline()) {
            automaticHubTeleports.remove(player.getUniqueId());
            finishTabJoin(player);
            return;
        }

        Location hub = locations.get("locations.hub");
        if (hub == null) {
            hub = locations.worldSpawn(plugin.configs().server().getString("world", "world"));
        }
        if (hub == null) {
            automaticHubTeleports.remove(player.getUniqueId());
            finishTabJoin(player);
            plugin.sendConfigured(player, "messages.missing-location");
            return;
        }

        player.teleportAsync(hub).thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
            automaticHubTeleports.remove(player.getUniqueId());
            if (success && player.isOnline()) hubCompass.give(player);
            finishTabJoin(player);
        }));
    }

    private void finishTabJoin(Player player) {
        if (plugin.getTabVisibilityManager() != null) {
            plugin.getTabVisibilityManager().finishJoin(player);
        }
    }

    private void applyJoinMessage(PlayerJoinEvent event, Player player) {
        if (plugin.getStaffCommands() != null && plugin.getStaffCommands().isHiddenFromPlayers(player)) {
            event.joinMessage(null);
            return;
        }
        if (!plugin.configs().messages().getBoolean("announcements.join.enabled", false)) {
            event.joinMessage(null);
            return;
        }
        String text = plugin.configs().messages().getString(
                "announcements.join.text",
                "<gray><player> hat den Server betreten.</gray>"
        );
        event.joinMessage(plugin.messages().component(text, "<player>", player.getName()));
    }

    private void applyQuitMessage(PlayerQuitEvent event, Player player) {
        if (plugin.getStaffCommands() != null && plugin.getStaffCommands().isHiddenFromPlayers(player)) {
            event.quitMessage(null);
            return;
        }
        if (!plugin.configs().messages().getBoolean("announcements.quit.enabled", false)) {
            event.quitMessage(null);
            return;
        }
        String text = plugin.configs().messages().getString(
                "announcements.quit.text",
                "<gray><player> hat den Server verlassen.</gray>"
        );
        event.quitMessage(plugin.messages().component(text, "<player>", player.getName()));
    }

    private String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) return "nicht gesetzt";
        return location.getWorld().getName() + " "
                + String.format("%.1f %.1f %.1f", location.getX(), location.getY(), location.getZ());
    }
}
