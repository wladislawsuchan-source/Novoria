package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Stores durable collection-reward unlocks separately from the later physical item claim. */
public final class CreateHeadRewardsMigration implements SchemaMigration {
    @Override public int version() { return 19; }
    @Override public String description() { return "Kopfsammlungs-Belohnungen"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "head_collection_rewards (" +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "reward_id VARCHAR(64) NOT NULL, " +
                    "unlocked_at BIGINT NOT NULL, " +
                    "claimed_at BIGINT NULL, " +
                    "PRIMARY KEY(player_uuid, reward_id)" +
                    ")");
        }
    }
}
