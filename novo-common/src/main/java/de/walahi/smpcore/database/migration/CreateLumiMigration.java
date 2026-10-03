package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class CreateLumiMigration implements SchemaMigration {
    @Override public int version() { return 7; }
    @Override public String description() { return "Create Lumi currency accounts table"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "lumi_accounts (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "balance BIGINT NOT NULL DEFAULT 0, " +
                    "updated_at BIGINT NOT NULL" +
                    ")");
        }
    }
}
