package de.walahi.novosmp.stats;

import de.walahi.novosmp.NovoSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Shared, rate-limited player profile cache for every leaderboard-style menu.
 *
 * <p>Incomplete offline profiles are never assigned to skulls because Paper may otherwise start
 * many Mojang profile lookups at once. Missing profiles are resolved asynchronously at a
 * configurable rate. Successfully resolved skins are additionally persisted so a server restart
 * does not make the same leaderboard wait for all Mojang lookups again.</p>
 */
public final class LeaderboardProfileCache {
    private static final String CONFIG_ROOT = "leaderboards.player-heads";
    private static final long DEFAULT_LOOKUP_INTERVAL_MILLIS = 250L;
    private static final long DEFAULT_RETRY_AFTER_FAILURE_MILLIS = TimeUnit.MINUTES.toMillis(5L);

    private final NovoSMPPlugin plugin;
    private final Map<UUID, PlayerProfile> resolved = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<PlayerProfile>> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Long> retryAfter = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAccess = new ConcurrentHashMap<>();
    private final long lookupIntervalMillis;
    private final long retryAfterFailureMillis;
    private final boolean persistentCacheEnabled;
    private final int maxMemoryProfiles;
    private final int maxPersistentProfiles;
    private final File cacheFile;
    private final YamlConfiguration diskCache;
    private long nextLookupAtMillis;
    private BukkitTask pendingSave;

    public LeaderboardProfileCache(NovoSMPPlugin plugin) {
        this.plugin = plugin;
        this.lookupIntervalMillis = clamp(
                plugin.configs().scoreboards().getLong(
                        CONFIG_ROOT + ".lookup-interval-millis", DEFAULT_LOOKUP_INTERVAL_MILLIS),
                50L, 5_000L);
        this.retryAfterFailureMillis = TimeUnit.SECONDS.toMillis(Math.max(10L,
                plugin.configs().scoreboards().getLong(
                        CONFIG_ROOT + ".retry-after-failure-seconds",
                        TimeUnit.MILLISECONDS.toSeconds(DEFAULT_RETRY_AFTER_FAILURE_MILLIS))));
        this.persistentCacheEnabled = plugin.configs().scoreboards().getBoolean(
                CONFIG_ROOT + ".persistent-cache", true);
        this.maxMemoryProfiles = clampInt(plugin.configs().scoreboards().getInt(
                CONFIG_ROOT + ".max-memory-profiles", 128), 16, 2_000);
        this.maxPersistentProfiles = clampInt(plugin.configs().scoreboards().getInt(
                CONFIG_ROOT + ".max-persistent-profiles", 256), maxMemoryProfiles, 10_000);
        this.cacheFile = new File(plugin.getDataFolder(), "leaderboard-head-cache.yml");
        this.diskCache = persistentCacheEnabled
                ? YamlConfiguration.loadConfiguration(cacheFile)
                : new YamlConfiguration();
        loadPersistentProfiles();
    }

    /** Returns an already safe/complete profile without starting any network request. */
    public PlayerProfile cached(UUID uuid) {
        if (uuid == null) return null;

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            PlayerProfile profile = online.getPlayerProfile();
            if (profile.isComplete()) {
                remember(uuid, profile, true);
                return profile;
            }
        }

        PlayerProfile cached = resolved.get(uuid);
        if (cached != null && cached.isComplete()) {
            lastAccess.put(uuid, System.currentTimeMillis());
            return cached;
        }

