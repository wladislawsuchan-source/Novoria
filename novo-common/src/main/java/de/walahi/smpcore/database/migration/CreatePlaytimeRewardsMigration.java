package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Speichert einmalig abgeholte Spielzeit-Meilensteine dupe-sicher. */
public final class CreatePlaytimeRewardsMigration implements SchemaMigration {
    @Override public int version() { return 20; }
    @Override public String description() { return "Spielzeit-Meilenstein-Belohnungen"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "playtime_rewards (" +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "milestone_id INTEGER NOT NULL, " +
                    "claimed_at BIGINT NOT NULL, " +
                    "PRIMARY KEY(player_uuid, milestone_id)" +
                    ")");
        }
    }
}
