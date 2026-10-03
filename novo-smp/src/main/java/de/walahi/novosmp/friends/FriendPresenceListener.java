package de.walahi.novosmp.friends;

import de.walahi.novosmp.NovoSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.function.Predicate;

/** Join/leave visibility and notifications for friends. */
public final class FriendPresenceListener implements Listener {
    private final NovoSMPPlugin plugin;
    private final FriendConfiguration config;
    private final FriendStateCache cache;
    private final FriendRequestNotifier notifier;
    private final FriendGlowController glow;
    private final Predicate<Player> vanished;

    public FriendPresenceListener(NovoSMPPlugin plugin, FriendConfiguration config, FriendStateCache cache,
                                  FriendRequestNotifier notifier, FriendGlowController glow,
                                  Predicate<Player> vanished) {
        this.plugin = plugin;
        this.config = config;
        this.cache = cache;
        this.notifier = notifier;
        this.glow = glow;
        this.vanished = vanished;
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        cache.invalidatePlayer(joined.getUniqueId());
        notifier.notifyNow(joined);
        if (!vanished.test(joined)) notifyState(joined, true);
        glow.refreshLater(10L);
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        Player left = event.getPlayer();
        if (!vanished.test(left)) notifyState(left, false);
        cache.invalidatePlayer(left.getUniqueId());
    }

    public void handleVanishState(Player changed, boolean vanishedNow) {
        if (changed == null) return;
        notifyState(changed, !vanishedNow);
        glow.refreshLater(0L);
    }

    private void notifyState(Player changed, boolean online) {
        java.util.List<Player> onlinePlayers = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
        if (!cache.preloadOnline(onlinePlayers.stream().map(Player::getUniqueId).toList())) return;
        for (Player viewer : onlinePlayers) {
            if (viewer.getUniqueId().equals(changed.getUniqueId())) continue;
            try {
                if (!cache.areFriends(viewer.getUniqueId(), changed.getUniqueId())
                        || !cache.settings(viewer.getUniqueId(), changed.getUniqueId()).joinLeave()) continue;
                String path = online ? "messages.friend-online" : "messages.friend-offline";
                String fallback = online
                        ? "<dark_gray>[<light_purple>Freunde</light_purple>]</dark_gray> <green><player> ist nun online.</green>"
                        : "<dark_gray>[<light_purple>Freunde</light_purple>]</dark_gray> <gray><player> ist nun offline.</gray>";
                viewer.sendMessage(config.component(path, fallback, Map.of("player", changed.getName())));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Freundes-Status konnte nicht geladen werden: " + exception.getMessage());
            }
        }
    }
}
