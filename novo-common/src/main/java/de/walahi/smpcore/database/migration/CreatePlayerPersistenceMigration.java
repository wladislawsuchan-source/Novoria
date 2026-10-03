package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Creates SQL-backed SMP statistics and last-location persistence. */
public final class CreatePlayerPersistenceMigration implements SchemaMigration {
    @Override public int version() { return 10; }
    @Override public String description() { return "Create player statistics and last SMP locations"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_stats (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "kills BIGINT NOT NULL DEFAULT 0, " +
                    "deaths BIGINT NOT NULL DEFAULT 0, " +
                    "blocks_mined BIGINT NOT NULL DEFAULT 0, " +
                    "mobs_killed BIGINT NOT NULL DEFAULT 0, " +
                    "playtime_seconds BIGINT NOT NULL DEFAULT 0, " +
                    "blocks_placed BIGINT NOT NULL DEFAULT 0, " +
                    "sell_earnings BIGINT NOT NULL DEFAULT 0, " +
                    "advancements BIGINT NOT NULL DEFAULT 0, " +
                    "registered_at BIGINT NOT NULL, " +
                    "last_seen BIGINT NOT NULL" +
                    ")");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_locations (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "world VARCHAR(128) NOT NULL, " +
                    "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, " +
                    "yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, updated_at BIGINT NOT NULL" +
                    ")");
        }
    }
}
