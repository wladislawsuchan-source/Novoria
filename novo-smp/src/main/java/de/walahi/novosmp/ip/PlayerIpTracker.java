package de.walahi.novosmp.ip;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;

public final class PlayerIpTracker implements Listener {
    private final JavaPlugin plugin;
    private final PlayerIpRepository repository;

    public PlayerIpTracker(JavaPlugin plugin, PlayerIpRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        record(event.getPlayer());
    }

    public void record(Player player) {
        String ipAddress = address(player);
        if (ipAddress == null) return;
        var playerId = player.getUniqueId();
        String playerName = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repository.record(playerId, playerName, ipAddress);
            } catch (Exception exception) {
                plugin.getLogger().warning("IP-Verlauf für " + playerName + " konnte nicht gespeichert werden: " + exception.getMessage());
            }
        });
    }

    public static String address(Player player) {
        InetSocketAddress socketAddress = player.getAddress();
        if (socketAddress == null || socketAddress.getAddress() == null) return null;
        return socketAddress.getAddress().getHostAddress();
    }
}
