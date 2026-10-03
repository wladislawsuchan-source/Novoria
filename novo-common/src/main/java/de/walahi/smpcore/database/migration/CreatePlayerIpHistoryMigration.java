package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Stores every player/IP combination seen on the SMP for staff account checks. */
public final class CreatePlayerIpHistoryMigration implements SchemaMigration {
    @Override public int version() { return 28; }
    @Override public String description() { return "Spieler-IP-Verlauf"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        String table = tablePrefix + "player_ip_history";
        try (Statement statement = connection.createStatement()) {
            String indexes = dialect == StorageDialect.SQLITE ? "" :
                    ",INDEX " + tablePrefix + "idx_player_ip_address (ip_address)" +
                    ",INDEX " + tablePrefix + "idx_player_ip_name (player_name)";
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (" +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "player_name VARCHAR(64) NOT NULL," +
                    "ip_address VARCHAR(45) NOT NULL," +
                    "first_seen BIGINT NOT NULL," +
                    "last_seen BIGINT NOT NULL," +
                    "PRIMARY KEY (player_uuid, ip_address)" + indexes +
                    ")");
            if (dialect == StorageDialect.SQLITE) {
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS " + tablePrefix +
                        "idx_player_ip_address ON " + table + " (ip_address)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS " + tablePrefix +
                        "idx_player_ip_name ON " + table + " (player_name)");
            }
        }
    }
}
