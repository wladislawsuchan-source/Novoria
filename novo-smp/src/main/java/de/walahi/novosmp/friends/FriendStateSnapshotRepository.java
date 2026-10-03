package de.walahi.novosmp.friends;

import de.walahi.smpcore.database.DatabaseManager;
import de.walahi.smpcore.friends.FriendSettings;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads all online-to-online friend state in two bounded SQL queries. */
public final class FriendStateSnapshotRepository {
    public record Relation(UUID owner, UUID friend, FriendSettings settings) { }
    public record Snapshot(boolean successful, List<Relation> relations, Map<UUID, Boolean> glowPermissions) {
        public static Snapshot failed() {
            return new Snapshot(false, List.of(), Map.of());
        }
    }

    private final JavaPlugin plugin;
    private final DatabaseManager database;

    public FriendStateSnapshotRepository(JavaPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    public Snapshot load(Collection<UUID> players) {
        List<UUID> ids = players == null ? List.of() : players.stream().distinct().toList();
        if (ids.isEmpty()) return new Snapshot(true, List.of(), Map.of());
        try {
            return new Snapshot(true, loadRelations(ids), loadGlowPermissions(ids));
        } catch (SQLException | IllegalArgumentException exception) {
            plugin.getLogger().warning("Online-Freundesstatus konnte nicht als Batch geladen werden: "
                    + exception.getMessage());
            return Snapshot.failed();
        }
    }

    private List<Relation> loadRelations(List<UUID> ids) throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT f.player_uuid,f.friend_uuid," +
                "COALESCE(s.glow_enabled,d.glow_enabled,1)," +
                "COALESCE(s.chat_mark,d.chat_mark,1)," +
                "COALESCE(s.join_leave,d.join_leave,1)," +
                "COALESCE(s.friendly_fire,d.friendly_fire,0) " +
                "FROM " + database.table("friends") + " f " +
                "LEFT JOIN " + database.table("friend_settings") + " s " +
                "ON s.player_uuid=f.player_uuid AND s.friend_uuid=f.friend_uuid " +
                "LEFT JOIN " + database.table("friend_defaults") + " d " +
                "ON d.player_uuid=f.player_uuid " +
                "WHERE f.player_uuid IN (" + placeholders + ") " +
                "AND f.friend_uuid IN (" + placeholders + ")";
        List<Relation> relations = new ArrayList<>();
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindIds(statement, ids, 1);
            bindIds(statement, ids, index);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    relations.add(new Relation(
                            UUID.fromString(result.getString(1)),
                            UUID.fromString(result.getString(2)),
                            new FriendSettings(result.getBoolean(3), result.getBoolean(4),
                                    result.getBoolean(5), result.getBoolean(6))
                    ));
                }
            }
        }
        return relations;
    }

    private Map<UUID, Boolean> loadGlowPermissions(List<UUID> ids) throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT player_uuid,allow_be_glowed FROM " + database.table("friend_defaults")
                + " WHERE player_uuid IN (" + placeholders + ")";
        Map<UUID, Boolean> permissions = new HashMap<>();
        for (UUID id : ids) permissions.put(id, true);
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindIds(statement, ids, 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) permissions.put(UUID.fromString(result.getString(1)), result.getBoolean(2));
            }
        }
        return permissions;
    }

    private int bindIds(PreparedStatement statement, List<UUID> ids, int startIndex) throws SQLException {
        int index = startIndex;
        for (UUID id : ids) statement.setString(index++, id.toString());
        return index;
    }
}
