package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Creates durable SMP home storage. */
public final class CreateHomesMigration implements SchemaMigration {
    @Override public int version() { return 9; }
    @Override public String description() { return "Create homes"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "homes (" +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "home_name VARCHAR(32) NOT NULL, " +
                    "display_name VARCHAR(64) NOT NULL, " +
                    "world VARCHAR(128) NOT NULL, " +
                    "x DOUBLE NOT NULL, " +
                    "y DOUBLE NOT NULL, " +
                    "z DOUBLE NOT NULL, " +
                    "yaw FLOAT NOT NULL, " +
                    "pitch FLOAT NOT NULL, " +
                    "created_at BIGINT NOT NULL, " +
                    "updated_at BIGINT NOT NULL, " +
                    "PRIMARY KEY (player_uuid, home_name)" +
                    ")");
        }
    }
}
