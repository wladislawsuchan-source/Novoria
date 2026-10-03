package de.walahi.smpcore.friends;

import de.walahi.smpcore.database.DatabaseManager;
import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Persistent friend graph, requests, settings and per-home permissions. */
public final class FriendRepository {
    public enum RequestResult {
        SENT,
        ALREADY_FRIENDS,
        ALREADY_SENT,
        AUTO_ACCEPTED,
        FAILED
    }

    private final DatabaseManager database;

    public FriendRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Resolves offline players from the persistent network tables. */
    public UUID findKnownPlayerUuid(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        String normalized = playerName.trim();

        for (String table : List.of("player_stats", "player_locations", "homes", "friends")) {
            UUID result = findUuidByName(table, "player_uuid", "player_name", normalized);
            if (result != null) return result;
        }
        return findUuidByName("friends", "friend_uuid", "friend_name", normalized);
    }

    public boolean areFriends(UUID player, UUID friend) {
        String sql = "SELECT 1 FROM " + database.table("friends")
                + " WHERE player_uuid=? AND friend_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPair(statement, player, friend);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public List<FriendEntry> list(UUID player) {
        String sql = "SELECT friend_uuid,friend_name,created_at FROM " + database.table("friends")
                + " WHERE player_uuid=? ORDER BY friend_name";
        List<FriendEntry> entries = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(new FriendEntry(
                            UUID.fromString(result.getString(1)),
                            result.getString(2),
                            result.getLong(3)
                    ));
                }
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw failure(exception);
        }
        return entries;
    }

    public RequestResult sendRequest(UUID sender, String senderName, UUID target, String targetName) {
        if (sender == null || target == null || sender.equals(target)) return RequestResult.FAILED;
        if (areFriends(sender, target)) return RequestResult.ALREADY_FRIENDS;
        if (hasRequest(sender, target)) return RequestResult.ALREADY_SENT;
        if (hasRequest(target, sender)) {
            accept(target, targetName, sender, senderName);
            return RequestResult.AUTO_ACCEPTED;
        }

        String sql = "INSERT INTO " + database.table("friend_requests")
                + " (sender_uuid,sender_name,target_uuid,target_name,created_at) VALUES(?,?,?,?,?)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sender.toString());
            statement.setString(2, senderName);
            statement.setString(3, target.toString());
            statement.setString(4, targetName);
            statement.setLong(5, System.currentTimeMillis());
            return statement.executeUpdate() > 0 ? RequestResult.SENT : RequestResult.FAILED;
        } catch (SQLException exception) {
            return RequestResult.FAILED;
        }
    }

    /** Backwards-compatible wrapper for older callers. */
    public boolean request(UUID sender, String senderName, UUID target, String targetName) {
        RequestResult result = sendRequest(sender, senderName, target, targetName);
        return result == RequestResult.SENT || result == RequestResult.AUTO_ACCEPTED;
    }

    public boolean hasRequest(UUID sender, UUID target) {
        String sql = "SELECT 1 FROM " + database.table("friend_requests")
                + " WHERE sender_uuid=? AND target_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPair(statement, sender, target);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public List<FriendRequest> incomingRequests(UUID target) {
        String sql = "SELECT sender_uuid,sender_name,created_at FROM " + database.table("friend_requests")
                + " WHERE target_uuid=? ORDER BY created_at DESC";
        List<FriendRequest> requests = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    requests.add(new FriendRequest(
                            UUID.fromString(result.getString(1)),
                            result.getString(2),
                            result.getLong(3)
                    ));
                }
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw failure(exception);
        }
        return requests;
    }

    /** Loads requests for all supplied online targets with one SQL query. */
    public Map<UUID, List<FriendRequest>> incomingRequests(Collection<UUID> targets) {
        if (targets == null || targets.isEmpty()) return Map.of();
        List<UUID> ids = targets.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();

        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT target_uuid,sender_uuid,sender_name,created_at FROM "
                + database.table("friend_requests") + " WHERE target_uuid IN (" + placeholders + ")"
                + " ORDER BY target_uuid,created_at DESC";
        Map<UUID, List<FriendRequest>> requests = new HashMap<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (UUID id : ids) statement.setString(index++, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID target = UUID.fromString(result.getString(1));
                    FriendRequest request = new FriendRequest(
                            UUID.fromString(result.getString(2)),
                            result.getString(3),
                            result.getLong(4)
                    );
                    requests.computeIfAbsent(target, ignored -> new ArrayList<>()).add(request);
                }
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw failure(exception);
        }
        requests.replaceAll((target, entries) -> List.copyOf(entries));
        return Map.copyOf(requests);
    }

    public boolean deny(UUID sender, UUID target) {
        String sql = "DELETE FROM " + database.table("friend_requests")
                + " WHERE sender_uuid=? AND target_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPair(statement, sender, target);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public void accept(UUID sender, String senderName, UUID target, String targetName) {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                insertFriend(connection, sender, senderName, target, targetName);
                insertFriend(connection, target, targetName, sender, senderName);
                deleteRequest(connection, sender, target);
                ensureSettings(connection, sender, target);
                ensureSettings(connection, target, sender);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public void remove(UUID first, UUID second) {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                deleteSymmetricPair(connection, "friends", "player_uuid", "friend_uuid", first, second);
                deleteSymmetricPair(connection, "friend_settings", "player_uuid", "friend_uuid", first, second);
                deleteSymmetricPair(connection, "friend_home_permissions", "owner_uuid", "friend_uuid", first, second);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public FriendSettings getSettings(UUID owner, UUID friend) {
        String sql = "SELECT glow_enabled,chat_mark,join_leave,friendly_fire FROM "
                + database.table("friend_settings") + " WHERE player_uuid=? AND friend_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPair(statement, owner, friend);
            FriendSettings settings = null;
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) settings = readSettings(result);
            }
            return settings == null ? getDefaults(connection, owner) : settings;
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public void setSettings(UUID owner, UUID friend, FriendSettings settings) {
        String sql = upsert(
                "friend_settings",
                "player_uuid,friend_uuid,glow_enabled,chat_mark,join_leave,friendly_fire",
                "?,?,?,?,?,?",
                "glow_enabled=VALUES(glow_enabled),chat_mark=VALUES(chat_mark),"
                        + "join_leave=VALUES(join_leave),friendly_fire=VALUES(friendly_fire)",
                "glow_enabled=excluded.glow_enabled,chat_mark=excluded.chat_mark,"
                        + "join_leave=excluded.join_leave,friendly_fire=excluded.friendly_fire",
                "player_uuid,friend_uuid"
        );
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            statement.setString(2, friend.toString());
            bindSettings(statement, settings, 3);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    /** Replaces the individual values with the owner's current defaults. */
    public FriendSettings resetSettingsToDefaults(UUID owner, UUID friend) {
        FriendSettings defaults = getDefaults(owner);
        setSettings(owner, friend, defaults);
        return defaults;
    }

    public FriendSettings getDefaults(UUID owner) {
        try (Connection connection = database.connection()) {
            return getDefaults(connection, owner);
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public void setDefaults(UUID owner, FriendSettings settings) {
        String sql = upsert(
                "friend_defaults",
                "player_uuid,glow_enabled,chat_mark,join_leave,friendly_fire",
                "?,?,?,?,?",
                "glow_enabled=VALUES(glow_enabled),chat_mark=VALUES(chat_mark),"
                        + "join_leave=VALUES(join_leave),friendly_fire=VALUES(friendly_fire)",
                "glow_enabled=excluded.glow_enabled,chat_mark=excluded.chat_mark,"
                        + "join_leave=excluded.join_leave,friendly_fire=excluded.friendly_fire",
                "player_uuid"
        );
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            bindSettings(statement, settings, 2);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public boolean isHomeAllowed(UUID owner, UUID friend, String home) {
        String sql = "SELECT allowed FROM " + database.table("friend_home_permissions")
                + " WHERE owner_uuid=? AND friend_uuid=? AND home_name=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            statement.setString(2, friend.toString());
            statement.setString(3, normalizeHome(home));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public void setHomeAllowed(UUID owner, UUID friend, String home, boolean allowed) {
        String sql = upsert(
                "friend_home_permissions",
                "owner_uuid,friend_uuid,home_name,allowed",
                "?,?,?,?",
                "allowed=VALUES(allowed)",
                "allowed=excluded.allowed",
                "owner_uuid,friend_uuid,home_name"
        );
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            statement.setString(2, friend.toString());
            statement.setString(3, normalizeHome(home));
            statement.setBoolean(4, allowed);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public void deleteHomePermission(UUID owner, String home) {
        String sql = "DELETE FROM " + database.table("friend_home_permissions")
                + " WHERE owner_uuid=? AND home_name=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            statement.setString(2, normalizeHome(home));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public void deleteHomePermissionsExcept(UUID owner, Collection<String> existingHomes) {
        if (existingHomes == null || existingHomes.isEmpty()) {
            deleteAllHomePermissions(owner);
            return;
        }

        String placeholders = String.join(",", Collections.nCopies(existingHomes.size(), "?"));
        String sql = "DELETE FROM " + database.table("friend_home_permissions")
                + " WHERE owner_uuid=? AND home_name NOT IN (" + placeholders + ")";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            int index = 2;
            for (String home : existingHomes) statement.setString(index++, normalizeHome(home));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public boolean allowsBeingGlowed(UUID player) {
        String sql = "SELECT allow_be_glowed FROM " + database.table("friend_defaults")
                + " WHERE player_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return !result.next() || result.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public void setAllowsBeingGlowed(UUID player, boolean allowed) {
        String sql = upsert(
                "friend_defaults",
                "player_uuid,glow_enabled,chat_mark,join_leave,friendly_fire,allow_be_glowed",
                "?,?,?,?,?,?",
                "allow_be_glowed=VALUES(allow_be_glowed)",
                "allow_be_glowed=excluded.allow_be_glowed",
                "player_uuid"
        );
        try (Connection connection = database.connection()) {
            FriendSettings defaults = getDefaults(connection, player);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, player.toString());
                bindSettings(statement, defaults, 2);
                statement.setBoolean(6, allowed);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private UUID findUuidByName(String table, String uuidColumn, String nameColumn, String name) {
        String sql = "SELECT " + uuidColumn + " FROM " + database.table(table)
                + " WHERE LOWER(" + nameColumn + ")=LOWER(?) LIMIT 1";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? UUID.fromString(result.getString(1)) : null;
            }
        } catch (SQLException | IllegalArgumentException ignored) {
            // Older installations may not contain every lookup table.
            return null;
        }
    }

    private FriendSettings getDefaults(Connection connection, UUID owner) throws SQLException {
        String sql = "SELECT glow_enabled,chat_mark,join_leave,friendly_fire FROM "
                + database.table("friend_defaults") + " WHERE player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readSettings(result) : FriendSettings.defaults();
            }
        }
    }

    private FriendSettings readSettings(ResultSet result) throws SQLException {
        return new FriendSettings(
                result.getBoolean(1),
                result.getBoolean(2),
                result.getBoolean(3),
                result.getBoolean(4)
        );
    }

    private void insertFriend(Connection connection, UUID player, String playerName,
                              UUID friend, String friendName) throws SQLException {
        String sql = upsert(
                "friends",
                "player_uuid,player_name,friend_uuid,friend_name,created_at",
                "?,?,?,?,?",
                "player_name=VALUES(player_name),friend_name=VALUES(friend_name)",
                "player_name=excluded.player_name,friend_name=excluded.friend_name",
                "player_uuid,friend_uuid"
        );
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setString(2, playerName);
            statement.setString(3, friend.toString());
            statement.setString(4, friendName);
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void deleteRequest(Connection connection, UUID sender, UUID target) throws SQLException {
        String sql = "DELETE FROM " + database.table("friend_requests")
                + " WHERE sender_uuid=? AND target_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPair(statement, sender, target);
            statement.executeUpdate();
        }
    }

    private void ensureSettings(Connection connection, UUID owner, UUID friend) throws SQLException {
        FriendSettings defaults = getDefaults(connection, owner);
        String sql = insertIgnore(
                "friend_settings",
                "player_uuid,friend_uuid,glow_enabled,chat_mark,join_leave,friendly_fire",
                "?,?,?,?,?,?",
                "player_uuid,friend_uuid"
        );
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            statement.setString(2, friend.toString());
            bindSettings(statement, defaults, 3);
            statement.executeUpdate();
        }
    }

    private void deleteSymmetricPair(Connection connection, String table, String leftColumn,
                                     String rightColumn, UUID first, UUID second) throws SQLException {
        String sql = "DELETE FROM " + database.table(table)
                + " WHERE (" + leftColumn + "=? AND " + rightColumn + "=?)"
                + " OR (" + leftColumn + "=? AND " + rightColumn + "=?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, first.toString());
            statement.setString(2, second.toString());
            statement.setString(3, second.toString());
            statement.setString(4, first.toString());
            statement.executeUpdate();
        }
    }

    private void deleteAllHomePermissions(UUID owner) {
        String sql = "DELETE FROM " + database.table("friend_home_permissions") + " WHERE owner_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private void bindPair(PreparedStatement statement, UUID first, UUID second) throws SQLException {
        statement.setString(1, first.toString());
        statement.setString(2, second.toString());
    }

    private void bindSettings(PreparedStatement statement, FriendSettings settings, int startIndex)
            throws SQLException {
        statement.setBoolean(startIndex, settings.glow());
        statement.setBoolean(startIndex + 1, settings.chatMark());
        statement.setBoolean(startIndex + 2, settings.joinLeave());
        statement.setBoolean(startIndex + 3, settings.friendlyFire());
    }

    private String normalizeHome(String home) {
        return home.toLowerCase(Locale.ROOT);
    }

    private String upsert(String table, String columns, String values, String mariaUpdate,
                          String sqliteUpdate, String conflictColumns) {
        String base = "INSERT INTO " + database.table(table) + " (" + columns + ") VALUES(" + values + ")";
        return database.dialect() == StorageDialect.SQLITE
                ? base + " ON CONFLICT(" + conflictColumns + ") DO UPDATE SET " + sqliteUpdate
                : base + " ON DUPLICATE KEY UPDATE " + mariaUpdate;
    }

    private String insertIgnore(String table, String columns, String values, String conflictColumns) {
        String base = "INSERT INTO " + database.table(table) + " (" + columns + ") VALUES(" + values + ")";
        return database.dialect() == StorageDialect.SQLITE
                ? base + " ON CONFLICT(" + conflictColumns + ") DO NOTHING"
                : base + " ON DUPLICATE KEY UPDATE player_uuid=player_uuid";
    }

    private void rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private IllegalStateException failure(Exception exception) {
        return new IllegalStateException("Friends-Datenbankfehler", exception);
    }
}
