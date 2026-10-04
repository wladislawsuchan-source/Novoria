package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Quest instances are durable; hot progress is checkpointed in bounded batches. */
public final class CreateQuestSystemMigration implements SchemaMigration {
    @Override public int version() { return 35; }
    @Override public String description() { return "Create personal and global quests"; }

    @Override public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "quest_daily ("
                    + "instance_id VARCHAR(36) NOT NULL PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,"
                    + "quest_day VARCHAR(10) NOT NULL, slot_index INTEGER NOT NULL, definition_id VARCHAR(80) NOT NULL,"
                    + "progress DOUBLE NOT NULL, completed INTEGER NOT NULL, key_delivered INTEGER NOT NULL)");
            sql.executeUpdate("CREATE INDEX " + prefix + "idx_quest_daily_player ON " + prefix
                    + "quest_daily (player_uuid,quest_day)");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "quest_global_slot ("
                    + "slot_index INTEGER NOT NULL PRIMARY KEY, instance_id VARCHAR(36), definition_id VARCHAR(80),"
                    + "cooldown_left BIGINT NOT NULL, active_since BIGINT NOT NULL, winner_uuid VARCHAR(36))");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "quest_global_progress ("
                    + "instance_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL,"
                    + "progress DOUBLE NOT NULL, reached_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (instance_id,player_uuid))");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "quest_reward_ledger ("
                    + "instance_id VARCHAR(36) NOT NULL PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,"
                    + "lumi_amount BIGINT NOT NULL, daily_key INTEGER NOT NULL, awarded_at BIGINT NOT NULL)");
        }
    }
}
