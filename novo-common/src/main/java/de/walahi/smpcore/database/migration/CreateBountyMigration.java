package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Persistent UUID-owned bounty totals. Contributions remain intentionally private. */
public final class CreateBountyMigration implements SchemaMigration {
    @Override public int version() { return 30; }
    @Override public String description() { return "Create persistent bounty totals"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "bounties (" +
                    "target_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "target_name VARCHAR(32) NOT NULL, " +
                    "amount BIGINT NOT NULL, " +
                    "updated_at BIGINT NOT NULL" +
                    ")");
        }
    }
}
