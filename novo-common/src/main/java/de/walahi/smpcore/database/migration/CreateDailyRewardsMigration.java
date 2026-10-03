package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class CreateDailyRewardsMigration implements SchemaMigration {
    @Override public int version() { return 8; }
    @Override public String description() { return "Create daily rewards progress table"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "daily_rewards (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "current_day INTEGER NOT NULL DEFAULT 1, " +
                    "last_claim_date VARCHAR(10) NULL, " +
                    "total_claims BIGINT NOT NULL DEFAULT 0, " +
                    "updated_at BIGINT NOT NULL" +
                    ")");
        }
    }
}
