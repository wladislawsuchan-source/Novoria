package de.walahi.smpcore.punishments;

import de.walahi.smpcore.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Database-neutral JDBC repository for SQLite, MySQL and MariaDB. */
public final class JdbcPunishmentRepository implements PunishmentRepository {
    private final DatabaseManager databaseManager;

    public JdbcPunishmentRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager");
    }

    @Override
    public synchronized Punishment create(UUID playerUuid, String playerName, UUID staffUuid, String staffName,
                                          PunishmentType type, String reason, Instant expiresAt) throws SQLException {
        String sql = "INSERT INTO " + table() + " " +
                "(player_uuid, player_name, staff_uuid, staff_name, type, reason, created_at, expires_at, active) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)";
        Instant createdAt = Instant.now();
        Connection connection = databaseManager.connection();
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            setUuid(statement, 3, staffUuid);
            statement.setString(4, staffName);
            statement.setString(5, type.name());
            statement.setString(6, reason);
            statement.setLong(7, createdAt.toEpochMilli());
            setInstant(statement, 8, expiresAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Die Datenbank hat keine ID für das Punishment zurückgegeben.");
                return new Punishment(keys.getLong(1), playerUuid, playerName, staffUuid, staffName, type,
                        reason, createdAt, expiresAt, true, null, null, null);
            }
        } finally {
            databaseManager.closeIfPooled(connection);
        }
    }

    @Override
    public synchronized Optional<Punishment> findActive(UUID playerUuid, PunishmentType type, Instant now) throws SQLException {
        String sql = "SELECT * FROM " + table() + " WHERE player_uuid = ? AND type = ? AND active = 1 " +
                "AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC LIMIT 1";
        Connection connection = databaseManager.connection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, type.name());
            statement.setLong(3, now.toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        } finally {
            databaseManager.closeIfPooled(connection);
        }
    }

    @Override
    public synchronized List<Punishment> findHistory(UUID playerUuid) throws SQLException {
        String sql = "SELECT * FROM " + table() + " WHERE player_uuid = ? ORDER BY created_at DESC";
        List<Punishment> history = new ArrayList<>();
        Connection connection = databaseManager.connection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) history.add(read(resultSet));
            }
        } finally {
            databaseManager.closeIfPooled(connection);
        }
        return List.copyOf(history);
    }

    @Override
    public synchronized boolean revokeActive(UUID playerUuid, PunishmentType type, UUID staffUuid, String staffName,
                                             Instant revokedAt) throws SQLException {
        String sql = "UPDATE " + table() + " SET active = 0, revoked_at = ?, revoked_by_uuid = ?, revoked_by_name = ? " +
                "WHERE player_uuid = ? AND type = ? AND active = 1";
        Connection connection = databaseManager.connection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, revokedAt.toEpochMilli());
            setUuid(statement, 2, staffUuid);
            statement.setString(3, staffName);
            statement.setString(4, playerUuid.toString());
            statement.setString(5, type.name());
            return statement.executeUpdate() > 0;
        } finally {
            databaseManager.closeIfPooled(connection);
        }
    }

    @Override
    public synchronized int deactivateExpired(Instant now) throws SQLException {
        String sql = "UPDATE " + table() + " SET active = 0 WHERE active = 1 AND expires_at IS NOT NULL AND expires_at <= ?";
        Connection connection = databaseManager.connection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now.toEpochMilli());
            return statement.executeUpdate();
        } finally {
            databaseManager.closeIfPooled(connection);
        }
    }

    private String table() { return databaseManager.table("punishments"); }

    private Punishment read(ResultSet resultSet) throws SQLException {
        return new Punishment(resultSet.getLong("id"), UUID.fromString(resultSet.getString("player_uuid")),
                resultSet.getString("player_name"), parseUuid(resultSet.getString("staff_uuid")),
                resultSet.getString("staff_name"), PunishmentType.valueOf(resultSet.getString("type")),
                resultSet.getString("reason"), Instant.ofEpochMilli(resultSet.getLong("created_at")),
                parseInstant(resultSet, "expires_at"), resultSet.getInt("active") == 1,
                parseInstant(resultSet, "revoked_at"), parseUuid(resultSet.getString("revoked_by_uuid")),
                resultSet.getString("revoked_by_name"));
    }

    private static void setUuid(PreparedStatement statement, int index, UUID uuid) throws SQLException {
        if (uuid == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, uuid.toString());
    }
    private static void setInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        if (instant == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, instant.toEpochMilli());
    }
    private static UUID parseUuid(String value) { return value == null || value.isBlank() ? null : UUID.fromString(value); }
    private static Instant parseInstant(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column); return resultSet.wasNull() ? null : Instant.ofEpochMilli(value);
    }
}
