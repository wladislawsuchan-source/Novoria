package de.walahi.novosmp.friends;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.friends.FriendRepository;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Facade and lifecycle owner for the SMP friend module.
 * Rendering, listeners, persistence reads, notifications and glow are implemented in focused classes.
 */
public final class FriendManager {
    private final NovoSMPPlugin plugin;
    private final FriendRepository repository;
    private final FriendConfiguration config;
    private final FriendHomeRepository homes;
    private final FriendStateCache cache;
    private final FriendGlowController glow;
    private final FriendRequestNotifier requests;
    private final FriendPresenceListener presence;
    private final FriendMenuController menus;
    private final FriendProtectionListener protection;
    private boolean started;

    public FriendManager(NovoSMPPlugin plugin, FriendRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.config = new FriendConfiguration(plugin);
        this.homes = new FriendHomeRepository(plugin, plugin.sharedDb(), repository);
        this.cache = new FriendStateCache(repository,
                new FriendStateSnapshotRepository(plugin, plugin.sharedDb()),
                config.longValue("settings.cache-ttl-millis", 30_000L));
        this.glow = new FriendGlowController(plugin, config, cache, this::isHiddenFromPlayers, this::friendFeaturesSuppressed);
        this.requests = new FriendRequestNotifier(plugin, repository, config);
        this.presence = new FriendPresenceListener(plugin, config, cache, requests, glow, this::isHiddenFromPlayers);
        this.menus = new FriendMenuController(plugin, repository, homes, cache, glow, config, this::isHiddenFromPlayers);
        this.protection = new FriendProtectionListener(cache, config, this::isVanished, this::friendFeaturesSuppressed);
    }

    public void start() {
        if (started) return;
        started = true;
        Bukkit.getPluginManager().registerEvents(menus, plugin);
        Bukkit.getPluginManager().registerEvents(glow, plugin);
        Bukkit.getPluginManager().registerEvents(presence, plugin);
        Bukkit.getPluginManager().registerEvents(protection, plugin);
        requests.start();
        glow.start();
    }

    public void stop() {
        if (!started) return;
        started = false;
        requests.stop();
        glow.stop();
        cache.clear();
    }

    public FriendRepository repository() { return repository; }

    public boolean isVanished(Player player) {
        return player != null && plugin.getStaffCommands() != null && plugin.getStaffCommands().isVanished(player);
    }

    public boolean isHiddenFromPlayers(Player player) {
        return player != null && plugin.getStaffCommands() != null
                && plugin.getStaffCommands().isHiddenFromPlayers(player);
    }


    public void openList(Player viewer) { menus.openList(viewer); }
    public void openList(Player viewer, int page) { menus.openList(viewer, page); }
    public void openRequests(Player viewer) { menus.openRequests(viewer); }
    public void openProfile(Player viewer, UUID friend, String name) { menus.openProfile(viewer, friend, name); }
    public void openDefaults(Player viewer) { menus.openDefaults(viewer); }
    public void openHomes(Player viewer, UUID friend) { menus.openHomes(viewer, friend); }
    public void openAdminPlayers(Player viewer, int page) { menus.openAdminPlayers(viewer, page); }
    public void openAdminHomes(Player viewer, UUID owner, String name) { menus.openAdminHomes(viewer, owner, name); }

    public UUID resolvePlayer(String name) { return homes.resolvePlayer(name); }
    public List<String> allowedHomeNames(UUID owner, UUID friend) { return homes.allowedHomeNames(owner, friend); }
    public List<String> homeNames(UUID owner) { return homes.homeNames(owner); }
    public Location loadHome(UUID owner, String home) { return homes.load(owner, home); }

    public boolean areFriends(UUID first, UUID second) {
        return cache.areFriends(first, second);
    }

    public void setClanGlowProvider(BiPredicate<Player, Player> provider) { glow.setClanGlow(provider); }
    public void refreshGlow() { glow.refreshAll(); }

    public FriendRepository.RequestResult sendRequest(UUID sender, String senderName, UUID target, String targetName) {
        FriendRepository.RequestResult result = repository.sendRequest(sender, senderName, target, targetName);
        if (result == FriendRepository.RequestResult.AUTO_ACCEPTED) {
            cache.invalidatePair(sender, target);
            glow.refreshAll();
        }
        return result;
    }

    public void accept(UUID sender, String senderName, UUID target, String targetName) {
        repository.accept(sender, senderName, target, targetName);
        cache.invalidatePair(sender, target);
        glow.refreshAll();
    }

    public boolean deny(UUID sender, UUID target) {
        return repository.deny(sender, target);
    }

    public void remove(UUID first, UUID second) {
        repository.remove(first, second);
        cache.invalidatePair(first, second);
        glow.refreshAll();
    }

    public boolean shouldMarkChat(Player viewer, Player sender) {
        if (viewer == null || sender == null || viewer.getUniqueId().equals(sender.getUniqueId())) return false;
        if (isVanished(sender) || friendFeaturesSuppressed(viewer, sender)) return false;
        try {
            return cache.areFriends(viewer.getUniqueId(), sender.getUniqueId())
                    && cache.settings(viewer.getUniqueId(), sender.getUniqueId()).chatMark();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public void handleVanishState(Player changed, boolean vanished) {
        presence.handleVanishState(changed, vanished);
    }

    public void notifyRequestsNow(Player player) {
        requests.notifyNow(player);
    }

    public String friendName(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null || name.isBlank() ? "Spieler" : name;
    }

    public void send(Player player, String path, String fallback) {
        player.sendMessage(config.component(path, fallback));
    }

    public void send(Player player, String path, String fallback, Map<String, String> placeholders) {
        player.sendMessage(config.component(path, fallback, placeholders));
    }

    public Component message(String path, String fallback, Map<String, String> placeholders) {
        return config.component(path, fallback, placeholders);
    }

    private boolean friendFeaturesSuppressed(Player first, Player second) {
        return first != null && second != null
                && plugin.areDuelOpponents(first.getUniqueId(), second.getUniqueId());
    }
}
