package de.walahi.novosmp.homes;

import de.walahi.smpcore.api.EventPublisher;
import de.walahi.smpcore.api.event.HomeDeleteEvent;
import de.walahi.smpcore.api.event.HomeSetEvent;
import de.walahi.smpcore.database.DatabaseManager;
import de.walahi.smpcore.homes.HomeAccess;
import de.walahi.smpcore.homes.HomeLimitResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Database-backed home persistence. */
public final class HomeService implements HomeAccess {

    private final JavaPlugin plugin;
    private final EventPublisher events;
    private final FileConfiguration config;
    private final DatabaseManager database;

    public HomeService(JavaPlugin plugin, EventPublisher events, FileConfiguration config,
                       DatabaseManager database) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.events = Objects.requireNonNull(events, "events");
        this.config = Objects.requireNonNull(config, "config");
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void start() { }

    @Override
    public SetHomeResult setHome(Player player, String rawName, Location location) {
        String normalized = normalizeName(rawName);
        int limit = getLimit(player);
        if (!isValidName(normalized)) return new SetHomeResult(SetHomeStatus.INVALID_NAME, normalized, rawName, limit);
        if (find(player, normalized) != null) {
            return new SetHomeResult(SetHomeStatus.ALREADY_EXISTS, normalized, getDisplayName(player, normalized), limit);
        }
        if (limit >= 0 && getNames(player).size() >= limit) {
            return new SetHomeResult(SetHomeStatus.LIMIT_REACHED, normalized, rawName, limit);
        }

        long now = System.currentTimeMillis();
        String sql = "INSERT INTO " + database.table("homes") +
                " (player_uuid, player_name, home_name, display_name, world, x, y, z, yaw, pitch, created_at, updated_at)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, player.getName());
            statement.setString(3, normalized);
            statement.setString(4, rawName);
            statement.setString(5, location.getWorld().getName());
            statement.setDouble(6, location.getX());
            statement.setDouble(7, location.getY());
            statement.setDouble(8, location.getZ());
            statement.setFloat(9, location.getYaw());
            statement.setFloat(10, location.getPitch());
            statement.setLong(11, now);
            statement.setLong(12, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseFailure("Home konnte nicht gespeichert werden", exception);
        }
        events.publish(new HomeSetEvent(player, normalized, rawName, location, HomeSetEvent.Action.CREATED));
        return new SetHomeResult(SetHomeStatus.CREATED, normalized, rawName, limit);
    }

    @Override
    public HomeLookup find(Player player, String rawName) {
        String normalized = normalizeName(rawName);
        String sql = "SELECT display_name, world, x, y, z, yaw, pitch FROM " + database.table("homes") +
                " WHERE player_uuid = ? AND home_name = ?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, normalized);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                World world = Bukkit.getWorld(result.getString("world"));
                if (world == null) return null;
                Location location = new Location(world, result.getDouble("x"), result.getDouble("y"), result.getDouble("z"),
                        result.getFloat("yaw"), result.getFloat("pitch"));
                return new HomeLookup(normalized, result.getString("display_name"), location);
            }
        } catch (SQLException exception) {
            throw databaseFailure("Home konnte nicht geladen werden", exception);
        }
    }

    @Override
    public DeleteHomeResult delete(Player player, String rawName) {
        String normalized = normalizeName(rawName);
        String displayName = getDisplayName(player, normalized);
        String sql = "DELETE FROM " + database.table("homes") + " WHERE player_uuid = ? AND home_name = ?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, normalized);
            if (statement.executeUpdate() == 0) return new DeleteHomeResult(false, rawName);
        } catch (SQLException exception) {
            throw databaseFailure("Home konnte nicht gelöscht werden", exception);
        }
        String permissionSql = "DELETE FROM " + database.table("friend_home_permissions") +
                " WHERE owner_uuid = ? AND home_name = ?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(permissionSql)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, normalized);
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Veraltete Freundes-Homefreigaben konnten nicht entfernt werden: " + exception.getMessage());
        }
        events.publish(new HomeDeleteEvent(player, normalized, displayName));
        return new DeleteHomeResult(true, displayName);
    }

    @Override
    public List<String> getNames(Player player) {
        String sql = "SELECT home_name FROM " + database.table("homes") + " WHERE player_uuid = ? ORDER BY home_name";
        List<String> names = new ArrayList<>();
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.getUniqueId().toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) names.add(result.getString(1));
            }
        } catch (SQLException exception) {
            throw databaseFailure("Homes konnten nicht geladen werden", exception);
        }
        return names;
    }

    @Override
    public List<String> getDisplayNames(Player player) {
        String sql = "SELECT display_name FROM " + database.table("homes") + " WHERE player_uuid = ? ORDER BY home_name";
        List<String> names = new ArrayList<>();
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.getUniqueId().toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) names.add(result.getString(1));
            }
        } catch (SQLException exception) {
            throw databaseFailure("Homes konnten nicht geladen werden", exception);
        }
        return names;
    }

    @Override
    public int getLimit(Player player) {
        return HomeLimitResolver.resolve(player, config);
    }

    public String normalizeName(String name) { return name.toLowerCase(Locale.ROOT); }

    public boolean isValidName(String normalizedName) {
        int maxLength = Math.max(1, config.getInt("homes.max-name-length", 16));
        return normalizedName.length() <= maxLength && normalizedName.matches("[a-z0-9_-]+");
    }

    @Override
    public String getDisplayName(Player player, String normalizedName) {
        String sql = "SELECT display_name FROM " + database.table("homes") + " WHERE player_uuid = ? AND home_name = ?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, normalizeName(normalizedName));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : normalizedName;
            }
        } catch (SQLException exception) {
            throw databaseFailure("Home-Anzeigename konnte nicht geladen werden", exception);
        }
    }

    private IllegalStateException databaseFailure(String message, SQLException exception) {
        plugin.getLogger().severe(message + ": " + exception.getMessage());
        return new IllegalStateException(message, exception);
    }
}
