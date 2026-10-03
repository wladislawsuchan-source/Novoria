package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tracks one-shot data repairs for head-collection rewards.
 *
 * <p>1.53.4 could unlock missing default milestones with the generic 10-head fallback.
 * A repair marker lets NovoSMP correct only that historical mistake once per player without
 * weakening the intended rule that legitimately earned collection milestones stay permanent.</p>
 */
public final class RepairHeadCollectionRewardsMigration implements SchemaMigration {
    @Override
    public int version() {
        return 24;
    }

    @Override
    public String description() {
        return "Einmalige Reparatur fehlerhafter Kopfsammlungs-Rewards";
    }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        String table = tablePrefix + "head_collection_reward_repairs";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (" +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "repair_id VARCHAR(64) NOT NULL," +
                    "repaired_at BIGINT NOT NULL," +
                    "PRIMARY KEY (player_uuid, repair_id)" +
                    ")");
        }
    }
}
