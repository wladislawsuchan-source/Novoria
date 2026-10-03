package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Stores whether a player explicitly unlocked/revealed their personal referral code. */
public final class AddReferralCodeUnlockMigration implements SchemaMigration {
    @Override public int version() { return 23; }
    @Override public String description() { return "Referral-Code-Freischaltung"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        String accounts = prefix + "referral_accounts";
        if (columnExists(connection, accounts, "code_unlocked")) return;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + accounts + " ADD COLUMN code_unlocked INTEGER NOT NULL DEFAULT 0");
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : new String[]{table, table.toUpperCase(), table.toLowerCase()}) {
            try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, candidate, column)) {
                if (result.next()) return true;
            }
        }
        return false;
    }
}
