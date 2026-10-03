package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Stores personal mob-head collection unlocks. */
public final class CreateHeadCollectionMigration implements SchemaMigration {
    @Override public int version() { return 17; }
    @Override public String description() { return "Mobkopf-Sammlung und persönliche Trophäen"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "head_collection (" +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "head_id VARCHAR(128) NOT NULL, " +
                    "unlocked_at BIGINT NOT NULL, " +
                    "PRIMARY KEY(player_uuid, head_id)" +
                    ")");
            createIndex(statement, dialect, prefix + "idx_head_collection_player",
                    prefix + "head_collection", "player_uuid, unlocked_at");
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
