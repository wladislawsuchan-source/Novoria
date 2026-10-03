package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds pending verification and delayed Novo-Key delivery to referrals. */
public final class AddReferralVerificationMigration implements SchemaMigration {
    @Override public int version() { return 22; }
    @Override public String description() { return "Referral-Verifizierung und ausstehende Keys"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        String referrals = prefix + "referrals";
        String accounts = prefix + "referral_accounts";
        try (Statement statement = connection.createStatement()) {
            if (!columnExists(connection, referrals, "verified_at")) {
                statement.executeUpdate("ALTER TABLE " + referrals + " ADD COLUMN verified_at BIGINT NOT NULL DEFAULT 0");
            }
            if (!columnExists(connection, accounts, "pending_keys")) {
                statement.executeUpdate("ALTER TABLE " + accounts + " ADD COLUMN pending_keys INTEGER NOT NULL DEFAULT 0");
            }
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
