package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Stores one pending, expiring death-back point per player. */
public final class CreateDeathBackMigration implements SchemaMigration {
    @Override public int version() { return 12; }
    @Override public String description() { return "Create persistent death-back points"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "death_back (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "world VARCHAR(128) NOT NULL, " +
                    "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, " +
                    "yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, " +
                    "created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL" +
                    ")");
        }
    }
}
