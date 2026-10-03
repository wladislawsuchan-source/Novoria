package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Stores player-planted saplings until they actually grow into trees. */
public final class CreateProfessionSaplingsMigration implements SchemaMigration {
    @Override public int version() { return 16; }
    @Override public String description() { return "Holzfäller-Aufforstung und gewachsene Setzlinge"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "profession_planted_saplings (" +
                    "world_uuid VARCHAR(36) NOT NULL, " +
                    "block_x INTEGER NOT NULL, " +
                    "block_y INTEGER NOT NULL, " +
                    "block_z INTEGER NOT NULL, " +
                    "owner_uuid VARCHAR(36) NOT NULL, " +
                    "profession_id VARCHAR(32) NOT NULL, " +
                    "prestige INTEGER NOT NULL, " +
                    "milestone INTEGER NOT NULL, " +
                    "growth_group_id VARCHAR(64) NOT NULL, " +
                    "sapling_material VARCHAR(64) NOT NULL, " +
                    "planted_at BIGINT NOT NULL, " +
                    "PRIMARY KEY(world_uuid, block_x, block_y, block_z)" +
                    ")");
            createIndex(statement, dialect, prefix + "idx_profession_saplings_owner",
                    prefix + "profession_planted_saplings", "owner_uuid, profession_id, prestige, milestone");
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
