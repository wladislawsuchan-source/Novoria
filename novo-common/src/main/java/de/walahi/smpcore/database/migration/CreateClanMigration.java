package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Durable clan state. Runtime-only parties deliberately stay out of SQL. */
public final class CreateClanMigration implements SchemaMigration {
    @Override public int version() { return 31; }
    @Override public String description() { return "Create clans, members, homes, chest and snitches"; }

    @Override public void apply(Connection connection, StorageDialect dialect, String p) throws SQLException {
        String bool = dialect == StorageDialect.SQLITE ? "INTEGER" : "TINYINT(1)";
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "clans (" +
                    "clan_uuid VARCHAR(36) PRIMARY KEY,name VARCHAR(32) NOT NULL,name_norm VARCHAR(32) NOT NULL UNIQUE," +
                    "tag VARCHAR(16) NOT NULL,tag_norm VARCHAR(16) NOT NULL UNIQUE,tag_color VARCHAR(7) NOT NULL," +
                    "level INTEGER NOT NULL,bank BIGINT NOT NULL,leader_uuid VARCHAR(36) NOT NULL," +
                    "friendly_fire " + bool + " NOT NULL DEFAULT 0,bonus_home " + bool + " NOT NULL DEFAULT 0," +
                    "renamed_at BIGINT NOT NULL DEFAULT 0,tagged_at BIGINT NOT NULL DEFAULT 0,created_at BIGINT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "clan_members (" +
                    "clan_uuid VARCHAR(36) NOT NULL,player_uuid VARCHAR(36) NOT NULL UNIQUE,player_name VARCHAR(32) NOT NULL," +
                    "role VARCHAR(16) NOT NULL,joined_at BIGINT NOT NULL,PRIMARY KEY(clan_uuid,player_uuid))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "clan_homes (" +
                    "clan_uuid VARCHAR(36) NOT NULL,home_name VARCHAR(32) NOT NULL,display_name VARCHAR(32) NOT NULL," +
                    "world VARCHAR(128) NOT NULL,x DOUBLE NOT NULL,y DOUBLE NOT NULL,z DOUBLE NOT NULL,yaw FLOAT NOT NULL,pitch FLOAT NOT NULL," +
                    "PRIMARY KEY(clan_uuid,home_name))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "clan_chest (" +
                    "clan_uuid VARCHAR(36) NOT NULL,slot_index INTEGER NOT NULL,item_data BLOB NOT NULL," +
                    "PRIMARY KEY(clan_uuid,slot_index))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "clan_former_members (" +
                    "clan_uuid VARCHAR(36) NOT NULL,player_uuid VARCHAR(36) NOT NULL,player_name VARCHAR(32) NOT NULL,left_at BIGINT NOT NULL," +
                    "PRIMARY KEY(clan_uuid,player_uuid))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "clan_snitches (" +
                    "clan_uuid VARCHAR(36) NOT NULL,player_uuid VARCHAR(36) NOT NULL,player_name VARCHAR(32) NOT NULL," +
                    "marked_by_uuid VARCHAR(36) NOT NULL,marked_by_name VARCHAR(32) NOT NULL,marked_at BIGINT NOT NULL," +
                    "PRIMARY KEY(clan_uuid,player_uuid))");
        }
    }
}
