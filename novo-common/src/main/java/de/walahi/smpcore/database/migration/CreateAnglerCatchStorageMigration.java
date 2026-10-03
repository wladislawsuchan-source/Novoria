package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Durable per-player catch storage; inventory size is derived from profession prestige. */
public final class CreateAnglerCatchStorageMigration implements SchemaMigration {
    @Override public int version() { return 34; }
    @Override public String description() { return "Create Angler catch storage"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        String blob = dialect == StorageDialect.SQLITE ? "BLOB" : "LONGBLOB";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "angler_catch_storage ("
                    + "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "contents " + blob + " NULL,"
                    + "updated_at BIGINT NOT NULL)");
        }
    }
}
