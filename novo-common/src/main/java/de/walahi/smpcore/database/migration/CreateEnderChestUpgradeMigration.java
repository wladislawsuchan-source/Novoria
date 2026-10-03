package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class CreateEnderChestUpgradeMigration implements SchemaMigration {
    @Override public int version() { return 6; }
    @Override public String description() { return "Create expandable ender chest storage"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        String blob = dialect == StorageDialect.SQLITE ? "BLOB" : "LONGBLOB";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "enderchest_upgrades (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                    "upgrade_level INTEGER NOT NULL DEFAULT 0," +
                    "contents " + blob + " NULL," +
                    "updated_at BIGINT NOT NULL" +
                    ")");
        }
    }
}
