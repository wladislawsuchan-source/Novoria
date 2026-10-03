package de.walahi.smpcore.services;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.database.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Owns runtime player-session state and persistent last-SMP-location data.
 * Commands and listeners should access player persistence through this service.
 */
public final class PlayerDataService implements Service {
    private final Map<UUID, Instant> sessionStarts = new ConcurrentHashMap<>();
    private final Predicate<World> smpWorldPredicate;
    private final SMPCorePlugin plugin;
    private final DatabaseManager database;
    private final boolean sqlPersistence;

    public PlayerDataService(SMPCorePlugin plugin, DatabaseManager database, Predicate<World> smpWorldPredicate) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
        this.sqlPersistence = plugin.isSmpServer();
        this.smpWorldPredicate = Objects.requireNonNull(smpWorldPredicate, "smpWorldPredicate");
    }

    public void markOnline(UUID playerUuid) {
        sessionStarts.put(playerUuid, Instant.now());
    }

    public void markOffline(UUID playerUuid) {
        sessionStarts.remove(playerUuid);
    }

    public boolean isTrackedOnline(UUID playerUuid) {
        return sessionStarts.containsKey(playerUuid);
    }

    public Instant sessionStartedAt(UUID playerUuid) {
        return sessionStarts.get(playerUuid);
    }

    public void saveLastSmpLocation(Player player, Location location) {
        if (player == null || location == null || location.getWorld() == null) return;
        if (!smpWorldPredicate.test(location.getWorld())) return;

        if (sqlPersistence) {
            saveSqlLocation(player, location);
        }
    }

    public Location loadLastSmpLocation(Player player) {
        if (player == null || !sqlPersistence) return null;

        Location sql = loadSqlLocation(player.getUniqueId().toString());
        if (isValidSmpLocation(sql)) return sql;

        Location byName = loadSqlLocationByName(player.getName());
        if (isValidSmpLocation(byName)) {
            saveSqlLocation(player, byName);
            return byName;
        }
        return null;
    }


    public Location loadLastSmpLocation(UUID playerUuid, String playerName) {
        if (playerUuid == null || !sqlPersistence) return null;

        Location sql = loadSqlLocation(playerUuid.toString());
        if (isValidSmpLocation(sql)) return sql;

        if (playerName != null && !playerName.isBlank()) {
            Location byName = loadSqlLocationByName(playerName);
            if (isValidSmpLocation(byName)) return byName;
        }
        return null;
    }

    private void saveSqlLocation(Player player, Location location) {
        String table = database.table("player_locations");
        String sql = plugin.storageManager().dialect() == de.walahi.smpcore.storage.StorageDialect.SQLITE
                ? "INSERT INTO " + table + " (player_uuid,player_name,world,x,y,z,yaw,pitch,updated_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET player_name=excluded.player_name,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,updated_at=excluded.updated_at"
                : "INSERT INTO " + table + " (player_uuid,player_name,world,x,y,z,yaw,pitch,updated_at) VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE player_name=VALUES(player_name),world=VALUES(world),x=VALUES(x),y=VALUES(y),z=VALUES(z),yaw=VALUES(yaw),pitch=VALUES(pitch),updated_at=VALUES(updated_at)";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.getUniqueId().toString()); statement.setString(2, player.getName());
            statement.setString(3, location.getWorld().getName()); statement.setDouble(4, location.getX());
            statement.setDouble(5, location.getY()); statement.setDouble(6, location.getZ());
            statement.setFloat(7, location.getYaw()); statement.setFloat(8, location.getPitch());
            statement.setLong(9, System.currentTimeMillis()); statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().severe("Letzte SMP-Position konnte nicht gespeichert werden: " + exception.getMessage());
        }
    }

    private Location loadSqlLocation(String uuid) {
        return loadSql("SELECT world,x,y,z,yaw,pitch FROM " + database.table("player_locations") + " WHERE player_uuid = ?", uuid);
    }

    private Location loadSqlLocationByName(String name) {
        return loadSql("SELECT world,x,y,z,yaw,pitch FROM " + database.table("player_locations") + " WHERE LOWER(player_name) = LOWER(?) ORDER BY updated_at DESC", name);
    }

    private Location loadSql(String sql, String value) {
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                World world = Bukkit.getWorld(result.getString("world"));
                if (world == null) return null;
                return new Location(world, result.getDouble("x"), result.getDouble("y"), result.getDouble("z"), result.getFloat("yaw"), result.getFloat("pitch"));
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Letzte SMP-Position konnte nicht geladen werden: " + exception.getMessage());
            return null;
        }
    }

    private boolean isValidSmpLocation(Location location) {
        return location != null && location.getWorld() != null && smpWorldPredicate.test(location.getWorld());
    }

    @Override
    public void stop() {
        sessionStarts.clear();
    }
}
