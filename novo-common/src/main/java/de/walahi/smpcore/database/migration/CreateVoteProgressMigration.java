package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Stores how many recurring vote milestones have already been paid per player. */
public final class CreateVoteProgressMigration implements SchemaMigration {
    @Override public int version() { return 27; }
    @Override public String description() { return "Vote-Meilenstein-Fortschritt"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        String table = tablePrefix + "vote_progress";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                    "milestones_rewarded BIGINT NOT NULL DEFAULT 0" +
                    ")");
        }
    }
}
