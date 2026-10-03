package de.walahi.novosmp.friends;

import de.walahi.smpcore.database.DatabaseManager;
import de.walahi.smpcore.friends.FriendRepository;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Read model for friend/admin home menus and teleports. */
public final class FriendHomeRepository {
    public record HomeEntry(String name, String displayName) { }

    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final FriendRepository friends;

    public FriendHomeRepository(JavaPlugin plugin, DatabaseManager database, FriendRepository friends) {
        this.plugin = plugin;
        this.database = database;
        this.friends = friends;
    }

    public List<HomeEntry> homes(UUID owner) {
        List<HomeEntry> homes = new ArrayList<>();
        String sql = "SELECT home_name,display_name FROM " + database.table("homes")
                + " WHERE player_uuid=? ORDER BY home_name";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) homes.add(new HomeEntry(result.getString(1), result.getString(2)));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Freundes-Homes konnten nicht geladen werden: " + exception.getMessage());
        }
        return homes;
    }

    public List<String> allowedHomeNames(UUID owner, UUID friend) {
        String sql = "SELECT h.display_name FROM " + database.table("homes") + " h JOIN "
                + database.table("friend_home_permissions") + " p ON p.owner_uuid=h.player_uuid "
                + "AND p.home_name=h.home_name WHERE h.player_uuid=? AND p.friend_uuid=? "
                + "AND p.allowed=TRUE ORDER BY h.home_name";
        List<String> names = new ArrayList<>();
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            statement.setString(2, friend.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) names.add(result.getString(1));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Freigegebene Freundes-Homes konnten nicht geladen werden: " + exception.getMessage());
        }
        return names;
    }

    public List<String> homeNames(UUID owner) {
        return homes(owner).stream().map(HomeEntry::displayName).toList();
    }

    public Location load(UUID owner, String rawHome) {
        if (rawHome == null || rawHome.isBlank()) return null;
        String sql = "SELECT world,x,y,z,yaw,pitch FROM " + database.table("homes")
                + " WHERE player_uuid=? AND home_name=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            statement.setString(2, rawHome.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                World world = Bukkit.getWorld(result.getString(1));
                return world == null ? null : new Location(world, result.getDouble(2), result.getDouble(3),
                        result.getDouble(4), result.getFloat(5), result.getFloat(6));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Freundes-Home konnte nicht geladen werden: " + exception.getMessage());
            return null;
        }
    }

    public UUID resolvePlayer(String name) {
        if (name == null || name.isBlank()) return null;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) return cached.getUniqueId();

        String sql = "SELECT player_uuid FROM " + database.table("player_stats")
                + " WHERE LOWER(player_name)=LOWER(?) LIMIT 1";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) return UUID.fromString(result.getString(1));
            }
        } catch (SQLException | IllegalArgumentException ignored) {
            // Fall through to persistent friend/home records.
        }
        return friends.findKnownPlayerUuid(name);
    }
}
