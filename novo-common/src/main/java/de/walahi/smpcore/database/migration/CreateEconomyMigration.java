package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class CreateEconomyMigration implements SchemaMigration {
    @Override public int version() { return 2; }
    @Override public String description() { return "Create whole-coin economy accounts table"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        String table = tablePrefix + "economy_accounts";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "balance BIGINT NOT NULL DEFAULT 0, " +
                    "updated_at BIGINT NOT NULL" +
                    ")");
        }
    }
}
