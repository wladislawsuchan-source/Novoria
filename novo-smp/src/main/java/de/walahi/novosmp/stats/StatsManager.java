package de.walahi.novosmp.stats;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.StatType;
import de.walahi.smpcore.database.DatabaseManager;
import de.walahi.smpcore.stats.StatsAccess;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Public statistics facade. Tracking, persistence, ranking and menus are implemented
 * by focused collaborators instead of one YAML-backed god class.
 */
public final class StatsManager implements StatsAccess {
    private final NovoSMPPlugin plugin;
    private final StatsRepository repository;
    private final StatsCache cache;
    private final StatsQueryService queries;
    private final StatsFormatter formatter;
    private final StatsMenu statsMenu;
    private final LeaderboardMenu leaderboardMenu;
    private final StatsTracker tracker;
    private final BukkitTask autosaveTask;

    public StatsManager(NovoSMPPlugin plugin) {
        this.plugin = plugin;
        DatabaseManager database = plugin.storageManager() == null ? null : plugin.databaseManager();
        if (database == null) throw new IllegalStateException("Stats benötigen eine aktive Datenbank.");

        this.repository = new StatsRepository(database, plugin.getLogger());
        long lastSeenTouchInterval = Math.max(0L,
                plugin.configs().scoreboards().getLong("stats.last-seen-touch-interval-millis", 30_000L));
        try {
            this.cache = new StatsCache(repository.loadAll(), lastSeenTouchInterval);
        } catch (SQLException exception) {
            throw new IllegalStateException("Statistiken konnten nicht aus der Datenbank geladen werden", exception);
        }

        this.queries = new StatsQueryService(plugin, cache);
        this.formatter = new StatsFormatter(plugin);
        this.statsMenu = new StatsMenu(plugin, queries, formatter);
        this.leaderboardMenu = new LeaderboardMenu(plugin, cache, queries, formatter);
        this.tracker = new StatsTracker(plugin, cache);
        Bukkit.getPluginManager().registerEvents(tracker, plugin);

        long autosaveTicks = Math.max(20L,
                plugin.configs().scoreboards().getLong("stats.autosave-ticks", 1200L));
        this.autosaveTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::saveIfDirty, autosaveTicks, autosaveTicks);
    }

    @Override
    public void shutdown() {
        tracker.shutdown();
        autosaveTask.cancel();
        saveIfDirty();
    }

    @Override
    public long getStat(UUID uuid, String path) {
        if (uuid == null || path == null) return 0L;
        return cache.get(uuid, path);
    }

    @Override
    public long getStat(UUID uuid, StatType type) {
        return uuid == null || type == null ? 0L : cache.get(uuid, type.path());
    }

    @Override
    public void addStat(UUID uuid, StatType type, long amount) {
        if (uuid == null || type == null || amount <= 0L) return;
        Player online = Bukkit.getPlayer(uuid);
        cache.ensure(uuid, online == null ? uuid.toString() : online.getName());
        cache.add(uuid, type.path(), amount);
    }

    @Override
    public void setStat(UUID uuid, StatType type, long value) {
        if (uuid == null || type == null) return;
        Player online = Bukkit.getPlayer(uuid);
        cache.ensure(uuid, online == null ? uuid.toString() : online.getName());
        cache.set(uuid, type.path(), Math.max(0L, value));
    }

    @Override
    public int registeredPlayers() {
        return cache.size();
    }

    @Override
    public String getFormattedPlaytime(UUID uuid) {
        return formatter.playtime(getStat(uuid, StatType.PLAYTIME));
    }

    @Override
    public void openStats(Player player) {
        if (player == null) return;
        cache.ensure(player.getUniqueId(), player.getName());
        statsMenu.open(player, cache.snapshot(player.getUniqueId(), player.getName()));
    }

    @Override
    public boolean openStats(Player viewer, String playerName) {
        if (viewer == null || playerName == null || playerName.isBlank()) return false;
        StatsSnapshot target = queries.findByName(playerName);
        if (target == null) return false;
        statsMenu.open(viewer, target);
        return true;
    }

    @Override
    public List<String> registeredPlayerNames(String prefix) {
        return queries.registeredPlayerNames(prefix);
    }

    @Override
    public void openLeaderboards(Player viewer) {
        if (viewer != null) leaderboardMenu.open(viewer);
    }

    public int getRank(UUID uuid, StatType type) {
        return queries.rank(uuid, type);
    }

    public int getCoinRank(UUID uuid) {
        return queries.coinRank(uuid);
    }

    public int getLumiRank(UUID uuid) {
        return queries.lumiRank(uuid);
    }

    private void saveIfDirty() {
        if (!cache.isDirty()) return;
        List<StatsSnapshot> dirty = cache.dirtySnapshots();
        if (dirty.isEmpty()) return;
        try {
            repository.saveAll(dirty);
            cache.markPersisted(dirty);
        } catch (SQLException exception) {
            plugin.getLogger().severe("Spielerstatistiken konnten nicht gespeichert werden: "
                    + exception.getMessage());
        }
    }
}
