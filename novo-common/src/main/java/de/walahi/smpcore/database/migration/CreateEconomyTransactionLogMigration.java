package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Creates an append-only audit log for every successful economy change. */
public final class CreateEconomyTransactionLogMigration implements SchemaMigration {
    @Override public int version() { return 3; }
    @Override public String description() { return "Create economy transaction audit log"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        String table = tablePrefix + "economy_transactions";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (" +
                    "log_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "transaction_uuid VARCHAR(36) NOT NULL, " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "transaction_type VARCHAR(24) NOT NULL, " +
                    "amount BIGINT NOT NULL, " +
                    "balance_before BIGINT NOT NULL, " +
                    "balance_after BIGINT NOT NULL, " +
                    "reason VARCHAR(255) NOT NULL, " +
                    "source VARCHAR(32) NOT NULL, " +
                    "actor_uuid VARCHAR(36), " +
                    "target_uuid VARCHAR(36), " +
                    "created_at BIGINT NOT NULL" +
                    ")");
        }
    }
}
