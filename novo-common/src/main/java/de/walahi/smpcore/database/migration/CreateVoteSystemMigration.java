package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Stores every received vote and the independently claimable reward parts. */
public final class CreateVoteSystemMigration implements SchemaMigration {
    @Override public int version() { return 26; }
    @Override public String description() { return "Persistentes Vote-System"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        String table = tablePrefix + "vote_events";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (" +
                    "vote_id VARCHAR(64) NOT NULL PRIMARY KEY," +
                    "service_name VARCHAR(128) NOT NULL," +
                    "player_name VARCHAR(64) NOT NULL," +
                    "player_uuid VARCHAR(36) NULL," +
                    "vote_address VARCHAR(128) NOT NULL," +
                    "vote_timestamp VARCHAR(64) NOT NULL," +
                    "received_at BIGINT NOT NULL," +
                    "coins_rewarded INTEGER NOT NULL DEFAULT 0," +
                    "key_rewarded INTEGER NOT NULL DEFAULT 0," +
                    "rewarded_at BIGINT NULL" +
                    ")");
        }
    }
}
