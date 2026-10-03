package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class CreateFriendsMigration implements SchemaMigration {
    @Override public int version() { return 13; }
    @Override public String description() { return "Friendsystem, Anfragen, Einstellungen und Home-Freigaben"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        String text = dialect == StorageDialect.SQLITE ? "TEXT" : "VARCHAR(36)";
        String name = dialect == StorageDialect.SQLITE ? "TEXT" : "VARCHAR(16)";
        String bool = dialect == StorageDialect.SQLITE ? "INTEGER" : "TINYINT(1)";
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "friends (" +
                    "player_uuid " + text + " NOT NULL, player_name " + name + " NOT NULL, " +
                    "friend_uuid " + text + " NOT NULL, friend_name " + name + " NOT NULL, created_at BIGINT NOT NULL, " +
                    "PRIMARY KEY(player_uuid, friend_uuid))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "friend_requests (" +
                    "sender_uuid " + text + " NOT NULL, sender_name " + name + " NOT NULL, " +
                    "target_uuid " + text + " NOT NULL, target_name " + name + " NOT NULL, created_at BIGINT NOT NULL, " +
                    "PRIMARY KEY(sender_uuid, target_uuid))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "friend_settings (" +
                    "player_uuid " + text + " NOT NULL, friend_uuid " + text + " NOT NULL, " +
                    "glow_enabled " + bool + " NOT NULL DEFAULT 0, chat_mark " + bool + " NOT NULL DEFAULT 1, " +
                    "join_leave " + bool + " NOT NULL DEFAULT 1, friendly_fire " + bool + " NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(player_uuid, friend_uuid))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "friend_defaults (" +
                    "player_uuid " + text + " NOT NULL PRIMARY KEY, glow_enabled " + bool + " NOT NULL DEFAULT 0, " +
                    "chat_mark " + bool + " NOT NULL DEFAULT 1, join_leave " + bool + " NOT NULL DEFAULT 1, " +
                    "friendly_fire " + bool + " NOT NULL DEFAULT 0)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "friend_home_permissions (" +
                    "owner_uuid " + text + " NOT NULL, friend_uuid " + text + " NOT NULL, home_name " +
                    (dialect == StorageDialect.SQLITE ? "TEXT" : "VARCHAR(32)") + " NOT NULL, allowed " + bool + " NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(owner_uuid, friend_uuid, home_name))");
        }
    }
}
