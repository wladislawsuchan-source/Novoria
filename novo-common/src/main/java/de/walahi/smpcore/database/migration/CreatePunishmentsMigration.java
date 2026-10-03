package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class CreatePunishmentsMigration implements SchemaMigration {
    @Override public int version() { return 1; }
    @Override public String description() { return "Create punishments table and indexes"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        String table = tablePrefix + "punishments";
        String idDefinition = dialect == StorageDialect.SQLITE
                ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        String nameType = dialect == StorageDialect.SQLITE ? "TEXT" : "VARCHAR(255)";
        String activeType = dialect == StorageDialect.SQLITE ? "INTEGER" : "TINYINT";

        String sql = "CREATE TABLE IF NOT EXISTS " + table + " (" +
                "id " + idDefinition + ", " +
                "player_uuid VARCHAR(36) NOT NULL, " +
                "player_name " + nameType + " NOT NULL, " +
                "staff_uuid VARCHAR(36), " +
                "staff_name " + nameType + " NOT NULL, " +
                "type VARCHAR(32) NOT NULL, " +
                "reason TEXT NOT NULL, " +
                "created_at BIGINT NOT NULL, " +
                "expires_at BIGINT, " +
                "active " + activeType + " NOT NULL DEFAULT 1, " +
                "revoked_at BIGINT, " +
                "revoked_by_uuid VARCHAR(36), " +
                "revoked_by_name " + nameType +
                ")";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            createIndex(statement, dialect, tablePrefix + "idx_punishments_player", table, "player_uuid");
            createIndex(statement, dialect, tablePrefix + "idx_punishments_active_type", table, "player_uuid, type, active");
        }
    }

    private static void createIndex(Statement statement, StorageDialect dialect, String index,
                                    String table, String columns) throws SQLException {
        if (dialect == StorageDialect.SQLITE) {
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS " + index + " ON " + table + "(" + columns + ")");
            return;
        }
        try {
            statement.executeUpdate("CREATE INDEX " + index + " ON " + table + "(" + columns + ")");
        } catch (SQLException exception) {
            if (exception.getErrorCode() != 1061) throw exception;
        }
    }
}
