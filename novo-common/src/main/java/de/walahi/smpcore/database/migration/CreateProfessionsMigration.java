package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Creates durable profession, profession-tool, contribution and booster storage. */
public final class CreateProfessionsMigration implements SchemaMigration {
    @Override public int version() { return 15; }
    @Override public String description() { return "Berufe, Meilensteine, Werkzeuge und Booster"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        String bool = dialect == StorageDialect.SQLITE ? "INTEGER" : "TINYINT(1)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "profession_profiles (" +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "profession_id VARCHAR(32) NOT NULL, " +
                    "prestige INTEGER NOT NULL DEFAULT 0, " +
                    "level INTEGER NOT NULL DEFAULT 1, " +
                    "xp DOUBLE NOT NULL DEFAULT 0, " +
                    "completed_milestone INTEGER NOT NULL DEFAULT 0, " +
                    "active_tool_serial VARCHAR(64) NULL, " +
                    "updated_at BIGINT NOT NULL, " +
                    "PRIMARY KEY(player_uuid, profession_id)" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "profession_meta (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "free_switch " + bool + " NOT NULL DEFAULT 0, " +
                    "second_slot_unlocked " + bool + " NOT NULL DEFAULT 0, " +
                    "updated_at BIGINT NOT NULL" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "profession_active (" +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "slot_index INTEGER NOT NULL, " +
                    "profession_id VARCHAR(32) NOT NULL, " +
                    "updated_at BIGINT NOT NULL, " +
                    "PRIMARY KEY(player_uuid, slot_index)" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "profession_contributions (" +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "profession_id VARCHAR(32) NOT NULL, " +
                    "prestige INTEGER NOT NULL, " +
                    "milestone INTEGER NOT NULL, " +
                    "requirement_id VARCHAR(64) NOT NULL, " +
                    "amount BIGINT NOT NULL DEFAULT 0, " +
                    "updated_at BIGINT NOT NULL, " +
                    "PRIMARY KEY(player_uuid, profession_id, prestige, milestone, requirement_id)" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "profession_tools (" +
                    "serial_id VARCHAR(64) NOT NULL PRIMARY KEY, " +
                    "owner_uuid VARCHAR(36) NOT NULL, " +
                    "profession_id VARCHAR(32) NOT NULL, " +
                    "prestige INTEGER NOT NULL, " +
                    "active " + bool + " NOT NULL DEFAULT 1, " +
                    "created_at BIGINT NOT NULL, " +
                    "deactivated_at BIGINT NULL" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "profession_reward_claims (" +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "profession_id VARCHAR(32) NOT NULL, " +
                    "prestige INTEGER NOT NULL, " +
                    "reward_level INTEGER NOT NULL, " +
                    "claimed_at BIGINT NOT NULL, " +
                    "PRIMARY KEY(player_uuid, profession_id, prestige, reward_level)" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "player_boosters (" +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "category VARCHAR(24) NOT NULL, " +
                    "multiplier DOUBLE NOT NULL, " +
                    "remaining_seconds INTEGER NOT NULL, " +
                    "updated_at BIGINT NOT NULL, " +
                    "PRIMARY KEY(player_uuid, category)" +
                    ")");

            createIndex(statement, dialect, prefix + "idx_profession_tools_owner", prefix + "profession_tools",
                    "owner_uuid, profession_id, active");
            createIndex(statement, dialect, prefix + "idx_profession_active_player", prefix + "profession_active",
                    "player_uuid, profession_id");
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
