package de.walahi.novosmp.jumpnrun;

import de.walahi.smpcore.database.DatabaseManager;
import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Uses the existing player_stats table as the single persistent highscore source. */
final class JumpRunHighscoreRepository {
    private final DatabaseManager database;

    JumpRunHighscoreRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    Map<UUID, Entry> loadAll() throws SQLException {
        String sql = "SELECT player_uuid,player_name,jumpnrun_highscore FROM "
                + database.table("player_stats") + " WHERE jumpnrun_highscore > 0";
        Map<UUID, Entry> result = new HashMap<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    UUID playerId = UUID.fromString(rows.getString("player_uuid"));
                    long raw = Math.max(0L, rows.getLong("jumpnrun_highscore"));
                    int score = (int) Math.min(Integer.MAX_VALUE, raw);
                    String name = readableName(rows.getString("player_name"), playerId);
                    result.put(playerId, new Entry(playerId, name, score));
                } catch (IllegalArgumentException ignored) {
                    // Other stat systems already report malformed UUID rows; skip them here.
                }
            }
        }
        return result;
    }

    int saveIfHigher(UUID playerId, String playerName, int score) throws SQLException {
        int safeScore = Math.max(0, score);
        long now = System.currentTimeMillis();
        String table = database.table("player_stats");
        String sql;
        if (database.dialect() == StorageDialect.SQLITE) {
            sql = "INSERT INTO " + table
                    + " (player_uuid,player_name,jumpnrun_highscore,registered_at,last_seen) VALUES (?,?,?,?,?) "
                    + "ON CONFLICT(player_uuid) DO UPDATE SET "
                    + "player_name=excluded.player_name,jumpnrun_highscore=MAX("
                    + table + ".jumpnrun_highscore,excluded.jumpnrun_highscore)";
        } else {
            sql = "INSERT INTO " + table
                    + " (player_uuid,player_name,jumpnrun_highscore,registered_at,last_seen) VALUES (?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE player_name=VALUES(player_name),"
                    + "jumpnrun_highscore=GREATEST(jumpnrun_highscore,VALUES(jumpnrun_highscore))";
        }
        try (Connection connection = database.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, readableName(playerName, playerId));
                    statement.setInt(3, safeScore);
                    statement.setLong(4, now);
                    statement.setLong(5, now);
                    statement.executeUpdate();
                }
                int persisted = read(connection, playerId);
                connection.commit();
                return persisted;
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { exception.addSuppressed(rollbackFailure); }
                throw exception;
            } finally {
                try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException ignored) { }
            }
        }
    }

    private int read(Connection connection, UUID playerId) throws SQLException {
        String sql = "SELECT jumpnrun_highscore FROM " + database.table("player_stats") + " WHERE player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return 0;
                return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, row.getLong(1)));
            }
        }
    }

    private String readableName(String name, UUID playerId) {
        return name == null || name.isBlank() ? playerId.toString() : name;
    }

    record Entry(UUID playerId, String name, int score) { }
}
