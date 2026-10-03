package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds the player-wide privacy switch that decides whether friends may glow this player. */
public final class AddFriendGlowPrivacyMigration implements SchemaMigration {
    @Override public int version() { return 14; }
    @Override public String description() { return "Persönliche Glow-Freigabe"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        String bool = dialect == StorageDialect.SQLITE ? "INTEGER" : "TINYINT(1)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + prefix + "friend_defaults ADD COLUMN allow_be_glowed " + bool + " NOT NULL DEFAULT 1");
        }
    }
}
