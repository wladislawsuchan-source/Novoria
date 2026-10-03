package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Persistente Refer-a-Friend-Codes, Einlösungen, Punkte und Stufen-Claims. */
public final class CreateReferralMigration implements SchemaMigration {
    @Override public int version() { return 21; }
    @Override public String description() { return "Refer-a-Friend-System"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        String text = dialect == StorageDialect.SQLITE ? "TEXT" : "VARCHAR(64)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "referral_accounts (" +
                    "player_uuid " + text + " NOT NULL PRIMARY KEY," +
                    "player_name " + text + " NOT NULL," +
                    "referral_code " + text + " NOT NULL UNIQUE," +
                    "points INTEGER NOT NULL DEFAULT 0," +
                    "pending_keys INTEGER NOT NULL DEFAULT 0," +
                    "created_at BIGINT NOT NULL" +
                    ")");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "referrals (" +
                    "referred_uuid " + text + " NOT NULL PRIMARY KEY," +
                    "referrer_uuid " + text + " NOT NULL," +
                    "redeemed_at BIGINT NOT NULL," +
                    "verified_at BIGINT NOT NULL DEFAULT 0" +
                    ")");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "referral_reward_claims (" +
                    "player_uuid " + text + " NOT NULL," +
                    "tier_id INTEGER NOT NULL," +
                    "claimed_at BIGINT NOT NULL," +
                    "PRIMARY KEY (player_uuid,tier_id)" +
                    ")");
        }
    }
}
