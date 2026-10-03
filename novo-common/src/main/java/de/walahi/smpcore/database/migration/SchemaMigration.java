package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;

public interface SchemaMigration {
    int version();
    String description();
    void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException;
}
