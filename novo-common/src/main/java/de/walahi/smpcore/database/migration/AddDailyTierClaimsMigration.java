package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds independently persisted Standard, Premium and Premium+ Daily claims. */
public final class AddDailyTierClaimsMigration implements SchemaMigration {
    @Override public int version() { return 29; }
    @Override public String description() { return "Add independent Daily tier claims"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + prefix
                    + "daily_rewards ADD COLUMN claim_mask INTEGER NOT NULL DEFAULT 0");
            // A claim made with the old system already included every eligible tier. Marking it
            // complete prevents an update from paying those rewards a second time on the same day.
            statement.executeUpdate("UPDATE " + prefix
                    + "daily_rewards SET claim_mask=15 WHERE last_claim_date IS NOT NULL");
        }
    }
}