        // UUID lookup itself is local. If Paper already has a completed profile from a previous
        // login, use it immediately. Crucially, an incomplete UUID-only profile is not assigned.
        PlayerProfile localOfflineProfile = Bukkit.getOfflinePlayer(uuid).getPlayerProfile();
        if (localOfflineProfile.isComplete()) {
            remember(uuid, localOfflineProfile, true);
            return localOfflineProfile;
        }
        return null;
    }

    /**
     * Resolves a profile asynchronously if necessary. The callback always runs on the server
     * thread and is only invoked for a completed profile that is safe to put on a skull.
     */
    public void resolve(UUID uuid, String name, Consumer<PlayerProfile> callback) {
        if (uuid == null || callback == null || !plugin.isEnabled()) return;

        PlayerProfile immediate = cached(uuid);
        if (immediate != null) {
            callback.accept(immediate);
            return;
        }

        long now = System.currentTimeMillis();
        retryAfter.entrySet().removeIf(entry -> entry.getValue() <= now);
        Long retryAt = retryAfter.get(uuid);
        if (retryAt != null && retryAt > now) return;

        CompletableFuture<PlayerProfile> future = inFlight.computeIfAbsent(
                uuid, ignored -> scheduleLookup(uuid, name));
        future.thenAccept(profile -> {
            if (profile == null || !plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) callback.accept(profile);
            });
        });
    }

    /** Flushes the small persistent skin cache during a clean shutdown. */
    public void shutdown() {
        if (pendingSave != null) {
            pendingSave.cancel();
            pendingSave = null;
        }
        savePersistentCache();
        inFlight.clear();
        resolved.clear();
        retryAfter.clear();
        lastAccess.clear();
    }

    private CompletableFuture<PlayerProfile> scheduleLookup(UUID uuid, String name) {
        CompletableFuture<PlayerProfile> future = new CompletableFuture<>();
        long delayMillis;
        synchronized (this) {
            long now = System.currentTimeMillis();
            long startAt = Math.max(now, nextLookupAtMillis);
            delayMillis = Math.max(0L, startAt - now);
            nextLookupAtMillis = startAt + lookupIntervalMillis;
        }

        long delayTicks = Math.max(1L, (delayMillis + 49L) / 50L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> startLookup(uuid, name, future), delayTicks);
        return future;
    }

    private void startLookup(UUID uuid, String name, CompletableFuture<PlayerProfile> future) {
        if (!plugin.isEnabled()) {
            finish(uuid, future, null, null);
            return;
        }

        PlayerProfile local = cached(uuid);
        if (local != null) {
            finish(uuid, future, local, null);
            return;
        }

        try {
            String safeName = name;
            if (safeName == null || safeName.isBlank() || safeName.length() > 16) safeName = null;
            PlayerProfile partial = Bukkit.createPlayerProfile(uuid, safeName);
            partial.update().whenComplete((updated, error) -> finish(uuid, future, updated, error));
        } catch (Throwable error) {
            finish(uuid, future, null, error);
        }
    }

    private void finish(UUID uuid, CompletableFuture<PlayerProfile> future,
                        PlayerProfile profile, Throwable error) {
        PlayerProfile usable = error == null && profile != null && profile.isComplete() ? profile : null;
        if (usable != null) {
            remember(uuid, usable, true);
        } else {
            retryAfter.put(uuid, System.currentTimeMillis() + retryAfterFailureMillis);
        }
        inFlight.remove(uuid, future);
        future.complete(usable);
    }

    private void remember(UUID uuid, PlayerProfile profile, boolean persist) {
        resolved.put(uuid, profile);
        lastAccess.put(uuid, System.currentTimeMillis());
        retryAfter.remove(uuid);
        trimMemoryCache(uuid);
        if (!persist || !persistentCacheEnabled) return;

        Runnable writer = () -> persistProfile(uuid, profile);
        if (Bukkit.isPrimaryThread()) writer.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, writer);
    }

    private void persistProfile(UUID uuid, PlayerProfile profile) {
        try {
            String name = profile.getName();
            PlayerTextures textures = profile.getTextures();
            URL skin = textures.getSkin();
            if (name == null || name.isBlank() || skin == null) return;

            String root = "profiles." + uuid;
            diskCache.set(root + ".name", name);
            diskCache.set(root + ".skin", skin.toExternalForm());
            diskCache.set(root + ".model", textures.getSkinModel().name());
            diskCache.set(root + ".updated-at", System.currentTimeMillis());
            trimPersistentCache();
            scheduleSave();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Leaderboard-Skin konnte nicht gecacht werden: " + uuid, exception);
        }
    }

    private void loadPersistentProfiles() {
        if (!persistentCacheEnabled) return;
        ConfigurationSection profiles = diskCache.getConfigurationSection("profiles");
        if (profiles == null) return;

        List<String> newestProfiles = new ArrayList<>(profiles.getKeys(false));
        newestProfiles.sort(Comparator.comparingLong((String id) ->
                diskCache.getLong("profiles." + id + ".updated-at", 0L)).reversed());
        if (newestProfiles.size() > maxPersistentProfiles) {
            for (String stale : newestProfiles.subList(maxPersistentProfiles, newestProfiles.size())) {
                diskCache.set("profiles." + stale, null);
            }
            newestProfiles = new ArrayList<>(newestProfiles.subList(0, maxPersistentProfiles));
            scheduleSave();
        }

        int loaded = 0;
        for (String rawUuid : newestProfiles) {
            try {
                UUID uuid = UUID.fromString(rawUuid);
                String root = "profiles." + rawUuid;
                String name = diskCache.getString(root + ".name");
                String skin = diskCache.getString(root + ".skin");
                if (name == null || name.isBlank() || skin == null || skin.isBlank()) continue;

                PlayerProfile profile = Bukkit.createPlayerProfile(uuid, name);
                PlayerTextures textures = profile.getTextures();
                PlayerTextures.SkinModel model = parseSkinModel(diskCache.getString(root + ".model"));
                textures.setSkin(URI.create(skin).toURL(), model);
                profile.setTextures(textures);
                if (!profile.isComplete()) continue;
                remember(uuid, profile, false);
                loaded++;
            } catch (Exception ignored) {
                // A malformed/stale single cache entry must never prevent the server from starting.
            }
        }
        if (loaded > 0) {
            plugin.getLogger().info("Leaderboard-Head-Cache geladen: " + loaded + " Spielerprofile.");
        }
    }

    private PlayerTextures.SkinModel parseSkinModel(String configured) {
        if (configured == null || configured.isBlank()) return PlayerTextures.SkinModel.CLASSIC;
        try {
            return PlayerTextures.SkinModel.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PlayerTextures.SkinModel.CLASSIC;
        }
    }

    private void scheduleSave() {
        if (!persistentCacheEnabled || !plugin.isEnabled()) return;
        if (pendingSave != null && !pendingSave.isCancelled()) return;
        pendingSave = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingSave = null;
            savePersistentCache();
        }, 40L);
    }

    private void savePersistentCache() {
        if (!persistentCacheEnabled) return;
        try {
            File parent = cacheFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            diskCache.save(cacheFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "leaderboard-head-cache.yml konnte nicht gespeichert werden", exception);
        }
    }

    private void trimMemoryCache(UUID protectedId) {
        while (resolved.size() > maxMemoryProfiles) {
            UUID oldest = null;
            long oldestAccess = Long.MAX_VALUE;
            for (Map.Entry<UUID, PlayerProfile> entry : resolved.entrySet()) {
                UUID candidate = entry.getKey();
                if (candidate.equals(protectedId) || inFlight.containsKey(candidate)) continue;
                long accessed = lastAccess.getOrDefault(candidate, 0L);
                if (accessed < oldestAccess) {
                    oldestAccess = accessed;
                    oldest = candidate;
                }
            }
            if (oldest == null) return;
            resolved.remove(oldest);
            lastAccess.remove(oldest);
        }
    }

    private void trimPersistentCache() {
        ConfigurationSection profiles = diskCache.getConfigurationSection("profiles");
        if (profiles == null || profiles.getKeys(false).size() <= maxPersistentProfiles) return;
        String oldest = profiles.getKeys(false).stream().min(Comparator.comparingLong(id ->
                diskCache.getLong("profiles." + id + ".updated-at", 0L))).orElse(null);
        if (oldest != null) diskCache.set("profiles." + oldest, null);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
